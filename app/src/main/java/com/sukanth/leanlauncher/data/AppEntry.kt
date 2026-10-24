package com.sukanth.leanlauncher.data

import android.content.ComponentName

/** An installed, launchable app shown as one tile. */
data class AppEntry(val raw: RawApp) {
    val packageName: String get() = raw.packageName
    val label: String get() = raw.label
    val key: String get() = raw.key
    val isTvApp: Boolean get() = raw.isTvApp

    val component: ComponentName get() = ComponentName(raw.packageName, raw.activityName)

    companion object {
        fun list(rawApps: List<RawApp>): List<AppEntry> = rawApps.map(::AppEntry)
    }
}