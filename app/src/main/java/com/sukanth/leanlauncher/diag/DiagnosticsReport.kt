package com.sukanth.leanlauncher.diag

import android.os.Build
import com.sukanth.leanlauncher.setup.HomeRival
import com.sukanth.leanlauncher.setup.HomeState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Renders observed state as label/value rows for the setup and diagnostics screens. */
object DiagnosticsReport {

    private val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun rows(state: HomeState, rivals: List<HomeRival> = emptyList()): List<Pair<String, String>> = buildList {
        add("Our package" to state.ourPackage)
        add("Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        add("Device" to "${Build.MANUFACTURER} ${Build.MODEL}")
        add("Our HOME component" to if (state.selfHomeEnabled) "enabled" else "DISABLED")
        add("Home role API" to if (state.roleApiPresent) "present" else "absent (pre-Android 10)")
        add("Home role available" to state.roleAvailable.toString())
        add("Home role held" to state.roleHeld.toString())
        add(
            "HOME resolves to" to (
                state.defaultHome?.let {
                    "${it.packageName}/${it.className.substringAfterLast('.')}"
                } ?: "nothing"
                ),
        )
        add("Home candidates" to state.candidates.joinToString().ifEmpty { "none" })
        add(
            "Default-home settings" to
                if (state.homeSettingsResolvable) "available" else "no such screen on this device",
        )
        add("Verified default" to state.isVerifiedDefault.toString())
        add("Blocking package" to (state.blockingPackage ?: "none"))
        add(
            "Rival home apps" to rivals.joinToString { rival ->
                "${rival.label} (${rival.packageName})" +
                    if (rival.claimingHome) " [claiming HOME]" else " [not claiming]"
            }.ifEmpty { "none installed" },
        )
    }

    /**
     * Evidence that the HOME key itself reaches LeanLauncher. A role grant or a shell
     * command can both report success without changing where HOME goes, so this counter
     * is the only signal that cannot lie.
     */
    fun verificationRows(lastHomePressAt: Long, count: Int): List<Pair<String, String>> = listOf(
        "HOME reached leanlauncherTV" to if (count > 0) "yes, $count time(s)" else "not yet",
        "Last HOME press" to
            if (lastHomePressAt > 0L) timestamp.format(Date(lastHomePressAt)) else "never",
    )

    fun headline(state: HomeState): String = when {
        !state.selfHomeEnabled -> "leanlauncherTV's home screen is switched off."
        state.isVerifiedDefault -> "leanlauncherTV is the home app."
        state.roleHeld -> "The system accepted the home role, but HOME still opens another app."
        state.roleApiPresent && state.roleAvailable -> "Not the home app yet. Use the button below."
        state.roleApiPresent -> "This device does not offer the home role to apps. Use the one-press switch."
        else -> "This Android version has no home role API. Use the one-press switch."
    }

    /**
     * Steps tailored to the exact state the device reported, most promising first.
     *
     * The role-granted-but-not-rerouted case is called out specifically: it is the known
     * Android TV 11 behaviour and it means the in-app request has already been spent.
     */
    fun guidance(state: HomeState, rivals: List<HomeRival> = emptyList()): List<String> = when {
        !state.selfHomeEnabled -> listOf(
            "leanlauncherTV is not claiming the HOME key, so the system home screen is in charge.",
            "Use Switch to leanlauncherTV in Stock launcher switch to claim it again.",
        )

        state.isVerifiedDefault -> listOf(
            "Nothing to do. Pressing HOME opens leanlauncherTV.",
        )

        state.roleHeld || state.defaultHome?.packageName?.startsWith("com.google") == true -> {
            val rivalNames = rivals.filter { it.claimingHome }.map { it.label }
            buildList {
                add(
                    "HOME currently resolves to ${state.blockingPackage}. This TV is keeping " +
                        "its own launcher as the HOME app even though leanlauncherTV is installed.",
                )
                if (rivalNames.isNotEmpty()) {
                    add(
                        "Installed rivals holding HOME: ${rivalNames.joinToString()}. " +
                            "Copy the fix script below: it disables them, assigns HOME to " +
                            "leanlauncherTV, then verifies where HOME resolves.",
                    )
                } else {
                    add(
                        "No known rival launcher was detected as installed. Copy the ADB " +
                            "assignment below, verify the resolve step, and if HOME still " +
                            "returns here, the firmware is enforcing its own launcher.",
                    )
                }
                add(
                    "Google TV may be a protected system app. If the TV blocks disabling " +
                        "it, this app cannot override that restriction; contact the TV " +
                        "manufacturer for supported options.",
                )
            }
        }

        state.roleApiPresent && state.roleAvailable -> listOf(
            "Press the button below and confirm the system dialog.",
            "If HOME still opens another app afterwards, the firmware ignored the request and the ADB steps below are needed.",
        )

        else -> listOf(
            "This firmware does not offer the home role to apps, so the ADB steps below are the only path.",
        )
    }
}
