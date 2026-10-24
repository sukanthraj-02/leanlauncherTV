package com.sukanth.leanlauncher

import com.sukanth.leanlauncher.data.CatalogBuilder
import com.sukanth.leanlauncher.data.RawApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogBuilderTest {

    private fun app(
        pkg: String,
        label: String = pkg,
        activity: String = "$pkg.Main",
        isTvApp: Boolean = true,
        isSystemApp: Boolean = false,
    ) = RawApp(pkg, activity, label, isTvApp, isSystemApp)

    @Test
    fun `leanback activity wins over plain launcher activity for same package`() {
        val tv = listOf(app("com.netflix", activity = "com.netflix.ui.TvActivity"))
        val other = listOf(app("com.netflix", activity = "com.netflix.ui.PhoneActivity", isTvApp = false))

        val merged = CatalogBuilder.merge(tv, other)

        assertEquals(1, merged.size)
        assertEquals("com.netflix.ui.TvActivity", merged.single().activityName)
    }

    @Test
    fun `plain launcher entry is kept when a package has no leanback activity`() {
        val merged = CatalogBuilder.merge(
            tvApps = emptyList(),
            otherApps = listOf(app("com.example.tool", isTvApp = false)),
        )

        assertEquals(1, merged.size)
        assertEquals("com.example.tool", merged.single().packageName)
        assertEquals(false, merged.single().isTvApp)
    }

    @Test
    fun `system app with a leanback entry stays in the catalog`() {
        val merged = CatalogBuilder.merge(
            tvApps = listOf(app("com.mediatek.wwtv.tvcenter", "Live TV", isSystemApp = true)),
            otherApps = emptyList(),
        )

        assertEquals(listOf("com.mediatek.wwtv.tvcenter"), merged.map { it.packageName })
    }

    @Test
    fun `system app with only a plain launcher entry is filtered out`() {
        val merged = CatalogBuilder.merge(
            tvApps = emptyList(),
            otherApps = listOf(app("com.android.settings", isTvApp = false, isSystemApp = true)),
        )

        assertTrue(merged.isEmpty())
    }

    @Test
    fun `results are sorted case insensitively by label`() {
        val merged = CatalogBuilder.merge(
            tvApps = listOf(app("c", "zeta"), app("a", "Alpha"), app("b", "beta")),
            otherApps = emptyList(),
        )

        assertEquals(listOf("Alpha", "beta", "zeta"), merged.map { it.label })
    }

    @Test
    fun `app key is built once and reused`() {
        val app = app("com.netflix", activity = "com.netflix.ui.TvActivity")

        assertEquals("com.netflix/com.netflix.ui.TvActivity", app.key)
        // The key is a cache key and is compared for every tile in every diff, so it must be
        // the same instance each time rather than a fresh string per access.
        assertSame(app.key, app.key)
    }

    @Test
    fun `favorites come first in the saved order`() {
        val available = listOf(app("a", "Alpha"), app("b", "Beta"), app("c", "Gamma"))

        val (favorites, rest) = CatalogBuilder.applyFavorites(
            available = available,
            favoriteKeys = listOf("c/c.Main", "a/a.Main"),
            hiddenPackages = emptySet(),
        )

        assertEquals(listOf("c", "a"), favorites.map { it.packageName })
        assertEquals(listOf("b"), rest.map { it.packageName })
    }

    @Test
    fun `favorites for uninstalled apps are dropped`() {
        val available = listOf(app("a", "Alpha"))

        val (favorites, rest) = CatalogBuilder.applyFavorites(
            available = available,
            favoriteKeys = listOf("gone/Missing", "a/a.Main"),
            hiddenPackages = emptySet(),
        )

        assertEquals(listOf("a"), favorites.map { it.packageName })
        assertTrue(rest.isEmpty())
    }

    @Test
    fun `hidden packages are removed from the home lists`() {
        val available = listOf(app("a", "Alpha"), app("b", "Beta"))

        val (favorites, rest) = CatalogBuilder.applyFavorites(
            available = available,
            favoriteKeys = listOf("a/a.Main"),
            hiddenPackages = setOf("a"),
        )

        assertTrue(favorites.isEmpty())
        assertEquals(listOf("b"), rest.map { it.packageName })
    }

    @Test
    fun `favorite key must match package and activity to survive`() {
        val available = listOf(app("a", "Alpha", activity = "com.a.Tv"))

        val (favorites, _) = CatalogBuilder.applyFavorites(
            available = available,
            favoriteKeys = listOf("a/Other"),
            hiddenPackages = emptySet(),
        )

        assertTrue(favorites.isEmpty())
    }
}
