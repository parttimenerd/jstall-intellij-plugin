package me.bechberger.jstall.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import me.bechberger.femtocli.RunResult

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

    override fun buildArgs(project: Project, pid: Long): List<String> {
        val outputFile = buildOutputPath(project, pid)
        return buildList {
            add("record"); add("create"); add(pid.toString())
            add("-o"); add(outputFile.toString())
            addAll(commonArgs())
        }
    }

    override fun onResult(project: Project, pid: Long, result: RunResult) {
        val outputFile = currentOutputFile

        super.onResult(project, pid, result)

        if (result.exitCode() == 0) {
            ApplicationManager.getApplication().invokeLater({
                notifyInfo(project, "Recording saved to $outputFile")
            }, ModalityState.nonModal())
        }
    }
}