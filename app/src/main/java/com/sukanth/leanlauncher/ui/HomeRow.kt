package com.sukanth.leanlauncher.ui

import com.sukanth.leanlauncher.data.AppEntry

/** One row in the launcher grid: either a section heading or an app tile. */
sealed interface HomeRow {
    data class Section(val title: String) : HomeRow
    data class Tile(val entry: AppEntry) : HomeRow
}

/**
 * Turns the installed app list into the rows each tab shows.
 *
 * Kept free of Android types so the tab layouts can be unit tested directly.
 */
object HomeRowBuilder {

    /** Home tab: pinned apps first under their own heading, then everything else. */
    fun forHome(favorites: List<AppEntry>, rest: List<AppEntry>): List<HomeRow> = buildList {
        if (favorites.isNotEmpty()) {
            add(HomeRow.Section("Favourites"))
            favorites.forEach { add(HomeRow.Tile(it)) }
        }
        if (rest.isNotEmpty()) {
            if (favorites.isNotEmpty()) add(HomeRow.Section("All apps"))
            rest.forEach { add(HomeRow.Tile(it)) }
        }
    }

    /** Apps tab: every app grouped under an alphabetical heading. */
    fun forApps(apps: List<AppEntry>): List<HomeRow> = buildList {
        // Compared as a Char. Building a heading string for every app allocated one per
        // tile just to throw all but a handful away.
        var currentLetter: Char? = null
        for (entry in apps) {
            val letter = entry.label.firstOrNull()?.uppercaseChar()
            if (letter != currentLetter) {
                currentLetter = letter
                add(HomeRow.Section(letter?.toString().orEmpty()))
            }
            add(HomeRow.Tile(entry))
        }
    }

    /**
     * Search tab: label matches, case-insensitive.
     *
     * A blank query returns everything, so the tab is never an empty dead end.
     */
    fun search(query: String, apps: List<AppEntry>): List<AppEntry> {
        val needle = query.trim()
        if (needle.isEmpty()) return apps
        return apps.filter { it.label.contains(needle, ignoreCase = true) }
    }
}