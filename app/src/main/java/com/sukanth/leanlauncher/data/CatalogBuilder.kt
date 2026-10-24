package com.sukanth.leanlauncher.data

/**
 * Plain app record with no Android types, so catalog rules stay unit-testable.
 *
 * @param isTvApp true when the activity was resolved through [android.content.Intent.CATEGORY_LEANBACK_LAUNCHER].
 */
data class RawApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val isTvApp: Boolean,
    /** True for factory-installed apps, including updates to preloaded system apps. */
    val isSystemApp: Boolean = false,
) {
    /**
     * `package/activity`, used as the icon-cache key and compared for every tile in every
     * diff.
     *
     * Stored rather than computed: as a getter this built a new string on every access,
     * which meant a throwaway string per bind, per diff comparison and per `getItemId`.
     */
    val key: String = "$packageName/$activityName"
}

/**
 * Merges the two [android.content.pm.ResolveInfo] queries into one list.
 *
 * A package can answer both queries. The LEANBACK activity wins because it is the
 * entry point the vendor intends for a television, and its label is the TV-branded one.
 */
object CatalogBuilder {

    fun merge(tvApps: List<RawApp>, otherApps: List<RawApp>): List<RawApp> {
        val byPackage = LinkedHashMap<String, RawApp>(tvApps.size + otherApps.size)
        // System apps without a television entry point are firmware clutter. System apps
        // that do declare LEANBACK are the TV's own apps (Live TV, media player, store)
        // and must stay reachable.
        for (app in otherApps) if (!app.isSystemApp) byPackage[app.packageName] = app
        for (app in tvApps) byPackage[app.packageName] = app
        return byPackage.values.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.label },
        )
    }

    /**
     * Applies the user's saved ordering, drops favorites whose app is gone, and
     * appends everything else alphabetically.
     *
     * Home tab only: the Apps and Search tabs are built from the unfiltered catalog.
     *
     * @param available every app currently installed and launchable.
     * @param favoriteKeys saved `package/activity` keys, in user order.
     * @param hiddenPackages packages the user removed from the Home grid.
     */
    fun applyFavorites(
        available: List<RawApp>,
        favoriteKeys: List<String>,
        hiddenPackages: Set<String>,
    ): Pair<List<RawApp>, List<RawApp>> {
        val visible = available.filterNot { it.packageName in hiddenPackages }
        val byKey = visible.associateBy { it.key }
        val favorites = LinkedHashMap<String, RawApp>()
        for (key in favoriteKeys) {
            byKey[key]?.let { favorites[key] = it }
        }
        val rest = visible.filterNot { it.key in favorites }
        return favorites.values.toList() to rest
    }
}
