package com.sukanth.leanlauncher.setup

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * A read-only snapshot of how this device currently resolves "home".
 *
 * Every field is observed, never assumed. The launcher reports these facts instead of
 * claiming success, because Android TV firmware frequently ignores the standard
 * default-home requests.
 */
data class HomeState(
    val ourPackage: String,
    val selfHomeEnabled: Boolean,
    val roleApiPresent: Boolean,
    val roleAvailable: Boolean,
    val roleHeld: Boolean,
    val defaultHome: ComponentName?,
    val candidates: List<String>,
    val homeSettingsResolvable: Boolean,
) {
    val isLeanLauncherDefault: Boolean
        get() = defaultHome?.packageName == ourPackage

    /** Single candidate means the system has nothing else to choose, so this is settled. */
    val isSettled: Boolean get() = candidates.size <= 1

    /**
     * Ground truth is where `MAIN + HOME` actually resolves, nothing else.
     *
     * The role flag must not be required here: a swap applied over ADB never grants the
     * role, so demanding it would report "not default" on a device that really is.
     */
    val isVerifiedDefault: Boolean
        get() = isLeanLauncherDefault

    /** Another app currently owns HOME. */
    val blockingPackage: String?
        get() = defaultHome?.packageName?.takeIf { it != ourPackage }
}

/** Reads the real home state. Safe to call from the main thread; performs no IPC beyond PM queries. */
class HomeStateProbe(context: Context) {

    private val appContext = context.applicationContext
    private val packageManager: PackageManager get() = appContext.packageManager

    private val ourPackage: String get() = appContext.packageName

    fun probe(): HomeState {
        val roleApiPresent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val roleAvailable: Boolean
        val roleHeld: Boolean
        if (roleApiPresent) {
            val roleManager = appContext.getSystemService(RoleManager::class.java)
            roleAvailable = roleManager?.let {
                runCatching { it.isRoleAvailable(RoleManager.ROLE_HOME) }.getOrDefault(false)
            } ?: false
            roleHeld = roleManager?.let {
                runCatching { it.isRoleHeld(RoleManager.ROLE_HOME) }.getOrDefault(false)
            } ?: false
        } else {
            roleAvailable = false
            roleHeld = false
        }

        val defaultHome = resolveCurrentHome()

        return HomeState(
            ourPackage = ourPackage,
            selfHomeEnabled = isSelfHomeEnabled(),
            roleApiPresent = roleApiPresent,
            roleAvailable = roleAvailable,
            roleHeld = roleHeld,
            defaultHome = defaultHome,
            candidates = homeCandidates(),
            homeSettingsResolvable = isResolvable(Intent(Settings.ACTION_HOME_SETTINGS)),
        )
    }

    /**
     * Resolves `MAIN + HOME` exactly the way a HOME press does, so this reports what the
     * device would really open.
     *
     * The authoritative `getHomeActivities` list is hidden API, so the candidate list is
     * reported separately and [HomeState.isVerifiedDefault] refuses to confirm when more
     * than one app still claims HOME.
     */
    fun resolveCurrentHome(): ComponentName? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val flags = PackageManager.MATCH_DEFAULT_ONLY
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, flags)
        }
        val info = resolved?.activityInfo ?: return null
        return ComponentName(info.packageName, info.name)
    }

    private fun homeCandidates(): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val flags = PackageManager.MATCH_DEFAULT_ONLY
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, flags)
        }
        return infos.mapNotNull { info ->
            val activityInfo = info.activityInfo ?: return@mapNotNull null
            "${activityInfo.packageName}/${activityInfo.name.substringAfterLast('.')}"
        }.sorted()
    }

    /**
     * Full HOME components of every candidate, including ones currently disabled.
     *
     * Used only to write an exact restore command after a stock launcher has been
     * disabled: a disabled package still has its manifest activity, but the normal
     * candidate query hides it. This is not part of [HomeState.candidates], so the
     * diagnostics keep describing only what can really receive HOME right now.
     */
    fun homeComponentsIncludingDisabled(): Map<String, String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, flags)
        }
        return infos.mapNotNull { info ->
            val activityInfo = info.activityInfo ?: return@mapNotNull null
            activityInfo.packageName to "${activityInfo.packageName}/${activityInfo.name}"
        }.toMap()
    }

    private fun isResolvable(intent: Intent): Boolean =
        packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null

    /** Fully qualified home component of this app. */
    fun selfHomeComponent(): ComponentName =
        ComponentName(appContext.packageName, "${appContext.packageName}.HomeActivity")

    /**
     * Whether our own HOME activity is currently switched on.
     *
     * Disabling it removes LeanLauncher from the HOME candidates, which is the only
     * reversible way an app can hand the HOME key back to the system. Android forbids
     * doing this to any other package.
     */
    fun isSelfHomeEnabled(): Boolean = when (
        packageManager.getComponentEnabledSetting(selfHomeComponent())
    ) {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
        -> false

        else -> true
    }

    /** True when this app is one of the candidates the system will offer. */
    fun selfIsCandidate(): Boolean = homeCandidates().any { it.startsWith(ourPackage) }

    /**
     * Stock launchers installed on this device that can take HOME away from LeanLauncher.
     *
     * Read-only: this probe never installs, enables or disables anything. The optional
     * on-device ADB path applies changes separately, only after the user authorizes it.
     * Works off the manifest's `<queries>` visibility, which is exactly what lets a
     * normal app observe other launchers at all.
     *
     * @param candidates already-read HOME candidates. A caller holding a [HomeState] passes
     *   [HomeState.candidates] so the package manager is not asked the same query twice.
     */
    fun homeRivals(candidates: List<String> = homeCandidates()): List<HomeRival> =
        HomeRivalCatalog.detect(candidates, ::isPackageInstalled)

    private fun isPackageInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        true
    }.getOrDefault(false)
}