package com.sukanth.leanlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import com.sukanth.leanlauncher.data.AppRepository
import com.sukanth.leanlauncher.data.Prefs
import com.sukanth.leanlauncher.data.WallpaperSource
import com.sukanth.leanlauncher.databinding.ActivitySetupBinding
import com.sukanth.leanlauncher.diag.DiagnosticsReport
import com.sukanth.leanlauncher.diag.RemoteKeys
import com.sukanth.leanlauncher.setup.AdbFailure
import com.sukanth.leanlauncher.setup.AdbInstructions
import com.sukanth.leanlauncher.setup.AdbOutcome
import com.sukanth.leanlauncher.setup.AdbShellCommands
import com.sukanth.leanlauncher.setup.HomeRival
import com.sukanth.leanlauncher.setup.HomeRoleController
import com.sukanth.leanlauncher.setup.HomeState
import com.sukanth.leanlauncher.setup.HomeStateProbe
import com.sukanth.leanlauncher.setup.OnDeviceAdb
import com.sukanth.leanlauncher.setup.RoleRequestResult
import com.sukanth.leanlauncher.wallpaper.WebWallpaper
import java.util.concurrent.Executors

/**
 * Setup and wallpaper, reachable from the TV app list and from the home header.
 *
 * This screen never claims the default-home swap succeeded. It reports what it observes
 * and offers both remediation paths, because Android TV firmware commonly ignores the
 * standard request. The one-press path uses the TV's own ADB service; the copy buttons
 * remain the fallback for firmware that blocks it.
 */
class SetupActivity : Activity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var probe: HomeStateProbe
    private lateinit var controller: HomeRoleController
    private lateinit var onDeviceAdb: OnDeviceAdb

    private val repository by lazy { AppRepository(this) }
    private val prefs by lazy { Prefs(this) }
    private val adbExecutor = Executors.newSingleThreadExecutor()

    private var adbRunning = false

    /** Ready-to-paste scripts for the two stock-launcher buttons, rebuilt on every probe. */
    private var disableStockScript = ""
    private var restoreStockScript = ""

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_LOCAL_WALLPAPER) {
            if (resultCode == RESULT_OK && data != null) acceptLocalWallpapers(data)
            return
        }
        if (!controller.onActivityResult(requestCode, resultCode)) return
    }

    private val roleControllerCallback = { result: RoleRequestResult ->
        val state = probe.probe()
        render(state)
        when (result) {
            RoleRequestResult.Granted -> openHome()
            RoleRequestResult.Refused -> binding.statusMessage.text =
                getString(R.string.setup_refused)

            RoleRequestResult.Unavailable -> showAdbPath()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        probe = HomeStateProbe(this)
        controller = HomeRoleController(this, probe, roleControllerCallback)
        onDeviceAdb = OnDeviceAdb(applicationContext)

        binding.requestHomeButton.setOnClickListener { requestHome() }
        binding.previewButton.setOnClickListener { openHome() }
        binding.stockOneClickDisableButton.setOnClickListener { requestAdb(AdbAction.DISABLE) }
        binding.stockOneClickRestoreButton.setOnClickListener { requestAdb(AdbAction.RESTORE) }
        binding.stockDisableButton.setOnClickListener { copyStockLauncherCommands(restore = false) }
        binding.stockRestoreButton.setOnClickListener { copyStockLauncherCommands(restore = true) }
        binding.copyAdbButton.setOnClickListener { copyAdbCommands() }
        binding.wallpaperSourceButton.setOnClickListener { cycleWallpaperSource() }
        binding.wallpaperPickButton.setOnClickListener { chooseLocalWallpapers() }
        binding.wallpaperUrlButton.setOnClickListener { editWallpaperUrl() }
        binding.wallpaperToggleButton.setOnClickListener { toggleWallpaper() }
        binding.wallpaperIntervalButton.setOnClickListener { cycleWallpaperInterval() }
        binding.wallpaperNextButton.setOnClickListener { nextWallpaper() }

        // The first visible action should own D-pad focus when this screen opens.
        binding.requestHomeButton.post {
            if (currentFocus == null || !currentFocus!!.isClickable) {
                binding.requestHomeButton.requestFocus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val state = probe.probe()
        render(state)
        renderWallpaper()
    }

    /** Fallback for TV remotes that do not dispatch a centre-key click to a focused view. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (RemoteKeys.isCenter(keyCode)) {
            val focused = currentFocus
            if (focused != null && focused.isClickable) {
                focused.performClick()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        // The ADB call is intentionally bounded by socket timeouts. Interrupt it so a
        // finished setup screen does not keep a worker alive, and let the application
        // context held by OnDeviceAdb outlive this activity safely.
        adbExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun render(state: HomeState) {
        val rivals = probe.homeRivals(state.candidates)
        rememberStockHome(state, rivals)
        rememberStockLaunchers(rivals)
        val restorePackages = (rivals.map { it.packageName } + prefs.stockLauncherPackages).distinct()

        // Both buttons stay available even when LeanLauncher already owns HOME, so the
        // stock launcher can be disabled or brought back without hunting for the old
        // ADB block, which is hidden once the swap is verified.
        val fixCommands = if (rivals.isNotEmpty()) {
            AdbInstructions.overtakeFixScript(repository.selfComponent, rivals)
        } else {
            AdbInstructions.setupCommands(repository.selfComponent, state.blockingPackage)
        }
        disableStockScript = fixCommands.joinToString("\n")
        val restoreComponent = stockHomeComponent(restorePackages)
        restoreStockScript = AdbInstructions.restoreStockLauncherScript(
            stockPackages = restorePackages,
            stockHomeComponent = restoreComponent,
        ).joinToString("\n")

        binding.statusHeadline.text = DiagnosticsReport.headline(state)
        binding.guidanceText.text = DiagnosticsReport.guidance(state, rivals).joinToString("\n\n")

        binding.adbSection.visibility =
            if (state.isVerifiedDefault) View.GONE else View.VISIBLE

        if (state.isVerifiedDefault) {
            binding.adbText.text = ""
            return
        }
        val rivalText = rivals.filter { it.claimingHome }.joinToString("\n") {
            "• ${it.label} (${it.packageName})"
        }
        binding.rivalsText.text = rivalText
        binding.rivalsText.visibility = if (rivalText.isEmpty()) View.GONE else View.VISIBLE
        binding.adbText.text = fixCommands.joinToString("\n")
        binding.adbNotes.text = (
            AdbInstructions.restoreStockLauncherScript(restorePackages, restoreComponent) +
                AdbInstructions.manualFallbackNotes()
            ).joinToString("\n\n")
    }

    /**
     * Remembers every known stock launcher seen installed. The restore script must keep
     * working after those packages have been disabled, when a candidate query can no
     * longer find them.
     */
    private fun rememberStockLaunchers(rivals: List<HomeRival>) {
        if (rivals.isEmpty()) return
        val saved = prefs.stockLauncherPackages
        val merged = saved + rivals.map { it.packageName }
        if (merged != saved) prefs.stockLauncherPackages = merged
    }

    /**
     * Remembers the exact component that owned HOME before the stock launcher was
     * disabled. The restore script uses it later, when the disabled package no longer
     * appears in the ordinary HOME candidate list.
     */
    private fun rememberStockHome(state: HomeState, rivals: List<HomeRival>) {
        val home = state.defaultHome ?: return
        if (rivals.none { it.packageName == home.packageName }) return
        val component = "${home.packageName}/${home.className}"
        if (prefs.lastStockHomeComponent != component) {
            prefs.lastStockHomeComponent = component
        }
    }

    /**
     * Full `package/activity` to hand HOME back to. The saved component wins because it
     * is the one actually observed; otherwise fall back to any HOME activity the stock
     * package still declares, including while it is disabled.
     */
    private fun stockHomeComponent(stockPackages: List<String>): String? {
        val saved = prefs.lastStockHomeComponent
        if (saved != null && saved.substringBefore('/') in stockPackages) {
            return saved
        }
        val observed = probe.homeComponentsIncludingDisabled()
        return stockPackages.firstNotNullOfOrNull { observed[it] }
    }

    /**
     * The wallpaper is opt-in and off by default. Turning it on here only records the
     * choice: the home screen starts loading the first image when it next resumes.
     */
    private fun toggleWallpaper() {
        if (!prefs.wallpaperEnabled) {
            sourceMissingMessage()?.let {
                showWallpaperStatus(getString(it))
                return
            }
        }
        prefs.wallpaperEnabled = !prefs.wallpaperEnabled
        binding.wallpaperStatus.visibility = View.GONE
        renderWallpaper()
    }

    private fun cycleWallpaperInterval() {
        val intervals = Prefs.WALLPAPER_INTERVALS_MINUTES
        val current = intervals.indexOf(prefs.wallpaperIntervalMinutes)
        prefs.wallpaperIntervalMinutes = intervals[(current + 1) % intervals.size]
        binding.wallpaperStatus.visibility = View.GONE
        renderWallpaper()
    }

    /** Also switches the wallpaper on: asking for a picture implies wanting to see it. */
    private fun nextWallpaper() {
        sourceMissingMessage()?.let {
            showWallpaperStatus(getString(it))
            return
        }
        prefs.wallpaperEnabled = true
        prefs.requestNextWallpaper()
        renderWallpaper()
        showWallpaperStatus(getString(R.string.wallpaper_queued))
    }

    /** Cycles Built-in -> Local -> Website, without turning the wallpaper on by itself. */
    private fun cycleWallpaperSource() {
        prefs.wallpaperSource = when (prefs.wallpaperSource) {
            WallpaperSource.BUILTIN -> WallpaperSource.LOCAL
            WallpaperSource.LOCAL -> WallpaperSource.WEB
            WallpaperSource.WEB -> WallpaperSource.BUILTIN
        }
        prefs.requestNextWallpaper()
        binding.wallpaperStatus.visibility = View.GONE
        renderWallpaper()
        showWallpaperStatus(
            getString(
                R.string.wallpaper_source_switched,
                getString(sourceLabelRes(prefs.wallpaperSource)),
            ),
        )
    }

    private fun chooseLocalWallpapers() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_LOCAL_WALLPAPER)
        } catch (_: ActivityNotFoundException) {
            showWallpaperStatus(getString(R.string.wallpaper_picker_missing))
        }
    }

    private fun acceptLocalWallpapers(data: Intent) {
        val uris = buildList {
            data.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) {
                    clip.getItemAt(index).uri?.let(::add)
                }
            }
            if (isEmpty()) data.data?.let(::add)
        }.distinct().take(MAX_LOCAL_WALLPAPERS)

        if (uris.isEmpty()) {
            showWallpaperStatus(getString(R.string.wallpaper_local_none))
            return
        }

        var persisted = 0
        for (uri in uris) {
            val kept = runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.isSuccess
            if (kept) persisted++
        }

        prefs.localWallpaperUris = uris.map { it.toString() }
        prefs.wallpaperSource = WallpaperSource.LOCAL
        prefs.wallpaperEnabled = true
        prefs.requestNextWallpaper()
        renderWallpaper()

        val saved = resources.getQuantityString(
            R.plurals.wallpaper_local_saved,
            uris.size,
            uris.size,
        )
        showWallpaperStatus(
            if (persisted == uris.size) {
                saved
            } else {
                saved + "\n" + getString(R.string.wallpaper_local_again)
            },
        )
    }

    private fun editWallpaperUrl() {
        val input = EditText(this).apply {
            setText(prefs.wallpaperWebUrl.orEmpty())
            hint = getString(R.string.wallpaper_url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setSelectAllOnFocus(true)
        }
        val container = FrameLayout(this).apply {
            val horizontal = (24 * resources.displayMetrics.density).toInt()
            val top = (8 * resources.displayMetrics.density).toInt()
            setPadding(horizontal, top, horizontal, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.wallpaper_url_title)
            .setMessage(R.string.wallpaper_url_note)
            .setView(container)
            .setPositiveButton(R.string.wallpaper_url_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val normalized = WebWallpaper.normalizePageUrl(input.text.toString())
                if (normalized == null) {
                    input.error = getString(R.string.wallpaper_url_invalid)
                    return@setOnClickListener
                }
                dialog.dismiss()
                prefs.wallpaperWebUrl = normalized
                prefs.wallpaperSource = WallpaperSource.WEB
                prefs.wallpaperEnabled = true
                prefs.requestNextWallpaper()
                renderWallpaper()
                showWallpaperStatus(getString(R.string.wallpaper_url_saved))
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
            )
        }
        dialog.show()
    }

    private fun renderWallpaper() {
        val source = prefs.wallpaperSource
        val localCount = prefs.localWallpaperUris.size
        binding.wallpaperToggleButton.setText(
            if (prefs.wallpaperEnabled) R.string.wallpaper_toggle_on else R.string.wallpaper_toggle_off,
        )
        binding.wallpaperIntervalButton.text = getString(
            R.string.wallpaper_interval,
            getString(intervalLabelRes(prefs.wallpaperIntervalMinutes)),
        )
        binding.wallpaperSourceButton.text = getString(
            R.string.wallpaper_source,
            getString(sourceLabelRes(source)),
        )
        binding.wallpaperPickButton.text =
            if (localCount == 0) {
                getString(R.string.wallpaper_pick_local)
            } else {
                resources.getQuantityString(
                    R.plurals.wallpaper_pick_local_count,
                    localCount,
                    localCount,
                )
            }
        binding.wallpaperUrlButton.setText(
            if (prefs.wallpaperWebUrl == null) {
                R.string.wallpaper_url_set
            } else {
                R.string.wallpaper_url_change
            },
        )
        binding.wallpaperDetail.text = when (source) {
            WallpaperSource.BUILTIN -> getString(R.string.wallpaper_detail_builtin)
            WallpaperSource.LOCAL -> if (localCount == 0) {
                getString(R.string.wallpaper_detail_local_empty)
            } else {
                resources.getQuantityString(
                    R.plurals.wallpaper_detail_local,
                    localCount,
                    localCount,
                )
            }

            WallpaperSource.WEB -> prefs.wallpaperWebUrl?.let {
                getString(R.string.wallpaper_detail_web, it)
            } ?: getString(R.string.wallpaper_source_needs_url)
        }
    }

    private fun sourceLabelRes(source: WallpaperSource): Int = when (source) {
        WallpaperSource.BUILTIN -> R.string.wallpaper_source_builtin
        WallpaperSource.LOCAL -> R.string.wallpaper_source_local
        WallpaperSource.WEB -> R.string.wallpaper_source_web
    }

    private fun sourceMissingMessage(): Int? = when (prefs.wallpaperSource) {
        WallpaperSource.BUILTIN -> null
        WallpaperSource.LOCAL ->
            if (prefs.localWallpaperUris.isEmpty()) R.string.wallpaper_source_needs_local else null

        WallpaperSource.WEB ->
            if (prefs.wallpaperWebUrl == null) R.string.wallpaper_source_needs_url else null
    }

    private fun showWallpaperStatus(message: String) {
        binding.wallpaperStatus.visibility = View.VISIBLE
        binding.wallpaperStatus.text = message
    }

    private fun intervalLabelRes(minutes: Int): Int = when (minutes) {
        15 -> R.string.wallpaper_15m
        30 -> R.string.wallpaper_30m
        360 -> R.string.wallpaper_6h
        1440 -> R.string.wallpaper_1d
        else -> R.string.wallpaper_1h
    }

    private fun requestHome() {
        binding.statusMessage.visibility = View.GONE
        val requested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            controller.request()
        } else {
            controller.requestViaHomeIntent()
        }
        if (!requested) showAdbPath()
    }

    /**
     * Makes sure this app's own HOME activity is enabled before the one-press path tries
     * to make it the default. A component disabled by a previous app version cannot be
     * set as HOME, and some TV builds forbid shell `pm enable` of a component.
     */
    private fun enableSelfHomeComponent() {
        runCatching {
            packageManager.setComponentEnabledSetting(
                probe.selfHomeComponent(),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
        }
    }

    private fun showAdbPath() {
        binding.adbSection.visibility = View.VISIBLE
        binding.statusMessage.visibility = View.VISIBLE
        binding.statusMessage.text = getString(R.string.setup_use_adb)
    }

    /**
     * Entry point for both one-press buttons. The first use needs an explicit explanation:
     * the app is about to ask the TV for shell access, which is more powerful than the
     * normal in-app home request.
     */
    private fun requestAdb(action: AdbAction) {
        if (adbRunning) {
            showStockStatus(getString(R.string.setup_adb_busy))
            return
        }
        if (prefs.adbConsentAccepted) {
            runAdbAction(action)
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.setup_adb_consent_title)
            .setMessage(R.string.setup_adb_consent_message)
            .setPositiveButton(R.string.setup_adb_consent_allow) { _, _ ->
                prefs.adbConsentAccepted = true
                runAdbAction(action)
            }
            .setNegativeButton(R.string.setup_adb_consent_cancel) { _, _ ->
                showStockStatus(getString(R.string.setup_adb_consent_declined))
            }
            .setOnCancelListener {
                showStockStatus(getString(R.string.setup_adb_consent_declined))
            }
            .show()
    }

    private fun runAdbAction(action: AdbAction) {
        if (adbRunning) return

        val state = probe.probe()
        val rivals = probe.homeRivals(state.candidates)
        val restorePackages =
            (rivals.map { it.packageName } + prefs.stockLauncherPackages).distinct()
        val restoreComponent = stockHomeComponent(restorePackages)

        val commands = when (action) {
            AdbAction.DISABLE -> AdbShellCommands.disableStockLauncher(
                ourComponent = repository.selfComponent,
                rivalPackages = rivals.map { it.packageName },
            )

            AdbAction.RESTORE -> AdbShellCommands.restoreStockLauncher(
                stockPackages = restorePackages,
                stockHomeComponent = restoreComponent,
            )
        }

        if (action == AdbAction.RESTORE && commands.isEmpty()) {
            showStockStatus(getString(R.string.setup_stock_none))
            return
        }

        if (action == AdbAction.DISABLE) {
            // A disabled HOME component cannot be made the default, and some TV builds
            // forbid shell `pm enable` of a component. The app can always enable its own.
            enableSelfHomeComponent()
            render(probe.probe())
            if (!probe.isSelfHomeEnabled()) {
                showStockStatus(getString(R.string.setup_adb_self_home_disabled))
                return
            }
        }

        val expectedPackage = when (action) {
            AdbAction.DISABLE -> packageName
            AdbAction.RESTORE -> restoreComponent?.substringBefore('/')
        }

        adbRunning = true
        setAdbButtonsEnabled(false)
        showStockStatus(getString(R.string.setup_adb_connecting))

        adbExecutor.execute {
            val outcome = onDeviceAdb.execute(commands)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adbRunning = false
                setAdbButtonsEnabled(true)
                showAdbOutcome(action, expectedPackage, outcome)
            }
        }
    }

    private fun showAdbOutcome(
        action: AdbAction,
        expectedPackage: String?,
        outcome: AdbOutcome,
    ) {
        val message = when (outcome.failure) {
            AdbFailure.UNREACHABLE -> getString(R.string.setup_adb_unreachable)
            AdbFailure.AUTHORIZATION -> getString(R.string.setup_adb_not_authorized)
            AdbFailure.KEY -> getString(R.string.setup_adb_key_failed)
            AdbFailure.COMMAND -> getString(
                R.string.setup_adb_failed,
                outcome.detail ?: getString(R.string.setup_adb_unknown_error),
            )

            null -> {
                // Re-probe before speaking: the shell command's report is not proof that
                // HOME really moved, and a stale screen must not hide the fallback.
                render(probe.probe())
                val resolved = outcome.resolvedHome
                when (action) {
                    AdbAction.DISABLE -> when {
                        resolved == packageName && outcome.failedCommands.isEmpty() ->
                            getString(R.string.setup_adb_disabled)

                        resolved == packageName ->
                            getString(R.string.setup_adb_disabled_with_warnings)

                        else -> getString(
                            R.string.setup_adb_home_not_changed,
                            resolved ?: getString(R.string.setup_adb_unknown_home),
                        )
                    }

                    AdbAction.RESTORE -> when {
                        expectedPackage != null && resolved == expectedPackage ->
                            getString(R.string.setup_adb_restored)

                        expectedPackage == null ->
                            getString(R.string.setup_adb_restored_no_component)

                        else -> getString(
                            R.string.setup_adb_restore_not_verified,
                            resolved ?: getString(R.string.setup_adb_unknown_home),
                        )
                    }
                }
            }
        }
        showStockStatus(message)
    }

    private fun setAdbButtonsEnabled(enabled: Boolean) {
        val buttons = listOf(
            binding.stockOneClickDisableButton,
            binding.stockOneClickRestoreButton,
        )
        for (button in buttons) {
            button.isEnabled = enabled
            button.isClickable = enabled
            button.isFocusable = enabled
            button.alpha = if (enabled) 1f else 0.5f
        }
    }

    private fun showStockStatus(message: String) {
        binding.stockStatus.visibility = View.VISIBLE
        binding.stockStatus.text = message
    }

    private fun copyAdbCommands() {
        copyToClipboard(binding.adbText.text.toString())
        binding.statusMessage.visibility = View.VISIBLE
        binding.statusMessage.text = getString(R.string.setup_copied)
    }

    /**
     * The manual fallback. The one-press buttons above run these same fixed commands
     * through the TV's own ADB service; these buttons copy them for a computer when the
     * firmware blocks on-device ADB. The script is rebuilt from the latest probe so it
     * always names this TV's launchers.
     */
    private fun copyStockLauncherCommands(restore: Boolean) {
        val commands = if (restore) restoreStockScript else disableStockScript
        if (commands.isBlank()) {
            binding.stockStatus.visibility = View.VISIBLE
            binding.stockStatus.text = getString(R.string.setup_stock_none)
            return
        }
        copyToClipboard(commands)
        binding.stockStatus.visibility = View.VISIBLE
        binding.stockStatus.text = getString(
            if (restore) R.string.setup_stock_restore_copied else R.string.setup_stock_disable_copied,
        )
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.setup_adb_label), text),
        )
    }

    private fun openHome() {
        // Fails when the HOME component has been switched off, so it is guarded.
        val started = runCatching {
            startActivity(
                Intent(this, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }.isSuccess
        if (!started) {
            binding.statusMessage.visibility = View.VISIBLE
            binding.statusMessage.text = getString(R.string.setup_continue_failed)
            return
        }
        finish()
    }

    private enum class AdbAction { DISABLE, RESTORE }

    private companion object {
        const val REQUEST_LOCAL_WALLPAPER = 4001
        const val MAX_LOCAL_WALLPAPERS = 40
    }
}
