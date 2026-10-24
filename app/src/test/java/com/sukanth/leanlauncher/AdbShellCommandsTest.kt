package com.sukanth.leanlauncher

import com.sukanth.leanlauncher.setup.AdbShellCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbShellCommandsTest {

    @Test
    fun `disable command list disables each rival then claims home`() {
        val commands = AdbShellCommands.disableStockLauncher(
            ourComponent = "com.sukanth.leanlauncher/com.sukanth.leanlauncher.HomeActivity",
            rivalPackages = listOf(
                "com.google.android.tvlauncher",
                "com.haier.launcher.haiermatrix",
                "com.google.android.tvlauncher",
            ),
        )

        assertEquals(
            listOf(
                "pm disable-user --user 0 com.google.android.tvlauncher",
                "pm disable-user --user 0 com.haier.launcher.haiermatrix",
                "cmd package set-home-activity " +
                    "com.sukanth.leanlauncher/com.sukanth.leanlauncher.HomeActivity",
            ),
            commands,
        )
        assertFalse(commands.any { it.startsWith("adb ") })
        assertFalse(commands.any { it.startsWith("#") })
    }

    @Test
    fun `disable command list still claims home when no rival is known`() {
        val commands = AdbShellCommands.disableStockLauncher(
            ourComponent = "com.sukanth.leanlauncher/.HomeActivity",
            rivalPackages = emptyList(),
        )

        assertEquals(
            listOf("cmd package set-home-activity com.sukanth.leanlauncher/.HomeActivity"),
            commands,
        )
    }

    @Test
    fun `restore command list enables remembered packages and restores home`() {
        val commands = AdbShellCommands.restoreStockLauncher(
            stockPackages = listOf(
                "com.google.android.tvlauncher",
                "com.haier.launcher.haiermatrix",
            ),
            stockHomeComponent =
                "com.google.android.tvlauncher/com.google.android.tvlauncher.MainActivity",
        )

        assertEquals(
            listOf(
                "pm enable com.google.android.tvlauncher",
                "pm enable com.haier.launcher.haiermatrix",
                "cmd package set-home-activity " +
                    "com.google.android.tvlauncher/com.google.android.tvlauncher.MainActivity",
            ),
            commands,
        )
    }

    @Test
    fun `restore without a known component only re-enables packages`() {
        val commands = AdbShellCommands.restoreStockLauncher(
            stockPackages = listOf("com.google.android.tvlauncher"),
            stockHomeComponent = null,
        )

        assertEquals(listOf("pm enable com.google.android.tvlauncher"), commands)
    }

    @Test
    fun `resolved package reads brief output`() {
        val output = "priority=0 preferredOrder=0 match=0x108000 isDefault=true\n" +
            "com.google.android.tvlauncher/.MainActivity\n"

        assertEquals("com.google.android.tvlauncher", AdbShellCommands.resolvedPackage(output))
    }

    @Test
    fun `resolved package ignores error lines and accepts full components`() {
        val output = "Error: Failed to set default home.\n" +
            "com.sukanth.leanlauncher/com.sukanth.leanlauncher.HomeActivity"

        assertEquals("com.sukanth.leanlauncher", AdbShellCommands.resolvedPackage(output))
    }

    @Test
    fun `resolved package is null when no component was printed`() {
        assertNull(AdbShellCommands.resolvedPackage(""))
        assertNull(AdbShellCommands.resolvedPackage("Error: no activity found"))
    }

    @Test
    fun `resolve command asks for the brief form`() {
        assertTrue(AdbShellCommands.RESOLVE_HOME.contains("--brief"))
        assertTrue(AdbShellCommands.RESOLVE_HOME.contains("android.intent.category.HOME"))
    }
}
