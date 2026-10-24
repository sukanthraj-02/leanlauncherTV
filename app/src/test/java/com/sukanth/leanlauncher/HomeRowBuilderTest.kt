package com.sukanth.leanlauncher

import com.sukanth.leanlauncher.data.AppEntry
import com.sukanth.leanlauncher.data.RawApp
import com.sukanth.leanlauncher.ui.HomeRow
import com.sukanth.leanlauncher.ui.HomeRowBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowBuilderTest {

    private fun entry(label: String, pkg: String = label.lowercase()) =
        AppEntry(RawApp(pkg, "$pkg.Main", label, isTvApp = true))

    private fun titles(rows: List<HomeRow>) = rows.filterIsInstance<HomeRow.Section>()
        .map { it.title }

    private fun tiles(rows: List<HomeRow>) = rows.filterIsInstance<HomeRow.Tile>()
        .map { it.entry.label }

    @Test
    fun `home tab puts favourites under their own heading`() {
        val rows = HomeRowBuilder.forHome(
            favorites = listOf(entry("Netflix"), entry("YouTube")),
            rest = listOf(entry("Spotify")),
        )

        assertEquals(listOf("Favourites", "All apps"), titles(rows))
        assertEquals(listOf("Netflix", "YouTube", "Spotify"), tiles(rows))
    }

    @Test
    fun `home tab omits headings when there is nothing to show`() {
        val rows = HomeRowBuilder.forHome(emptyList(), listOf(entry("Spotify")))

        assertTrue(titles(rows).isEmpty())
        assertEquals(listOf("Spotify"), tiles(rows))
    }

    @Test
    fun `home tab omits the all apps heading when favourites cover everything`() {
        val rows = HomeRowBuilder.forHome(listOf(entry("Netflix")), emptyList())

        assertEquals(listOf("Favourites"), titles(rows))
        assertEquals(listOf("Netflix"), tiles(rows))
    }

    @Test
    fun `apps tab inserts a heading when the letter changes`() {
        val rows = HomeRowBuilder.forApps(
            listOf(entry("Netflix"), entry("Prime Video"), entry("YouTube")),
        )

        assertEquals(listOf("N", "P", "Y"), titles(rows))
        assertEquals(listOf("Netflix", "Prime Video", "YouTube"), tiles(rows))
    }

    @Test
    fun `apps tab groups same letter apps under one heading`() {
        val rows = HomeRowBuilder.forApps(listOf(entry("Netflix"), entry("News")))

        assertEquals(listOf("N"), titles(rows))
        assertEquals(listOf("Netflix", "News"), tiles(rows))
    }

    @Test
    fun `search matches labels case insensitively`() {
        val apps = listOf(entry("Netflix"), entry("YouTube"), entry("Prime Video"))

        val result = HomeRowBuilder.search("net", apps)

        assertEquals(listOf("Netflix"), result.map { it.label })
    }

    @Test
    fun `search trims surrounding whitespace`() {
        val apps = listOf(entry("Netflix"), entry("YouTube"))

        assertEquals(listOf("YouTube"), HomeRowBuilder.search("  tube ", apps).map { it.label })
    }

    @Test
    fun `blank search returns everything so the tab is never a dead end`() {
        val apps = listOf(entry("Netflix"), entry("YouTube"))

        assertEquals(apps, HomeRowBuilder.search("", apps))
        assertEquals(apps, HomeRowBuilder.search("   ", apps))
    }

    @Test
    fun `search with no match returns empty`() {
        assertTrue(HomeRowBuilder.search("zzz", listOf(entry("Netflix"))).isEmpty())
    }
}