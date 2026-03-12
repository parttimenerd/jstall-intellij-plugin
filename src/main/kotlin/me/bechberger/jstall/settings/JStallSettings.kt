package me.bechberger.jstall.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "JStallSettings", storages = [Storage("jstall.xml")])
class JStallSettings : PersistentStateComponent<JStallSettings.State> {

    data class State(
        /** Record interval in seconds */
        var recordIntervalSeconds: Int = 5,
        /** Number of samples for interval-based data */
        var recordSampleCount: Int = 2,
        /** Include expensive diagnostics (--full) */
        var fullDiagnostics: Boolean = false,
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    val recordInterval: String
        get() = "${myState.recordIntervalSeconds}s"

    companion object {
        fun getInstance(): JStallSettings =
            ApplicationManager.getApplication().getService(JStallSettings::class.java)
    }
}