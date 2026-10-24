package com.sukanth.leanlauncher.setup

/**
 * A stock or fallback home app installed on the TV that can hold the HOME key away from
 * LeanLauncher.
 */
data class HomeRival(
    /** Package name, e.g. `com.google.android.tvlauncher`. */
    val packageName: String,
    /** Plain-words label shown on the setup screen. */
    val label: String,
    /** True when this app currently appears as a HOME candidate on the device. */
    val claimingHome: Boolean,
)

/**
 * Known stock launchers observed holding HOME on Android TV / Google TV builds.
 *
 * Conservative by design:
 * - Only these entries are ever offered as `pm disable-user` targets. Unknown packages
 *   that claim HOME are reported but never suggested for disabling: disabling the wrong
 *   platform component can leave the TV with no working home screen.
 * - `com.android.*` platform components (for example the TV settings FallbackHome) are
 *   never disable targets. FallbackHome is the safety net that opens when no other home
 *   remains, and it must stay enabled.
 */
object HomeRivalCatalog {

    private val KNOWN: Map<String, String> = linkedMapOf(
        "com.google.android.tvlauncher" to "Google stock Android TV launcher",
        "com.google.android.apps.tv.launcherx" to "Google TV launcher",
        "com.google.android.leanbacklauncher" to "Leanback launcher (older builds)",
        "com.google.android.tungsten.setupwraith" to "Google TV setup/fallback hijacker",
        "com.haier.launcher.haiermatrix" to "Haier TV+ launcher",
    )

    /**
     * @param homeCandidates entries from [HomeStateProbe], each `package/shortActivity`.
     * @param isInstalled whether the package is installed on the device.
     * @return known rivals, ones currently claiming HOME first, then the catalog order.
     */
    fun detect(homeCandidates: List<String>, isInstalled: (String) -> Boolean): List<HomeRival> {
        val claiming = homeCandidates.mapNotNull { it.substringBefore("/").takeIf(String::isNotEmpty) }.toSet()
        return KNOWN.mapNotNull { (packageName, label) ->
            if (!isInstalled(packageName)) return@mapNotNull null
            HomeRival(packageName, label, claimingHome = packageName in claiming)
        }.sortedWith(compareByDescending<HomeRival> { it.claimingHome })
    }

    /** Shell commands that disable the named packages for the current user. */
    fun disableCommands(packageNames: List<String>): List<String> =
        packageNames.map { "adb shell pm disable-user --user 0 $it" }

    /** Shell commands that restore the named packages. */
    fun enableCommands(packageNames: List<String>): List<String> =
        packageNames.map { "adb shell pm enable $it" }
}
