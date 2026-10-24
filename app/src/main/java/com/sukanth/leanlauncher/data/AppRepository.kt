package com.sukanth.leanlauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

/** Reads installed launchable apps and builds intents to open them. */
class AppRepository(context: Context) {

    private val appContext = context.applicationContext
    private val packageManager: PackageManager get() = appContext.packageManager

    /**
     * Fully qualified home component used in the ADB fallback. The `pkg/.Class` shorthand
     * is also valid, but the full form is accepted by every OEM shell build.
     */
    val selfComponent: String = "${appContext.packageName}/${appContext.packageName}.HomeActivity"

    /** Counts from the last [loadRawApps], for the diagnostics screen. */
    data class Summary(val tvApps: Int, val otherApps: Int, val merged: Int)

    /**
     * Enumerates launchable apps. Television entries win over plain launcher entries
     * for the same package, and LeanLauncher never lists itself.
     *
     * Blocking: call from a background thread.
     */
    fun loadRawApps(): Pair<List<RawApp>, Summary> {
        val tvApps = query(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), isTvApp = true)
        val otherApps = query(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), isTvApp = false)
        val merged = CatalogBuilder.merge(tvApps, otherApps)
            .filterNot { it.packageName == appContext.packageName }
        return merged to Summary(tvApps.size, otherApps.size, merged.size)
    }

    /** Every activity claiming to be a home screen. Used by diagnostics only. */
    fun homeCandidates(): List<String> = resolveInfos(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
    ).map { "${it.activityInfo.packageName}/${it.activityInfo.name}" }

    /**
     * Builds the best available launch intent, falling back through the leanback
     * entry point, the ordinary launcher entry point, then the exact component.
     */
    fun launchIntentFor(entry: AppEntry): Intent? {
        packageManager.getLeanbackLaunchIntentForPackage(entry.packageName)?.let { return newTask(it) }
        packageManager.getLaunchIntentForPackage(entry.packageName)?.let { return newTask(it) }
        return newTask(Intent(Intent.ACTION_MAIN).setComponent(entry.component))
    }

    /** Opens the system "app info" page so the user can uninstall or enable an app. */
    fun appInfoIntent(packageName: String): Intent? =
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun query(intent: Intent, isTvApp: Boolean): List<RawApp> =
        resolveInfos(intent).mapNotNull { info ->
            val activityInfo = info.activityInfo ?: return@mapNotNull null
            if (!isLaunchable(activityInfo.applicationInfo)) return@mapNotNull null
            RawApp(
                packageName = activityInfo.packageName,
                activityName = activityInfo.name,
                label = labelOf(info) ?: activityInfo.packageName,
                isTvApp = isTvApp,
                isSystemApp = activityInfo.applicationInfo?.let { appInfo ->
                    val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                    appInfo.flags and systemFlags != 0
                } ?: false,
            )
        }

    /** Disabled apps cannot be launched, so they are left out of the grid. */
    private fun isLaunchable(applicationInfo: ApplicationInfo?): Boolean =
        applicationInfo == null || applicationInfo.enabled

    /**
     * Enumerates launcher activities.
     *
     * Flags are deliberately 0. MATCH_DEFAULT_ONLY would additionally require
     * CATEGORY_DEFAULT on the filter, which plenty of television activities do not
     * declare, and those apps would silently vanish from the grid.
     */
    @Suppress("DEPRECATION")
    private fun resolveInfos(intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            packageManager.queryIntentActivities(intent, 0)
        }

    private fun labelOf(info: ResolveInfo): String? = try {
        info.loadLabel(packageManager)?.toString()
    } catch (_: Exception) {
        null
    }

    private fun newTask(intent: Intent): Intent =
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}
