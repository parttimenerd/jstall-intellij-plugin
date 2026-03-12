package me.bechberger.jstall

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object JStallIcons {
    @JvmField val Status: Icon = IconLoader.getIcon("/icons/jstallStatus.svg", JStallIcons::class.java)
    @JvmField val Record: Icon = IconLoader.getIcon("/icons/jstallRecord.svg", JStallIcons::class.java)
    @JvmField val RecordingFile: Icon = IconLoader.getIcon("/icons/jstallRecordingFile.svg", JStallIcons::class.java)
}