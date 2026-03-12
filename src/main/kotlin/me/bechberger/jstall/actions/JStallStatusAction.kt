package me.bechberger.jstall.actions

import com.intellij.openapi.project.Project
import me.bechberger.jstall.settings.JStallSettings

/**
 * Action that runs `jstall status <pid>` and displays the output in a console tab.
 *
 * When triggered from a run window, it extracts the PID from the running ProcessHandler.
 * When triggered outside a run window (e.g., from the Tools menu), it shows a JVM picker popup.
 */
class JStallStatusAction : AbstractJvmAction() {

    override val pickerTitle = "Select JVM Process"
    override val progressTitlePrefix = "JStall Status"

    override fun buildArgs(project: Project, pid: Long): List<String> {
        val settings = JStallSettings.getInstance()
        return buildList {
            add("status")
            add(pid.toString())
            add("--interval")
            add(settings.recordInterval)
            if (settings.state.fullDiagnostics) add("--full")
        }
    }
}