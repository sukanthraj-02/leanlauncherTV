package com.sukanth.leanlauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import com.sukanth.leanlauncher.data.AppRepository
import com.sukanth.leanlauncher.data.IconCache
import com.sukanth.leanlauncher.data.Prefs
import com.sukanth.leanlauncher.databinding.ActivityDiagnosticsBinding
import com.sukanth.leanlauncher.diag.DiagnosticsReport
import com.sukanth.leanlauncher.diag.KeyLog
import com.sukanth.leanlauncher.diag.MemoryProbe
import com.sukanth.leanlauncher.setup.HomeStateProbe
import java.util.concurrent.Executors

/**
 * Full read-out of what the device reports, plus a live memory measurement.
 *
 * Present in release builds because it is the only honest way to answer "is this launcher
 * actually light?" and to tell a user why the home swap did not stick. It is a static list
 * of text plus two buttons, and costs no measurable memory.
 */
class DiagnosticsActivity : Activity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diag-scan").apply { isDaemon = true }
    }

    private var appCounts: List<Pair<String, String>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.diagnosticsTitle.text = getString(R.string.diag_title)

        binding.refreshMemoryButton.setOnClickListener { render() }
        binding.clearCacheButton.setOnClickListener {
            IconCache.get().trim()
            render()
        }
        binding.closeButton.setOnClickListener { finish() }
        binding.relaunchHome.setOnClickListener {
            val started = runCatching {
                startActivity(
                    Intent(this, HomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (started) finish()
        }

        render()
        loadAppCounts()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun render() {
        val probe = HomeStateProbe(this)
        val prefs = Prefs(this)
        val state = probe.probe()
        val rivals = probe.homeRivals(state.candidates)

        val rows = buildList<Pair<String, String>> {
            addAll(DiagnosticsReport.rows(state, rivals))
            addAll(DiagnosticsReport.verificationRows(prefs.lastHomePressAt, prefs.homePressCount))
            addAll(KeyLog.describe())
            add("Self is a candidate" to probe.selfIsCandidate().toString())
            add("ADB target" to AppRepository(this@DiagnosticsActivity).selfComponent)
            add("" to "")
            add("--- memory ---" to "")
            addAll(MemoryProbe.rows(this@DiagnosticsActivity))
            add("" to "")
            add("--- app counts ---" to "")
            addAll(appCounts)
            add("--- guidance ---" to "")
            DiagnosticsReport.guidance(state, rivals).forEach { add(it to "") }
            add("--- HOME candidates ---" to "")
            state.candidates.forEachIndexed { index, value ->
                add("${index + 1}. $value" to "")
            }
        }

        binding.diagnosticsBody.text = rows.joinToString("\n") { (label, value) ->
            if (value.isEmpty()) label else "$label:  $value"
        }
        binding.diagnosticsBody.visibility = View.VISIBLE
    }

    /** App enumeration is blocking, so the counts arrive after the first paint. */
    private fun loadAppCounts() {
        worker.execute {
            val repository = AppRepository(this)
            val summary = runCatching { repository.loadRawApps() }.getOrNull()?.second
                ?: return@execute
            // Deliberately no "visible installed packages" row: counting those makes the
            // package manager materialise an ApplicationInfo for every installed app, and
            // that allocation lands before the memory read-out, inflating the very numbers
            // this screen exists to report.
            val rows = listOf(
                "TV launchable apps" to summary.tvApps.toString(),
                "Plain launcher apps" to summary.otherApps.toString(),
                "Merged app count" to summary.merged.toString(),
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                appCounts = rows
                render()
            }
        }
    }
}