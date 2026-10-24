package com.sukanth.leanlauncher.setup

/**
 * Path B: the commands that actually write the default-home setting on firmware that
 * ignores the in-app request.
 *
 * This object builds the copyable fallback for a computer. The optional one-press path
 * runs the same fixed commands through the TV's own ADB service after the user authorizes
 * debugging; it never exposes an arbitrary shell. Keeping this text means a TV that
 * blocks loopback ADB still has a supported route.
 */
object AdbInstructions {

    /**
     * @param ourComponent `package/.Activity` form accepted by `set-home-activity`.
     * @param stockPackage the app currently resolving HOME, or null if none was detected.
     */
    fun setupCommands(ourComponent: String, stockPackage: String?): List<String> = buildList {
        add("# 1. On the TV: Settings > Device Preferences > About > Build,")
        add("#    press Build 7 times, then enable Network debugging.")
        add("# 2. On this computer, with the TV and computer on the same network:")
        add("adb connect <TV-IP>:5555")
        add("")
        add("# 3. Assign HOME to leanlauncherTV:")
        add("adb shell cmd package set-home-activity $ourComponent")
        add("")
        add("# 4. Check which app HOME now resolves to:")
        add("adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME")
        if (stockPackage != null) {
            add("")
            add("# 5. Optional: only if the TV lets you disable the stock launcher.")
            add("#    Some Google TV builds protect it; this command may be rejected.")
            add("#    It is reversible and does not uninstall the app.")
            add("adb shell pm disable-user --user 0 $stockPackage")
            add("adb shell cmd package set-home-activity $ourComponent")
        } else {
            add("")
            add("# 5. If step 4 still reports a different launcher, run step 5 from")
            add("#    https://kotl.in/tv-home with that package name.")
        }
        add("")
        add("# Verify: press HOME on the remote. leanlauncherTV should open.")
    }

    /**
     * One ready-to-paste script that stops the takeover: disables every installed rival
     * launcher, assigns HOME to LeanLauncher, then verifies which app HOME resolves to.
     * Disable is user-scoped and reversible; nothing is uninstalled.
     *
     * The optional on-device ADB client runs the equivalent commands without the `adb`
     * prefix after the user allows debugging. This text is for a computer, which is the
     * fallback when the TV blocks loopback ADB.
     */
    fun overtakeFixScript(ourComponent: String, rivals: List<HomeRival>): List<String> = buildList {
        add("# 1. On the TV: Settings > Device Preferences > About > Build,")
        add("#    press Build 7 times, then enable Network debugging.")
        add("# 2. On this computer, with the TV and computer on the same network:")
        add("adb connect <TV-IP>:5555")
        add("")
        add("# 3. Disable every launcher fighting over the HOME key:")
        addAll(HomeRivalCatalog.disableCommands(rivals.map { it.packageName }))
        add("")
        add("# 4. Assign HOME to leanlauncherTV:")
        add("adb shell cmd package set-home-activity $ourComponent")
        add("")
        add("# 5. Check which app HOME now resolves to. It must be leanlauncherTV:")
        add("adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME")
        add("")
        add("# 6. Unplug the TV for a minute (standby does not reboot these TVs),")
        add("#    then press HOME on the remote.")
    }

    /**
     * Re-enables every rival launcher disabled by [overtakeFixScript] and, when the
     * original stock component is known, gives HOME back to it as well.
     *
     * @param stockHomeComponent full `package/activity` of the stock launcher observed
     *   holding HOME before the swap. Null means the TV's own Default apps / Home app
     *   screen has to make that choice after the packages are enabled again.
     */
    fun restoreRivalsScript(
        rivals: List<HomeRival>,
        stockHomeComponent: String? = null,
    ): List<String> = restoreStockLauncherScript(
        stockPackages = rivals.map { it.packageName },
        stockHomeComponent = stockHomeComponent,
    )

    /**
     * Same restore script, but driven by package names instead of live rivals.
     *
     * The names are deliberately allowed to outlive detection: a disabled launcher can
     * disappear from candidate queries, and the restore button still has to re-enable it.
     */
    fun restoreStockLauncherScript(
        stockPackages: List<String>,
        stockHomeComponent: String? = null,
    ): List<String> = buildList {
        add("# 1. On this computer, with the TV and computer on the same network:")
        add("adb connect <TV-IP>:5555")
        add("")
        if (stockPackages.isEmpty()) {
            add("# 2. No known stock launcher is installed according to this app.")
            add("#    Re-enable it in Settings > Apps > Show system apps, then choose")
            add("#    it under Settings > Apps > Default apps > Home app.")
        } else {
            add("# 2. Put the other launchers back. Nothing is installed or erased:")
            addAll(HomeRivalCatalog.enableCommands(stockPackages.distinct()))
            add("")
            if (stockHomeComponent != null) {
                add("# 3. Give HOME back to the stock launcher:")
                add("adb shell cmd package set-home-activity $stockHomeComponent")
                add("")
                add("# 4. Verify which app HOME resolves to:")
                add("adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME")
                add("")
                add("# 5. If HOME still opens leanlauncherTV, choose the stock launcher")
                add("#    under Settings > Apps > Default apps > Home app on the TV.")
            } else {
                add("# 3. Then choose the stock launcher under")
                add("#    Settings > Apps > Default apps > Home app on the TV.")
            }
        }
    }

    /**
     * @param stockComponent full `package/activity` of the stock launcher. A bare package
     *   name is still accepted for callers that have no component, but current Android
     *   builds expect a component for `set-home-activity`.
     */
    fun restoreCommands(stockComponent: String?): List<String> = buildList {
        add("# Put the original home app back")
        if (stockComponent != null) {
            add("adb shell pm enable ${stockComponent.substringBefore('/')}")
            add("adb shell cmd package set-home-activity $stockComponent")
        } else {
            add("# Re-enable the stock launcher in Settings > Apps > Show system apps,")
            add("# then choose it under Default apps / Home app.")
        }
    }

    /** The commands worth trying first, for a device that is not yet on ADB. */
    fun manualFallbackNotes(): List<String> = listOf(
        "Some Android TV builds simply do not allow changing the home app. If the buttons above do nothing, that is the firmware, not this app.",
        "On some TVs, Google TV is a protected system app and its Disable control or ADB disable command is blocked. In that case, use the TV's Default apps / Home app setting if available, or keep the stock launcher enabled.",
        "A system update can re-enable a disabled stock launcher. If HOME returns to Google TV after an update, run the one-press switch or the fix script again.",
        "Without debugging access, leanlauncherTV cannot change another app's enabled state. The one-press switch asks this TV's own ADB service for that access after you allow it; if the TV blocks the connection, use the copied commands on a computer.",
    )
}
