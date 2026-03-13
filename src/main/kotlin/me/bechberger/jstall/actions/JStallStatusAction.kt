package me.bechberger.jstall.actions

import com.intellij.openapi.project.Project

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
        return buildList {
            add("status"); add(pid.toString())
            addAll(commonArgs())
        }
    }
}