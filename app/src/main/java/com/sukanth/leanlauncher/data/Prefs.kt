package com.sukanth.leanlauncher.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Single small preferences file: favorites order, hidden packages, and wallpaper options.
 *
 * Order is stored as a newline-separated string because [SharedPreferences.getStringSet]
 * makes no ordering guarantee, and a stable order is the whole point of favorites.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var favoriteOrder: List<String>
        get() = prefs.getString(KEY_FAVORITES, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        set(value) {
            prefs.edit().putString(KEY_FAVORITES, value.joinToString(SEPARATOR)).apply()
        }

    var hiddenPackages: Set<String>
        get() = prefs.getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
        set(value) {
            prefs.edit().putStringSet(KEY_HIDDEN, value).apply()
        }

    fun isFavorite(key: String): Boolean = key in favoriteOrder

    fun addFavorite(key: String) {
        val current = favoriteOrder
        if (key !in current) favoriteOrder = current + key
    }

    fun removeFavorite(key: String) {
        favoriteOrder = favoriteOrder.filterNot { it == key }
    }

    /** Moves [key] to [targetIndex], clamped into range. Used by long-press reordering. */
    fun moveFavorite(key: String, targetIndex: Int) {
        val current = favoriteOrder.toMutableList()
        val from = current.indexOf(key)
        if (from < 0) return
        current.removeAt(from)
        current.add(targetIndex.coerceIn(0, current.size), key)
        favoriteOrder = current
    }

    fun isHidden(packageName: String): Boolean = packageName in hiddenPackages

    fun toggleHidden(packageName: String) {
        val next = hiddenPackages.toMutableSet()
        if (!next.remove(packageName)) next.add(packageName)
        hiddenPackages = next
    }

    /** The "not the home app yet" hint is shown once, then it only gets in the way. */
    var homeHintShown: Boolean
        get() = prefs.getBoolean(KEY_HOME_HINT, false)
        set(value) = prefs.edit().putBoolean(KEY_HOME_HINT, value).apply()

    /**
     * Records a launch that arrived through `MAIN + HOME`.
     *
     * This is the only fully trustworthy proof that the HOME key reaches LeanLauncher:
     * a role grant or a shell command can both report success without changing where HOME
     * actually goes, but this counter only moves when the key press really lands here.
     */
    fun recordHomePress() {
        prefs.edit()
            .putLong(KEY_LAST_HOME_PRESS, System.currentTimeMillis())
            .putInt(KEY_HOME_PRESS_COUNT, homePressCount + 1)
            .apply()
    }

    val lastHomePressAt: Long get() = prefs.getLong(KEY_LAST_HOME_PRESS, 0L)

    val homePressCount: Int get() = prefs.getInt(KEY_HOME_PRESS_COUNT, 0)

    var wallpaperEnabled: Boolean
        get() = prefs.getBoolean(KEY_WALLPAPER_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_WALLPAPER_ENABLED, value).apply()

    var wallpaperSource: WallpaperSource
        get() = WallpaperSource.fromStored(prefs.getString(KEY_WALLPAPER_SOURCE, null))
        set(value) = prefs.edit().putString(KEY_WALLPAPER_SOURCE, value.name).apply()

    /** Document-picker URIs, read in place. Order is the slideshow order. */
    var localWallpaperUris: List<String>
        get() = prefs.getString(KEY_WALLPAPER_LOCAL_URIS, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        set(value) {
            prefs.edit().putString(KEY_WALLPAPER_LOCAL_URIS, value.joinToString(SEPARATOR)).apply()
        }

    /** Page, feed or direct-image address used by [WallpaperSource.WEB]. */
    var wallpaperWebUrl: String?
        get() = prefs.getString(KEY_WALLPAPER_WEB_URL, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_WALLPAPER_WEB_URL, value).apply()

    var wallpaperIntervalMinutes: Int
        get() = prefs.getInt(KEY_WALLPAPER_INTERVAL, DEFAULT_WALLPAPER_INTERVAL_MINUTES)
            .takeIf { it in WALLPAPER_INTERVALS_MINUTES }
            ?: DEFAULT_WALLPAPER_INTERVAL_MINUTES
        set(value) = prefs.edit().putInt(KEY_WALLPAPER_INTERVAL, value).apply()

    var lastWallpaperChangeAt: Long
        get() = prefs.getLong(KEY_LAST_WALLPAPER_CHANGE, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_WALLPAPER_CHANGE, value).apply()

    /**
     * The stock `package/activity` last observed owning HOME while LeanLauncher was not
     * the default. Kept so the restore commands can hand HOME back to the exact app even
     * after its package has been disabled and no longer appears in candidate queries.
     */
    var lastStockHomeComponent: String?
        get() = prefs.getString(KEY_STOCK_HOME_COMPONENT, null)
        set(value) = prefs.edit().putString(KEY_STOCK_HOME_COMPONENT, value).apply()

    /**
     * Stock launcher packages this app has seen installed. Kept separately from the live
     * probe so the restore script can re-enable them even if a disabled package stops
     * appearing in HOME candidate queries.
     */
    var stockLauncherPackages: Set<String>
        get() = prefs.getStringSet(KEY_STOCK_LAUNCHERS, emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_STOCK_LAUNCHERS, value).apply()

    /**
     * The one-time explanation before the app connects to the TV's own ADB service.
     * Once accepted, the one-press buttons do not ask again.
     */
    var adbConsentAccepted: Boolean
        get() = prefs.getBoolean(KEY_ADB_CONSENT, false)
        set(value) = prefs.edit().putBoolean(KEY_ADB_CONSENT, value).apply()

    /** Makes the next visible home screen fetch a fresh image, even if the timer is not due. */
    fun requestNextWallpaper() {
        prefs.edit().putLong(KEY_LAST_WALLPAPER_CHANGE, 0L).apply()
    }

    companion object {
        val WALLPAPER_INTERVALS_MINUTES = listOf(15, 30, 60, 360, 1440)
        const val DEFAULT_WALLPAPER_INTERVAL_MINUTES = 60

        private const val FILE = "leanlauncher"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_LAST_HOME_PRESS = "last_home_press"
        private const val KEY_HOME_PRESS_COUNT = "home_press_count"
        private const val KEY_HOME_HINT = "home_hint_shown"
        private const val KEY_WALLPAPER_ENABLED = "wallpaper_enabled"
        private const val KEY_WALLPAPER_SOURCE = "wallpaper_source"
        private const val KEY_WALLPAPER_LOCAL_URIS = "wallpaper_local_uris"
        private const val KEY_WALLPAPER_WEB_URL = "wallpaper_web_url"
        private const val KEY_WALLPAPER_INTERVAL = "wallpaper_interval_minutes"
        private const val KEY_LAST_WALLPAPER_CHANGE = "last_wallpaper_change"
        private const val KEY_STOCK_HOME_COMPONENT = "stock_home_component"
        private const val KEY_STOCK_LAUNCHERS = "stock_launchers"
        private const val KEY_ADB_CONSENT = "adb_consent_accepted"
        private const val SEPARATOR = "\n"
    }
}