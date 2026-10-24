package com.sukanth.leanlauncher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sukanth.leanlauncher.data.AppEntry
import com.sukanth.leanlauncher.data.AppRepository
import com.sukanth.leanlauncher.data.CatalogBuilder
import com.sukanth.leanlauncher.data.IconCache
import com.sukanth.leanlauncher.data.IconLoader
import com.sukanth.leanlauncher.data.Prefs
import com.sukanth.leanlauncher.diag.KeyLog
import com.sukanth.leanlauncher.diag.RemoteKeys
import com.sukanth.leanlauncher.databinding.ActivityHomeBinding
import com.sukanth.leanlauncher.setup.HomeStateProbe
import com.sukanth.leanlauncher.ui.HomeRow
import com.sukanth.leanlauncher.ui.HomeRowAdapter
import com.sukanth.leanlauncher.ui.HomeRowBuilder
import com.sukanth.leanlauncher.wallpaper.WallpaperController
import java.util.concurrent.Executors

/**
 * The home screen.
 *
 * One activity, one RecyclerView, one adapter, one icon cache. The three tabs swap the
 * row list rather than holding separate views, so switching tabs allocates nothing and
 * there is only ever one view pool.
 *
 * Deliberately absent: content feeds, promoted rows, carousels, polling, and any
 * background component. Discovery runs on one worker thread only while visible. The
 * optional wallpaper is the only network feature, and it downloads only while this
 * activity is in front.
 */
class HomeActivity : Activity() {

    private enum class Tab { SEARCH, HOME, APPS }

    private lateinit var binding: ActivityHomeBinding
    private lateinit var repository: AppRepository
    private lateinit var prefs: Prefs
    private lateinit var probe: HomeStateProbe
    private lateinit var adapter: HomeRowAdapter
    private lateinit var iconLoader: IconLoader
    private lateinit var gridLayoutManager: GridLayoutManager
    private lateinit var wallpaper: WallpaperController

    private val iconCache = IconCache.get()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "app-scan").apply { isDaemon = true }
    }

    private var tab = Tab.HOME
    private var searchQuery = ""
    private var favorites: List<AppEntry> = emptyList()
    private var rest: List<AppEntry> = emptyList()
    private var allByLabel: List<AppEntry> = emptyList()

    private var menuEntry: AppEntry? = null
    private var scanning = false
    private var rescanQueued = false
    private var focusSet = false
    private var networkMonitoring = false

    /** Set when a long press consumed the gesture, so the release must not also click. */
    private var suppressNextCenter = false

    /** Counts from the last app scan, shared with diagnostics. */
    @Volatile
    private var scanSummary: AppRepository.Summary? = null

    private val hideNotice = Runnable { binding.notice.visibility = View.GONE }

    /** Refreshes the chip when the default route appears, disappears, or changes. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNetworkStatusLater()

        override fun onLost(network: Network) = refreshNetworkStatusLater()

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = refreshNetworkStatusLater()
    }

    /** Catches Wi-Fi being switched on or off while no network is connected. */
    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshNetworkStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = AppRepository(this)
        prefs = Prefs(this)
        probe = HomeStateProbe(this)
        iconLoader = IconLoader(this, iconCache)
        adapter = HomeRowAdapter(this, iconLoader, ::openApp, ::showMenu)
        wallpaper = WallpaperController(this, prefs, ::showWallpaper, ::hideWallpaper)

        gridLayoutManager = GridLayoutManager(this, spanCountFor())
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.isHeader(position)) gridLayoutManager.spanCount else 1
        }
        binding.appGrid.layoutManager = gridLayoutManager
        binding.appGrid.adapter = adapter
        // The grid always fills its parent, so skip the layout pass on every update.
        binding.appGrid.setHasFixedSize(true)
        // Item animations allocate and run per change; on a TV an instant swap reads as
        // faster. DiffUtil still keeps focus and avoids rebinding unchanged tiles.
        binding.appGrid.itemAnimator = null

        binding.tabSearch.setOnClickListener { selectTab(Tab.SEARCH) }
        binding.tabHome.setOnClickListener { selectTab(Tab.HOME) }
        binding.tabApps.setOnClickListener { selectTab(Tab.APPS) }

        binding.statusSource.setOnClickListener { openInputs() }
        binding.statusNetwork.setOnClickListener { openNetworkSettings() }
        binding.statusSettings.setOnClickListener { openDeviceSettings() }
        binding.statusSetup.setOnClickListener { openSetup() }

        binding.menuDismiss.setOnClickListener { hideMenu() }
        binding.menuPin.setOnClickListener { withEntry { toggleFavorite(it); hideMenu() } }
        binding.menuMoveLeft.setOnClickListener { withEntry { moveFavorite(it, -1); hideMenu() } }
        binding.menuMoveRight.setOnClickListener { withEntry { moveFavorite(it, +1); hideMenu() } }
        binding.menuAppInfo.setOnClickListener { withEntry { openAppInfo(it); hideMenu() } }
        binding.menuHide.setOnClickListener { withEntry { hideApp(it); hideMenu() } }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                applyTab()
            }
        })

        recordHomePressIfHomeIntent(intent)
        selectTab(Tab.HOME)
        // On a cold start the remote should land on the navigation controls, not deep
        // inside the grid. Returning from another app keeps the grid focus instead.
        if (!focusSet) binding.tabSearch.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        binding.statusClock.start()
        startNetworkMonitoring()
        refreshNetworkStatus()
        wallpaper.start()
        recordHomePressIfHomeIntent(intent)
        reloadApps()
        // The hint is worth showing once. Probing resolves HOME and queries every HOME
        // candidate, so it is skipped entirely on the common path: every return from an
        // app brings this activity back to the foreground.
        if (!prefs.homeHintShown && !probe.probe().isVerifiedDefault) {
            prefs.homeHintShown = true
            showNotice(getString(R.string.notice_not_home))
        }
    }

    override fun onStop() {
        wallpaper.stop()
        stopNetworkMonitoring()
        binding.statusClock.stop()
        binding.notice.removeCallbacks(hideNotice)
        super.onStop()
    }

    // The RUNNING_* levels are deprecated because the platform stopped delivering them in
    // API 34. They still arrive on API 26 to 33, which is what most Android TV boxes in the
    // field run, and there they are the only signal that arrives while the grid is visible.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The home screen is almost always visible, so UI_HIDDEN alone would miss the
        // pressure the launcher itself causes on those older builds. RUNNING_CRITICAL (15)
        // also covers UI_HIDDEN (20) and every background level above it, where the grid is
        // off screen and every cached icon is dead weight until it is shown again.
        when {
            level >= TRIM_MEMORY_RUNNING_CRITICAL -> iconCache.trim()
            level >= TRIM_MEMORY_RUNNING_MODERATE -> iconCache.trimToShare(SHALLOW_TRIM_SHARE)
        }
    }

    override fun onDestroy() {
        wallpaper.shutdown()
        iconLoader.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recordHomePressIfHomeIntent(intent)
        selectTab(Tab.HOME)
    }

    /**
     * Single input path for the whole screen.
     *
     * This is the only place that sees a key before the view hierarchy, so activation is
     * decided here rather than hoping a plain focusable view turns a press into a click.
     * ACTION_DOWN is always passed through so long press still works; ACTION_UP is consumed
     * for centre keys so exactly one activation happens.
     *
     * Plain [Activity] is used deliberately: ComponentActivity marks dispatchKeyEvent as
     * restricted, which blocks overriding it.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focusName = focusedViewName()
            KeyLog.record(event.keyCode, event.action, focusName)

            // Escape hatch: UP from the top row of the grid always reaches the header,
            // so the navigation controls can never become unreachable.
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP && isFocusInFirstGridRow()) {
                binding.tabSearch.requestFocus()
                return true
            }
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            when {
                menuEntry != null -> hideMenu()
                tab == Tab.SEARCH -> selectTab(Tab.HOME)
                // A launcher never exits on BACK; it has nowhere to go back to.
                else -> Unit
            }
            return true
        }

        if (RemoteKeys.isCenter(event.keyCode) && event.action == KeyEvent.ACTION_UP) {
            // A long press already opened the menu, so the release must not also activate.
            if (suppressNextCenter) {
                suppressNextCenter = false
                return true
            }
            val focused = currentFocus
            if (focused == null) {
                focusFirstTile()
                return true
            }
            if (focused.isClickable) {
                focused.isPressed = false
                focused.performClick()
                KeyLog.recordActivation()
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            // Alternative to long-press, for remotes that do not send a long press.
            focusedEntry()?.let(::showMenu)
            true
        } else {
            super.onKeyDown(keyCode, event)
        }

    /** True when the focused tile sits in the first row of tiles, headings aside. */
    private fun isFocusInFirstGridRow(): Boolean {
        val focused = currentFocus ?: return false
        val item = binding.appGrid.findContainingItemView(focused) ?: return false
        val position = binding.appGrid.getChildAdapterPosition(item)
        if (position == RecyclerView.NO_POSITION) return false

        var tilesInRow = 0
        for (index in 0..position) {
            if (adapter.isHeader(index)) {
                // A heading between rows means at least one row of tiles is above us.
                if (tilesInRow > 0) return false
                tilesInRow = 0
            } else if (index < position) {
                tilesInRow++
                // A full row has already wrapped, so this is not the top row.
                if (tilesInRow == gridLayoutManager.spanCount) return false
            }
        }
        return true
    }

    /** Human-readable name of the focused view, for the remote diagnostics. */
    private fun focusedViewName(): String {
        val focused = currentFocus ?: return "none"
        val id = focused.id
        if (id != View.NO_ID) {
            runCatching { resources.getResourceEntryName(id) }.getOrNull()?.let { return it }
        }
        return focused.javaClass.simpleName
    }

    // region tabs

    private fun selectTab(next: Tab) {
        tab = next
        binding.tabSearch.isSelected = next == Tab.SEARCH
        binding.tabHome.isSelected = next == Tab.HOME
        binding.tabApps.isSelected = next == Tab.APPS
        binding.searchPanel.visibility = if (next == Tab.SEARCH) View.VISIBLE else View.GONE
        applyTab()

        if (next == Tab.SEARCH) {
            focusSearchField()
        } else {
            hideKeyboard()
            if (next == Tab.APPS) {
                resetToFirstTile()
            } else {
                focusFirstTile()
            }
        }
    }

    /**
     * Opens the Apps tab at the top of the list.
     *
     * RecyclerView keeps the focused child across adapter updates, so a tab switch would
     * otherwise land on whichever app was focused last. The old focus is dropped before
     * the grid scrolls home, then the first real tile (headings aside) is focused once
     * its view exists.
     */
    private fun resetToFirstTile() {
        binding.appGrid.clearFocus()
        gridLayoutManager.scrollToPositionWithOffset(0, 0)
        focusFirstTile(force = true)
    }

    /**
     * Focuses the first app tile, skipping section headings.
     *
     * The RecyclerView itself is deliberately not focusable, otherwise it traps the
     * focus chain and D-pad only scrolls the grid without ever reaching the header.
     * Children may not exist on the first pass, so a bounded retry is scheduled.
     */
    private fun focusFirstTile(force: Boolean = false, attempt: Int = 0) {
        binding.appGrid.post {
            if (force) binding.appGrid.clearFocus()
            if (focusFirstTileChild(force)) return@post
            if (attempt < TILE_FOCUS_RETRIES) {
                binding.appGrid.postDelayed(
                    { focusFirstTile(force, attempt + 1) },
                    TILE_FOCUS_RETRY_MS,
                )
            }
        }
    }

    private fun focusFirstTileChild(force: Boolean): Boolean {
        val grid = binding.appGrid
        if (!force && grid.focusedChild != null) return true
        if (force) {
            val holder = grid.findViewHolderForAdapterPosition(firstTilePosition()) ?: return false
            val tile = holder.itemView
            if (!tile.isAttachedToWindow) return false
            return tile.requestFocus()
        }
        for (index in 0 until grid.childCount) {
            val child = grid.getChildAt(index) ?: continue
            val position = grid.getChildAdapterPosition(child)
            if (position != RecyclerView.NO_POSITION && !adapter.isHeader(position)) {
                return child.requestFocus()
            }
        }
        return false
    }

    private fun firstTilePosition(): Int {
        for (position in 0 until adapter.itemCount) {
            if (!adapter.isHeader(position)) return position
        }
        return RecyclerView.NO_POSITION
    }

    private fun applyTab() {
        val rows: List<HomeRow> = when (tab) {
            Tab.HOME -> HomeRowBuilder.forHome(favorites, rest)
            Tab.APPS -> HomeRowBuilder.forApps(allByLabel)
            Tab.SEARCH -> HomeRowBuilder.search(searchQuery, allByLabel)
                .map { HomeRow.Tile(it) }
        }
        adapter.submit(rows)
        updateSearchSummary()
    }

    private fun updateSearchSummary() {
        if (tab != Tab.SEARCH) {
            binding.searchSummary.visibility = View.GONE
            return
        }
        // Search rows are tiles only, so the adapter already holds the match count. Running
        // the filter a second time here meant two passes over the catalog per keystroke.
        val count = adapter.itemCount
        binding.searchSummary.visibility = View.VISIBLE
        binding.searchSummary.text = if (count == 0) {
            getString(R.string.search_none)
        } else {
            getString(R.string.search_results, count)
        }
    }

    private fun focusSearchField() {
        binding.searchInput.requestFocus()
        getSystemService(InputMethodManager::class.java)
            ?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    // endregion

    /** Only a launch carrying CATEGORY_HOME is a real HOME key press. */
    private fun recordHomePressIfHomeIntent(intent: Intent?) {
        if (intent?.hasCategory(Intent.CATEGORY_HOME) == true) prefs.recordHomePress()
    }

    private fun reloadApps() {
        if (scanning) {
            // A pin, hide or move landed mid-scan. Remember it instead of dropping it.
            rescanQueued = true
            return
        }
        scanning = true
        worker.execute {
            val loaded = runCatching { repository.loadRawApps() }.getOrNull()
            val scanned = loaded?.first.orEmpty()
            scanSummary = loaded?.second
            // Hiding only prunes the Home tab. Apps and Search keep the full catalog, so
            // a hidden app is always one tab away and can be restored from there.
            val split = CatalogBuilder.applyFavorites(scanned, prefs.favoriteOrder, prefs.hiddenPackages)
            val favEntries = AppEntry.list(split.first)
            val restEntries = AppEntry.list(split.second)
            val allEntries = AppEntry.list(scanned)
            runOnUiThread {
                scanning = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                favorites = favEntries
                rest = restEntries
                allByLabel = allEntries
                applyTab()
                if (!focusSet && allEntries.isNotEmpty()) {
                    focusSet = true
                    focusFirstTile()
                } else if (currentFocus == null) {
                    // The focused tile was just hidden; do not leave the remote dead.
                    focusFirstTile()
                }
                if (rescanQueued) {
                    rescanQueued = false
                    reloadApps()
                }
            }
        }
    }

    private fun startNetworkMonitoring() {
        if (networkMonitoring) return
        networkMonitoring = true
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(networkCallback)
        }
        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION).apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(wifiStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(wifiStateReceiver, filter)
            }
        }
    }

    private fun stopNetworkMonitoring() {
        if (!networkMonitoring) return
        networkMonitoring = false
        runCatching {
            getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(networkCallback)
        }
        runCatching { unregisterReceiver(wifiStateReceiver) }
    }

    private fun refreshNetworkStatusLater() {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) refreshNetworkStatus()
        }
    }

    /**
     * Re-reads connectivity, including the "Wi-Fi is on but nothing is connected" case
     * that has no active network and therefore used to look identical to being offline.
     */
    @Suppress("DEPRECATION")
    private fun refreshNetworkStatus() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val active = connectivity?.activeNetwork?.let(connectivity::getNetworkCapabilities)
        val wifiConnected = connectivity?.allNetworks?.any { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } == true
        val chip = networkChip(
            activeWifi = active?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            activeEthernet = active?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
            internetAvailable =
                active?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            wifiConnected = wifiConnected,
            wifiEnabled = getSystemService(WifiManager::class.java)?.isWifiEnabled == true,
        )
        binding.statusNetworkLabel.setText(chip.labelRes)
        binding.statusNetworkIcon.setImageResource(chip.iconRes)
        binding.statusNetworkIcon.alpha = if (chip.connected) 1f else 0.4f
    }

    private fun openApp(entry: AppEntry) {
        val intent = repository.launchIntentFor(entry)
        if (intent == null) {
            showNotice(getString(R.string.notice_cannot_launch, entry.label))
            return
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            showNotice(getString(R.string.notice_cannot_launch, entry.label))
            reloadApps()
        }
    }

    private fun focusedEntry(): AppEntry? {
        val focused = binding.appGrid.focusedChild ?: return null
        return adapter.tileAt(binding.appGrid.getChildAdapterPosition(focused))
    }

    private fun withEntry(action: (AppEntry) -> Unit) {
        menuEntry?.let(action)
    }

    private fun showMenu(entry: AppEntry) {
        menuEntry = entry
        // Reached by long press: the matching ACTION_UP must not also open the app.
        suppressNextCenter = true
        val isFavorite = prefs.isFavorite(entry.key)
        binding.menuTitle.text = entry.label
        binding.menuPin.setText(if (isFavorite) R.string.menu_unpin else R.string.menu_pin)
        binding.menuMoveLeft.visibility = if (isFavorite) View.VISIBLE else View.GONE
        binding.menuMoveRight.visibility = if (isFavorite) View.VISIBLE else View.GONE
        // The same menu is used from every tab, so spell out which grid Hide affects and
        // let an app hidden earlier be brought back from the Apps or Search tab.
        binding.menuHide.setText(
            if (prefs.isHidden(entry.packageName)) R.string.menu_show_home else R.string.menu_hide_home,
        )
        binding.menuOverlay.visibility = View.VISIBLE
        binding.menuPin.requestFocus()
    }

    private fun hideMenu() {
        menuEntry = null
        binding.menuOverlay.visibility = View.GONE
    }

    private fun toggleFavorite(entry: AppEntry) {
        if (prefs.isFavorite(entry.key)) prefs.removeFavorite(entry.key) else prefs.addFavorite(entry.key)
        reloadApps()
    }

    private fun moveFavorite(entry: AppEntry, delta: Int) {
        val index = prefs.favoriteOrder.indexOf(entry.key)
        if (index < 0) return
        prefs.moveFavorite(entry.key, index + delta)
        reloadApps()
    }

    private fun hideApp(entry: AppEntry) {
        prefs.toggleHidden(entry.packageName)
        reloadApps()
    }

    private fun openAppInfo(entry: AppEntry) {
        repository.appInfoIntent(entry.packageName)?.let { startActivity(it) }
    }

    /**
     * Cross-fades to the new photo. The old image stays on screen while the next one is
     * fading in, so a slow refresh never flashes black between wallpapers.
     */
    private fun showWallpaper(bitmap: Bitmap) {
        val view = binding.wallpaperImage
        if (view.visibility != View.VISIBLE || view.drawable == null) {
            view.animate().cancel()
            view.setImageBitmap(bitmap)
            view.alpha = 0f
            view.visibility = View.VISIBLE
            binding.wallpaperScrim.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(WALLPAPER_FADE_IN_MS).start()
            return
        }

        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(WALLPAPER_FADE_OUT_MS)
            .withEndAction {
                // Wallpaper may have been switched off while the fade was running.
                if (isFinishing || isDestroyed || view.visibility != View.VISIBLE) {
                    return@withEndAction
                }
                view.setImageBitmap(bitmap)
                binding.wallpaperScrim.visibility = View.VISIBLE
                view.animate().alpha(1f).setDuration(WALLPAPER_FADE_IN_MS).start()
            }
            .start()
    }

    private fun hideWallpaper() {
        binding.wallpaperImage.animate().cancel()
        binding.wallpaperImage.setImageDrawable(null)
        binding.wallpaperImage.alpha = 1f
        binding.wallpaperImage.visibility = View.GONE
        binding.wallpaperScrim.visibility = View.GONE
    }

    private fun openSetup() = startActivity(Intent(this, SetupActivity::class.java))

    private fun openInputs() {
        try {
            startActivity(Intent(ACTION_VIEW_INPUTS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            openDeviceSettings()
        }
    }

    private fun openDeviceSettings() = openSettings(Settings.ACTION_SETTINGS)

    private fun openNetworkSettings() = openSettings(Settings.ACTION_WIFI_SETTINGS)

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            openSettings(Settings.ACTION_SETTINGS)
        }
    }

    private fun showNotice(message: String) {
        binding.notice.text = message
        binding.notice.visibility = View.VISIBLE
        binding.notice.removeCallbacks(hideNotice)
        binding.notice.postDelayed(hideNotice, NOTICE_MS)
    }

    private fun spanCountFor(): Int {
        val metrics = resources.displayMetrics
        val tileWidth = resources.getDimensionPixelSize(R.dimen.tile_width)
        val spacing = resources.getDimensionPixelSize(R.dimen.grid_spacing)
        val margin = resources.getDimensionPixelSize(R.dimen.screen_margin)
        val usable = metrics.widthPixels - margin * 2
        return ((usable + spacing) / (tileWidth + spacing)).coerceIn(MIN_COLUMNS, MAX_COLUMNS)
    }

    private companion object {
        const val ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS"
        const val NOTICE_MS = 2_500L
        const val SHALLOW_TRIM_SHARE = 0.5
        const val TILE_FOCUS_RETRY_MS = 120L
        const val TILE_FOCUS_RETRIES = 4
        const val WALLPAPER_FADE_OUT_MS = 220L
        const val WALLPAPER_FADE_IN_MS = 550L
        const val MIN_COLUMNS = 4
        const val MAX_COLUMNS = 10
    }
}