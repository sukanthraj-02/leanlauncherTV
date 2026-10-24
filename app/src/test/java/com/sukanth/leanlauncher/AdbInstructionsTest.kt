package com.sukanth.leanlauncher

import com.sukanth.leanlauncher.setup.AdbInstructions
import com.sukanth.leanlauncher.setup.HomeRival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbInstructionsTest {

    @Test
    fun `setup commands assign home to the given component`() {
        val commands = AdbInstructions.setupCommands(
            ourComponent = "com.sukanth.leanlauncher/.HomeActivity",
            stockPackage = "com.haier.launcher.haiermatrix",
        ).joinToString("\n")

        assertTrue(commands.contains("adb shell cmd package set-home-activity com.sukanth.leanlauncher/.HomeActivity"))
        assertTrue(commands.contains("adb connect <TV-IP>:5555"))
    }

    @Test
    fun `disable step is only offered for a detected stock package`() {
        val withStock = AdbInstructions.setupCommands("pkg/.HomeActivity", "com.oem.launcher")
            .joinToString("\n")
        assertTrue(withStock.contains("pm disable-user --user 0 com.oem.launcher"))

        val withoutStock = AdbInstructions.setupCommands("pkg/.HomeActivity", null)
            .joinToString("\n")
        assertFalse(withoutStock.contains("pm disable-user"))
    }

    @Test
    fun `instructions never claim to uninstall anything`() {
        val all = (
            AdbInstructions.setupCommands("pkg/.HomeActivity", "com.oem.launcher") +
                AdbInstructions.restoreCommands("com.oem.launcher") +
                AdbInstructions.manualFallbackNotes()
            ).joinToString("\n")

        assertFalse(all.contains("pm uninstall"))
        assertTrue(all.contains("reversible"))
    }

    @Test
    fun `restore commands re-enable the stock launcher`() {
        val commands = AdbInstructions.restoreCommands(
            "com.oem.launcher/com.oem.launcher.MainActivity",
        ).joinToString("\n")

        assertTrue(commands.contains("adb shell pm enable com.oem.launcher"))
        assertTrue(
            commands.contains(
                "set-home-activity com.oem.launcher/com.oem.launcher.MainActivity",
            ),
        )
    }

    @Test
    fun `restore falls back to manual steps when nothing was detected`() {
        val commands = AdbInstructions.restoreCommands(null).joinToString("\n")

        assertFalse(commands.contains("adb shell"))
    }

    @Test
    fun `overtake script disables each rival then assigns and verifies`() {
        val script = AdbInstructions.overtakeFixScript(
            ourComponent = "com.sukanth.leanlauncher/com.sukanth.leanlauncher.HomeActivity",
            rivals = listOf(
                HomeRival("com.google.android.tvlauncher", "Google stock Android TV launcher", true),
                HomeRival("com.google.android.tungsten.setupwraith", "Google TV setup/fallback hijacker", true),
            ),
        ).joinToString("\n")

        val disableTv = script.indexOf("pm disable-user --user 0 com.google.android.tvlauncher")
        val disableWraith = script.indexOf("pm disable-user --user 0 com.google.android.tungsten.setupwraith")
        val assign = script.indexOf(
            "set-home-activity com.sukanth.leanlauncher/com.sukanth.leanlauncher.HomeActivity",
        )
        val verify = script.indexOf("resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME")

        assertTrue(disableTv >= 0 && disableWraith >= 0 && assign >= 0 && verify >= 0)
        assertTrue(disableTv < assign && disableWraith < assign && assign < verify)
        assertFalse(script.contains("pm uninstall"))
    }

    @Test
    fun `restore rivals script re-enables exactly what was disabled`() {
        val commands = AdbInstructions.restoreRivalsScript(
            listOf(
                HomeRival("com.google.android.tvlauncher", "Google stock Android TV launcher", true),
            ),
        ).joinToString("\n")

        assertTrue(commands.contains("adb connect <TV-IP>:5555"))
        assertTrue(commands.contains("adb shell pm enable com.google.android.tvlauncher"))
        assertFalse(commands.contains("pm uninstall"))
        assertFalse(commands.contains("set-home-activity"))
        assertTrue(commands.contains("Settings > Apps > Default apps > Home app"))
    }

    @Test
    fun `restore script gives HOME back to the observed stock component`() {
        val script = AdbInstructions.restoreRivalsScript(
            rivals = listOf(
                HomeRival("com.google.android.tvlauncher", "Google stock Android TV launcher", false),
                HomeRival("com.haier.launcher.haiermatrix", "Haier TV+ launcher", false),
            ),
            stockHomeComponent =
                "com.google.android.tvlauncher/com.google.android.tvlauncher.MainActivity",
        ).joinToString("\n")

        val enable = script.indexOf("pm enable com.google.android.tvlauncher")
        val enableHaier = script.indexOf("pm enable com.haier.launcher.haiermatrix")
        val assign = script.indexOf(
            "set-home-activity " +
                "com.google.android.tvlauncher/com.google.android.tvlauncher.MainActivity",
        )
        val verify = script.indexOf(
            "resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME",
        )

        assertTrue(enable >= 0 && enableHaier >= 0 && assign >= 0 && verify >= 0)
        assertTrue(enable < assign && enableHaier < assign && assign < verify)
        assertFalse(script.contains("pm uninstall"))
    }

    @Test
    fun `restore script keeps remembered packages when detection turns up nothing`() {
        val script = AdbInstructions.restoreStockLauncherScript(
            stockPackages = listOf(
                "com.google.android.tvlauncher",
                "com.google.android.tvlauncher",
                "com.haier.launcher.haiermatrix",
            ),
        ).joinToString("\n")

        assertEquals(
            1,
            Regex("pm enable com\\.google\\.android\\.tvlauncher").findAll(script).count(),
        )
        assertTrue(script.contains("pm enable com.haier.launcher.haiermatrix"))
        assertFalse(script.contains("set-home-activity"))
    }
}