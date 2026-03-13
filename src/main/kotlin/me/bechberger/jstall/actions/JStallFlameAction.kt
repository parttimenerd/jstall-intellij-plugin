package me.bechberger.jstall.actions

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.jcef.JBCefBrowser
import me.bechberger.femtocli.RunResult
import me.bechberger.jstall.settings.JStallSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

/** Key used to stash the raw HTML on a tool-window Content tab. */
private val HTML_KEY: Key<String> = Key.create("JStallFlame.html")

/**
 * Action that runs `jstall flame <pid> --output <tmp>.html` and renders the
 * resulting flamegraph HTML inside an embedded JCEF browser in a tool window.
 *
 * Disabled on Windows (async-profiler is not supported there).
 */
class JStallFlameAction : AbstractJvmAction() {

    override val pickerTitle = "Select JVM Process for Flamegraph"
    override val progressTitlePrefix = "JStall Flame"
    override val progressDescription = "Capturing flame graph"

    override fun update(e: AnActionEvent) {
        // Disable entirely on Windows
        if (System.getProperty("os.name", "").lowercase().contains("win")) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        super.update(e)
    }

    override fun buildArgs(project: Project, pid: Long): ActionArgs {
        val tmpFile = Files.createTempFile("jstall-flame-$pid-", ".html")
        val state = JStallSettings.getInstance().state.copy()
        return ActionArgs(buildList {
            add("flame")
            add(pid.toString())
            add("--output"); add(tmpFile.toFile().absolutePath)
            add("--duration"); add("${state.flameDurationSeconds}s")
        }, tmpFile)
    }

    override fun onResult(project: Project, pid: Long, result: RunResult, outputFile: Path?) {
        val htmlFile = outputFile?.toFile()

        if (result.exitCode() != 0 || htmlFile == null || !htmlFile.exists()) {
            htmlFile?.delete()
            val errorText = buildString {
                append("jstall flame failed (exit ${result.exitCode()})\n\n")
                append(formatJStallOutput(result))
            }
            ApplicationManager.getApplication().invokeLater({
                showPlainConsole(project, buildResultTitle(pid), errorText)
                notifyError(project, "$progressTitlePrefix failed: exit ${result.exitCode()}")
            }, ModalityState.nonModal())
            return
        }

        val htmlContent = htmlFile.readText()
        // Clean up temp file after reading
        htmlFile.delete()

        ApplicationManager.getApplication().invokeLater({
            showFlameWindow(project, buildResultTitle(pid), htmlContent)
        }, ModalityState.nonModal())
    }

    private fun showFlameWindow(project: Project, title: String, htmlContent: String) {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        val toolWindowId = "JStall Flamegraph"

        val toolWindow = toolWindowManager.getToolWindow(toolWindowId)
            ?: toolWindowManager.registerToolWindow(toolWindowId) {
                anchor = ToolWindowAnchor.BOTTOM
                canCloseContent = true
            }.also { installGearAction(it, project) }

        val browser = JBCefBrowser()

        // Open external links (http/https) in the system browser instead of navigating
        // away from the flamegraph inside the embedded JCEF panel.
        val client = browser.jbCefClient

        // Intercept target="_blank" popups
        client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(
                cefBrowser: CefBrowser?, frame: CefFrame?, targetUrl: String?,
                targetFrameName: String?
            ): Boolean {
                if (targetUrl != null && (targetUrl.startsWith("http://") || targetUrl.startsWith("https://"))) {
                    BrowserUtil.browse(targetUrl)
                }
                return true // cancel the popup in all cases
            }
        }, browser.cefBrowser)

        // Intercept same-window navigation to external URLs
        client.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(
                cefBrowser: CefBrowser?, frame: CefFrame?, request: CefRequest?,
                userGesture: Boolean, isRedirect: Boolean
            ): Boolean {
                val url = request?.url ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    BrowserUtil.browse(url)
                    return true // cancel navigation inside the embedded browser
                }
                return false // allow data: / about:blank / internal navigation
            }
        }, browser.cefBrowser)

        browser.loadHTML(htmlContent)

        val browserComponent: JComponent = browser.component

        val contentManager = toolWindow.contentManager
        val content = contentManager.factory.createContent(browserComponent, title, false).also {
            it.isCloseable = true
            it.putUserData(HTML_KEY, htmlContent)
        }
        Disposer.register(content, browser)
        contentManager.addContent(content)
        contentManager.setSelectedContent(content)
        toolWindow.show()
    }

    /**
     * Adds a "Save Flamegraph…" action to the tool window's gear / title-bar menu.
     * It always operates on the currently selected tab.
     */
    private fun installGearAction(toolWindow: ToolWindow, project: Project) {
        val saveAction = object : AnAction("Save Flamegraph…") {
            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.BGT
            }
            override fun actionPerformed(e: AnActionEvent) {
                val content = toolWindow.contentManager.selectedContent ?: return
                val html = content.getUserData(HTML_KEY) ?: return
                val suggestedName = content.displayName
                    .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                    .trimEnd('_')

                val descriptor = FileSaverDescriptor(
                    "Save Flamegraph",
                    "Choose where to save the flamegraph HTML file",
                    "html"
                )
                val saveDialog = FileChooserFactory.getInstance()
                    .createSaveFileDialog(descriptor, project)
                val baseDir = project.basePath
                    ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
                val wrapper = saveDialog.save(baseDir, "$suggestedName.html") ?: return
                try {
                    wrapper.file.writeText(html)
                    notifyInfo(project, "Flamegraph saved to ${wrapper.file.absolutePath}")
                } catch (ex: Exception) {
                    notifyError(project, "Failed to save flamegraph: ${ex.message}")
                }
            }

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled =
                    toolWindow.contentManager.selectedContent?.getUserData(HTML_KEY) != null
            }
        }

        toolWindow.setAdditionalGearActions(DefaultActionGroup(saveAction))
    }
}