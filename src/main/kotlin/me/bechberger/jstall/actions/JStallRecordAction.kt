package me.bechberger.jstall.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import me.bechberger.femtocli.RunResult
import me.bechberger.jstall.settings.JStallSettings
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Action that runs `jstall record create <pid> -o <output>` and displays the result.
 *
 * When triggered from a run window, it extracts the PID from the running ProcessHandler.
 * When triggered outside a run window (e.g., from the Tools menu), it shows a JVM picker popup.
 *
 * The output file is written to the project base directory as `<pid>-<timestamp>.zip`.
 */
class JStallRecordAction : AbstractJvmAction() {

    override val pickerTitle = "Select JVM Process to Record"
    override val progressTitlePrefix = "JStall Record"

    /** Computed per-invocation so the timestamp is fresh. Accessed from buildArgs and onResult. */
    private var currentOutputFile: Path? = null

    override fun buildArgs(project: Project, pid: Long): List<String> {
        val settings = JStallSettings.getInstance()
        val baseDir = project.basePath ?: System.getProperty("user.home")
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        currentOutputFile = Path.of(baseDir, "$pid-$timestamp.zip")

        return buildList {
            add("record"); add("create"); add(pid.toString())
            add("-o"); add(currentOutputFile.toString())
            add("--interval"); add(settings.recordInterval)
            add("--count"); add(settings.state.recordSampleCount.toString())
            if (settings.state.fullDiagnostics) add("--full")
        }
    }

    override fun onResult(project: Project, pid: Long, result: RunResult) {
        // Recompute output file with project path for display
        val outputFile = currentOutputFile
            ?: Path.of(project.basePath ?: System.getProperty("user.home"), "$pid.zip")

        super.onResult(project, pid, result)

        if (result.exitCode() == 0) {
            ApplicationManager.getApplication().invokeLater({
                notifyInfo(project, "Recording saved to $outputFile")
            }, ModalityState.nonModal())
        }
    }
}