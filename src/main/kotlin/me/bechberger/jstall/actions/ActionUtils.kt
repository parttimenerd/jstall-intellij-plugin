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
import java.awt.BorderLayout
import java.io.File
import java.util.Collections
import java.util.LinkedHashMap
import javax.swing.JComponent

internal const val MAX_DISPLAY_LENGTH = 100

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
 * based on interval from settings, plus a two-second buffer to account for startup and analysis time.
 */
internal fun estimatedDurationMs(): Long {
    val state = JStallSettings.getInstance().state.copy()
    return state.recordIntervalSeconds.toLong() * 1000 + 2000
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
            layout = BorderLayout()
            add(console.component, BorderLayout.CENTER)
        }
    }, title)

    RunContentManager.getInstance(project).showRunContent(
        com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance(),
        descriptor
    )
}

private val LOG = Logger.getInstance("JStall")

private data class RecordingCacheEntry(val isRecording: Boolean, val timestamp: Long)
private const val RECORDING_CACHE_MAX_SIZE = 200
private const val RECORDING_CACHE_TTL_MS = 5000L
private val recordingCache: MutableMap<String, RecordingCacheEntry> =
    Collections.synchronizedMap(object : LinkedHashMap<String, RecordingCacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RecordingCacheEntry>?): Boolean =
            size > RECORDING_CACHE_MAX_SIZE
    })

/**
 * Checks if the zip file at [path] is a valid jstall recording
 * by verifying it contains recorded JVMs via [ReplayProvider].
 * Results are cached for [RECORDING_CACHE_TTL_MS] ms to avoid repeated I/O.
 */
internal fun isJStallRecording(path: String): Boolean {
    val now = System.currentTimeMillis()
    recordingCache[path]?.let { entry ->
        if (now - entry.timestamp < RECORDING_CACHE_TTL_MS) return entry.isRecording
    }
    val result = try {
        val file = File(path)
        if (!file.exists() || !file.isFile) false
        else ReplayProvider(file.toPath()).listRecordedJvms(null).isNotEmpty()
    } catch (ex: Exception) {
        false
    }
    recordingCache[path] = RecordingCacheEntry(result, now)
    return result
}

/**
 * Result of analyzing a jstall recording.
 */
internal data class RecordingAnalysisResult(
    val output: String,
    val exitCode: Int
)

/**
 * Runs `jstall status <path>` on a recording file and returns the formatted output.
 * Respects the [JStallSettings.State.fullDiagnostics] setting.
 * Must be called on a background thread.
 */
internal fun analyzeRecording(path: String): RecordingAnalysisResult {
    val state = JStallSettings.getInstance().state.copy()
    val args = mutableListOf("status", path)
    if (state.fullDiagnostics) {
        args.add("--full")
    }
    val result = runJStallCaptured(*args.toTypedArray())
    return RecordingAnalysisResult(formatJStallOutput(result), result.exitCode())
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
    } catch (ex: Exception) {
        LOG.info("JStall: $message", ex)
    }
}

internal fun notifyError(project: Project, message: String) {
    try {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("JStall Notifications")
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    } catch (ex: Exception) {
        LOG.error("JStall: $message", ex)
    }
}