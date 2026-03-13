package me.bechberger.jstall.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

class JStallConfigurable : BoundConfigurable("JStall") {

    private val settings = JStallSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group("General") {
            row {
                checkBox("Include expensive diagnostics (--full)")
                    .bindSelected(settings.state::fullDiagnostics)
            }
        }
        group("Record Settings") {
            row("Sample interval (seconds):") {
                spinner(1..300, 1)
                    .bindIntValue(settings.state::recordIntervalSeconds)
            }
        }
        group("Flame Settings") {
            row("Profiling duration (seconds):") {
                spinner(1..300, 1)
                    .bindIntValue(settings.state::flameDurationSeconds)
            }
        }
    }
}