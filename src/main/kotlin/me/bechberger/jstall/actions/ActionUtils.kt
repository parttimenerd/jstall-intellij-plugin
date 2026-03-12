package me.bechberger.jstall.actions

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import me.bechberger.femtocli.FemtoCli
import me.bechberger.femtocli.RunResult
import me.bechberger.jstall.Main
import me.bechberger.jstall.provider.ReplayProvider
import me.bechberger.jstall.settings.JStallSettings
import me.bechberger.jstall.util.JVMDiscovery
import java.io.File
import javax.swing.JComponent

private const val MAX_LABEL_LENGTH = 50

/** Format a JVM process entry for the picker popup, capping the main class to [MAX_LABEL_LENGTH] chars. */
internal fun formatJvmLabel(jvm: JVMDiscovery.JVMProcess): String {
    val mainClass = jvm.mainClass()
    val truncated = if (mainClass.length > MAX_LABEL_LENGTH) {
        mainClass.substring(0, MAX_LABEL_LENGTH - 1) + "…"
    } else {
        mainClass
    }
    return "${jvm.pid()} — $truncated"
}

private val ANSI_PATTERN = Regex("\u001B\\[[;\\d]*m")

internal fun stripAnsiCodes(text: String): String = ANSI_PATTERN.replace(text, "")

/**
 * Run a jstall command via FemtoCli and return the captured result.
 */
internal fun runJStallCaptured(vararg args: String): RunResult {
    return FemtoCli.builder()
        .commandConfig(Main::setFemtoCliCommandConfig)
        .runCaptured(Main(), *args)
}

/**
 * Format the output of a jstall command: stdout first, stderr appended if non-blank.
 */
internal fun formatJStallOutput(result: RunResult): String {
    return buildString {
        append(stripAnsiCodes(result.out()))
        if (result.err().isNotBlank()) {
            if (isNotEmpty() && !endsWith("\n")) append("\n")
            append("\n--- stderr ---\n")
            append(stripAnsiCodes(result.err()))
        }
    }
}

/**
 * The estimated total duration of a jstall command in milliseconds,
 * based on interval * sample count from settings.
 */
internal fun estimatedDurationMs(): Long {
    val settings = JStallSettings.getInstance()
    return settings.state.recordIntervalSeconds.toLong() * settings.state.recordSampleCount * 1000
}

/**
 * Show [text] in a new Run tool window tab with the given [title],
 * using plain normal output (no stack trace red highlighting).
 * Must be called on the EDT.
 */
internal fun showPlainConsole(project: Project, title: String, text: String) {
    val console = ConsoleViewImpl(project, true)
    console.print(text, ConsoleViewContentType.NORMAL_OUTPUT)

    val descriptor = RunContentDescriptor(console, null, object : JComponent() {
        init {
            layout = java.awt.BorderLayout()
            add(console.component, java.awt.BorderLayout.CENTER)
        }
    }, title)

    RunContentManager.getInstance(project).showRunContent(
        com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance(),
        descriptor
    )
}

private val LOG = Logger.getInstance("JStall")

/**
 * Checks if the zip file at [path] is a valid jstall recording
 * by verifying it contains recorded JVMs via [ReplayProvider].
 */
internal fun isJStallRecording(path: String): Boolean {
    return try {
        val file = File(path)
        if (!file.exists() || !file.isFile) return false
        ReplayProvider(file.toPath()).listRecordedJvms(null).isNotEmpty()
    } catch (_: Exception) {
        false
    }
}

/**
 * Returns the selected virtual file from the event if it is a jstall recording zip,
 * or null otherwise.
 */
internal fun getJStallRecordingFile(e: AnActionEvent): VirtualFile? {
    val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
    if (file.isDirectory || !file.extension.equals("zip", ignoreCase = true)) return null
    return if (isJStallRecording(file.path)) file else null
}

internal fun notifyInfo(project: Project, message: String) {
    try {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("JStall Notifications")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    } catch (_: Exception) {
        LOG.info("JStall: $message")
    }
}

internal fun notifyError(project: Project, message: String) {
    try {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("JStall Notifications")
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    } catch (_: Exception) {
        LOG.error("JStall: $message")
    }
}