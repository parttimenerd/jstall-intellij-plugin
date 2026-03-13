package me.bechberger.jstall.actions

import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.concurrency.AppExecutorUtil
import me.bechberger.femtocli.RunResult
import me.bechberger.jstall.settings.JStallSettings
import me.bechberger.jstall.util.JVMDiscovery
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService

/**
 * Base class for JStall actions that target a running JVM process.
 *
 * Handles:
 * - PID extraction from run window, or JVM picker fallback
 * - Time-based progress bar (estimated from interval × count settings)
 * - Running FemtoCli on a background thread with progress updates
 */
abstract class AbstractJvmAction : DumbAwareAction() {

    protected val executor: ExecutorService =
        AppExecutorUtil.createBoundedApplicationPoolExecutor(javaClass.simpleName, 1)

    /** Holds the output file path computed during the last [buildArgs] call. */
    protected var currentOutputFile: Path? = null

    /**
     * Computes a timestamped output zip path inside the project base dir (or home dir).
     * Also stores it in [currentOutputFile].
     */
    protected fun buildOutputPath(project: Project, pid: Long): Path {
        val baseDir = project.basePath ?: System.getProperty("user.home")
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return Path.of(baseDir, "$pid-$timestamp.zip").also { currentOutputFile = it }
    }

    /**
     * Common CLI tail args shared by all actions: --interval, --count, and optionally --full.
     */
    protected fun commonArgs(): List<String> {
        val settings = JStallSettings.getInstance()
        return buildList {
            add("--interval"); add(settings.recordInterval)
            add("--dumps"); add(settings.state.recordSampleCount.toString())
            if (settings.state.fullDiagnostics) add("--full")
        }
    }

    /** Title shown in the JVM picker popup. */
    protected abstract val pickerTitle: String

    /** Title prefix shown in the progress bar, e.g. "JStall Status". */
    protected abstract val progressTitlePrefix: String

    /**
     * Build the CLI args for this action. Called on a background thread.
     */
    protected abstract fun buildArgs(project: Project, pid: Long): List<String>

    /**
     * Called after the command completes successfully on a background thread.
     * Default implementation shows output in a plain console and reports non-zero exit codes.
     * Override to add extra behaviour (e.g. notifications).
     */
    protected open fun onResult(project: Project, pid: Long, result: RunResult) {
        val output = formatJStallOutput(result)
        val title = buildResultTitle(pid)

        ApplicationManager.getApplication().invokeLater({
            showPlainConsole(project, title, output)
        }, ModalityState.nonModal())

        if (result.exitCode() != 0) {
            ApplicationManager.getApplication().invokeLater({
                notifyError(project, "$progressTitlePrefix exited with code ${result.exitCode()}")
            }, ModalityState.nonModal())
        }
    }

    protected fun buildResultTitle(pid: Long): String {
        val timestamp = com.intellij.util.text.DateFormatUtil.formatTimeWithSeconds(System.currentTimeMillis())
        return "$progressTitlePrefix $pid — $timestamp"
    }

    // ── update / actionPerformed ──

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val descriptor = e.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR)
        if (descriptor != null) {
            val handler = descriptor.processHandler
            e.presentation.isVisible = true
            e.presentation.isEnabled = handler != null && !handler.isProcessTerminated
        } else {
            e.presentation.isEnabledAndVisible = true
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val descriptor: RunContentDescriptor? = e.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR)
        val handler = descriptor?.processHandler

        if (handler is BaseProcessHandler<*> && !handler.isProcessTerminated) {
            runWithProgress(project, handler.process.pid())
        } else {
            showJvmPicker(project)
        }
    }

    // ── JVM picker ──

    private fun showJvmPicker(project: Project) {
        executor.execute {
            try {
                val jvms = JVMDiscovery.listJVMs()
                if (jvms.isEmpty()) {
                    ApplicationManager.getApplication().invokeLater({
                        notifyError(project, "No running JVMs found")
                    }, ModalityState.nonModal())
                    return@execute
                }
                ApplicationManager.getApplication().invokeLater({
                    val renderer = object : ColoredListCellRenderer<JVMDiscovery.JVMProcess>() {
                        override fun customizeCellRenderer(
                            list: javax.swing.JList<out JVMDiscovery.JVMProcess>,
                            value: JVMDiscovery.JVMProcess,
                            index: Int,
                            selected: Boolean,
                            hasFocus: Boolean
                        ) {
                            val fqcn = value.mainClass()
                            append(value.pid().toString(), SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                            appendTextPadding(60)
                            append(fqcn.substring(0, fqcn.length.coerceAtMost(100)), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        }
                    }
                    JBPopupFactory.getInstance()
                        .createPopupChooserBuilder(jvms)
                        .setTitle(pickerTitle)
                        .setRenderer(renderer)
                        .setNamerForFiltering { formatJvmLabel(it) }
                        .setItemChosenCallback { selectedValue: JVMDiscovery.JVMProcess ->
                            runWithProgress(project, selectedValue.pid())
                        }
                        .createPopup()
                        .showCenteredInCurrentWindow(project)
                }, ModalityState.nonModal())
            } catch (ex: Exception) {
                thisLogger().warn("Failed to list JVMs", ex)
                ApplicationManager.getApplication().invokeLater({
                    notifyError(project, "Failed to list JVMs: ${ex.message}")
                }, ModalityState.nonModal())
            }
        }
    }

    // ── Progress + execution ──

    private fun runWithProgress(project: Project, pid: Long) {
        val estimatedTotalMs = estimatedDurationMs()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "$progressTitlePrefix PID $pid", false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.fraction = 0.0
                indicator.text = "$progressTitlePrefix PID $pid…"
                indicator.text2 = "0%"

                val startTime = System.currentTimeMillis()
                val args = buildArgs(project, pid)

                val futureResult: CompletableFuture<RunResult> = CompletableFuture.supplyAsync({
                    runJStallCaptured(*args.toTypedArray())
                }, executor)

                while (!futureResult.isDone) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val fraction = if (estimatedTotalMs > 0) {
                        (elapsed.toDouble() / estimatedTotalMs).coerceAtMost(0.99)
                    } else 0.0
                    indicator.fraction = fraction
                    indicator.text2 = "${(fraction * 100).toInt()}%"
                    try { Thread.sleep(250) } catch (_: InterruptedException) { break }
                }

                indicator.fraction = 1.0
                indicator.text2 = "100%"
                indicator.text = "Processing…"

                val result = try {
                    futureResult.get()
                } catch (ex: Exception) {
                    thisLogger().warn("$progressTitlePrefix failed for PID $pid", ex)
                    ApplicationManager.getApplication().invokeLater({
                        notifyError(project, "$progressTitlePrefix failed: ${ex.cause?.message ?: ex.message}")
                    }, ModalityState.nonModal())
                    return
                }

                onResult(project, pid, result)
            }
        })
    }
}