package com.sukanth.leanlauncher.setup

/**
 * Shell commands used by the optional on-device ADB client.
 *
 * These are the machine form of the scripts in [AdbInstructions]: no `adb` prefix, no
 * comments, and only the mutations the app is allowed to request after the user approves
 * debugging on the TV. The app never exposes an arbitrary shell.
 */
object AdbShellCommands {

    /** `--brief` keeps the output to one component line, which is easy to verify. */
    const val RESOLVE_HOME =
        "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"

    /**
     * Disables the detected stock launchers, then claims HOME for LeanLauncher.
     *
     * The caller must make sure LeanLauncher's own HOME activity is enabled first. Some TV
     * builds refuse to set a disabled component as the default home, and shell `pm enable`
     * of a component is restricted on those same builds.
     */
    fun disableStockLauncher(
        ourComponent: String,
        rivalPackages: List<String>,
    ): List<String> = buildList {
        rivalPackages.distinct()
            .filter { it.isNotBlank() }
            .forEach { add("pm disable-user --user 0 $it") }
        add("cmd package set-home-activity $ourComponent")
    }

    /**
     * Re-enables the remembered stock launchers and, when the original component is known,
     * gives HOME back to it. Nothing is uninstalled.
     */
    fun restoreStockLauncher(
        stockPackages: List<String>,
        stockHomeComponent: String?,
    ): List<String> = buildList {
        stockPackages.distinct()
            .filter { it.isNotBlank() }
            .forEach { add("pm enable $it") }
        if (stockHomeComponent != null) {
            add("cmd package set-home-activity $stockHomeComponent")
        }
    }

    private val COMPONENT = Regex("""([A-Za-z][A-Za-z0-9_.]*)/([A-Za-z0-9_.$]+)""")

    /**
     * Reads the package from `cmd package resolve-activity --brief` output.
     *
     * Tolerates the longer, non-brief form too: it only matches real `package/activity`
     * pairs and ignores diagnostics such as `priority=` or error lines.
     */
    fun resolvedPackage(output: String): String? = output.lineSequence()
        .mapNotNull { line ->
            if (line.startsWith("Error", ignoreCase = true)) return@mapNotNull null
            COMPONENT.find(line)?.groupValues?.get(1)
        }
        .lastOrNull()
}
