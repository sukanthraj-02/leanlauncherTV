package com.sukanth.leanlauncher

import com.sukanth.leanlauncher.setup.HomeRival
import com.sukanth.leanlauncher.setup.HomeRivalCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRivalCatalogTest {

    private fun installed(vararg packages: String): (String) -> Boolean = { it in packages }

    @Test
    fun `detects every installed known stock launcher`() {
        val rivals = HomeRivalCatalog.detect(
            homeCandidates = listOf(
                "com.google.android.tvlauncher/MainActivity",
                "com.sukanth.leanlauncher/HomeActivity",
            ),
            isInstalled = installed("com.google.android.tvlauncher", "com.sukanth.leanlauncher"),
        )

        assertEquals(1, rivals.size)
        assertEquals("com.google.android.tvlauncher", rivals.single().packageName)
        assertTrue(rivals.single().claimingHome)
    }

    @Test
    fun `installed but not claiming rivals are reported without claiming flag`() {
        val rivals = HomeRivalCatalog.detect(
            homeCandidates = listOf("com.sukanth.leanlauncher/HomeActivity"),
            isInstalled = installed(
                "com.google.android.tvlauncher",
                "com.google.android.tungsten.setupwraith",
            ),
        )

        assertEquals(2, rivals.size)
        assertTrue(rivals.all { !it.claimingHome })
    }

    @Test
    fun `catalog entries absent from the device are skipped`() {
        val rivals = HomeRivalCatalog.detect(
            homeCandidates = listOf("com.google.android.apps.tv.launcherx/HomeActivity"),
            isInstalled = installed("com.google.android.apps.tv.launcherx"),
        )

        // launcherx is installed; tvlauncher is in the catalog but absent on the device.
        assertEquals(listOf("com.google.android.apps.tv.launcherx"), rivals.map { it.packageName })
    }

    @Test
    fun `claiming rivals sort before non claiming ones`() {
        val rivals = HomeRivalCatalog.detect(
            homeCandidates = listOf("com.google.android.tungsten.setupwraith/WraithActivity"),
            isInstalled = installed(
                "com.google.android.tvlauncher",
                "com.google.android.tungsten.setupwraith",
            ),
        )

        assertEquals(
            listOf(
                "com.google.android.tungsten.setupwraith",
                "com.google.android.tvlauncher",
            ),
            rivals.map { it.packageName },
        )
    }

    @Test
    fun `unknown packages claiming HOME are never disable targets`() {
        // Catalog has no entry for a random vendor launcher, so nothing is offered.
        // FallbackHome is platform, never a catalog entry.
        val rivals = HomeRivalCatalog.detect(
            homeCandidates = listOf(
                "com.android.tv.settings/FallbackHome",
                "com.vendor.mystery/Home",
            ),
            isInstalled = installed("com.android.tv.settings", "com.vendor.mystery"),
        )

        assertTrue(rivals.isEmpty())
        assertEquals(
            emptyList<String>(),
            HomeRivalCatalog.disableCommands(emptyList()),
        )
    }

    @Test
    fun `disable and enable commands are user scoped and reversible`() {
        val packages = listOf(
            "com.google.android.tvlauncher",
            "com.google.android.tungsten.setupwraith",
        )

        assertEquals(
            listOf(
                "adb shell pm disable-user --user 0 com.google.android.tvlauncher",
                "adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith",
            ),
            HomeRivalCatalog.disableCommands(packages),
        )
        assertEquals(
            listOf(
                "adb shell pm enable com.google.android.tvlauncher",
                "adb shell pm enable com.google.android.tungsten.setupwraith",
            ),
            HomeRivalCatalog.enableCommands(packages),
        )
    }

    @Test
    fun `same package twice still yields one rival`() {
        val seen = HomeRivalCatalog.detect(
            homeCandidates = listOf(
                "com.google.android.tvlauncher/MainActivity",
                "com.google.android.tvlauncher/OtherActivity",
            ),
            isInstalled = installed("com.google.android.tvlauncher"),
        )

        assertEquals(1, seen.size)
    }

    @Test
    fun `HomeRival keeps its label readable`() {
        val rival = HomeRival("com.google.android.tvlauncher", "Google stock Android TV launcher", true)

        assertFalse(rival.label.isBlank())
        assertTrue(rival.claimingHome)
    }
}
