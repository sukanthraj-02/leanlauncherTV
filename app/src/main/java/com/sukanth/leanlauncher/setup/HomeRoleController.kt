package com.sukanth.leanlauncher.setup

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build

/** What happened when we asked the system to make LeanLauncher the home app. */
sealed interface RoleRequestResult {
    /** The system confirmed and observation agrees. */
    data object Granted : RoleRequestResult

    /** The user declined, or the system granted but HOME still resolves elsewhere. */
    data object Refused : RoleRequestResult

    /** This build has no role API, or the TV ships no role dialog. */
    data object Unavailable : RoleRequestResult
}

/**
 * Path A: asks the system for [RoleManager.ROLE_HOME] from inside the app.
 *
 * Plain [Activity] with the legacy result API rather than the AndroidX activity-result
 * library. That is deliberate: ComponentActivity would drag the androidx.lifecycle,
 * savedstate and viewmodel tree into a launcher whose whole point is small memory, and
 * this one call does not justify it.
 *
 * No method on this class can force the swap. Android TV firmware is known to accept and
 * ignore the request, so the caller must always re-probe afterwards.
 */
class HomeRoleController(
    private val activity: Activity,
    private val probe: HomeStateProbe,
    private val onFinished: (RoleRequestResult) -> Unit,
) {
    private var pending = false

    /** @return false when there is nothing to request and the caller should show Path B. */
    fun request(): Boolean {
        if (pending) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onFinished(RoleRequestResult.Unavailable)
            return false
        }
        val roleManager = activity.getSystemService(RoleManager::class.java)
        if (roleManager == null) {
            onFinished(RoleRequestResult.Unavailable)
            return false
        }
        if (!runCatching { roleManager.isRoleAvailable(RoleManager.ROLE_HOME) }.getOrDefault(false)) {
            onFinished(RoleRequestResult.Unavailable)
            return false
        }

        val intent = try {
            roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
        } catch (_: Exception) {
            onFinished(RoleRequestResult.Unavailable)
            return false
        }

        return try {
            pending = true
            activity.startActivityForResult(intent, REQUEST_CODE)
            true
        } catch (_: Exception) {
            // No role dialog exists on this firmware.
            pending = false
            onFinished(RoleRequestResult.Unavailable)
            false
        }
    }

    /** @return true when the result belonged to this controller and was consumed. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_CODE) return false
        pending = false
        // RESULT_OK is not proof: some TV builds grant the role and keep the OEM launcher,
        // so the observed state decides the outcome rather than the result code.
        val outcome = if (probe.probe().isVerifiedDefault) {
            RoleRequestResult.Granted
        } else {
            RoleRequestResult.Refused
        }
        onFinished(outcome)
        return true
    }

    /**
     * Last resort for API 26 to 28, where no role API exists: firing HOME lets the stock
     * resolver show its own "choose a home app" dialog if the device has one.
     */
    fun requestViaHomeIntent(): Boolean = try {
        activity.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        pending = true
        true
    } catch (_: Exception) {
        onFinished(RoleRequestResult.Unavailable)
        false
    }

    companion object {
        private const val REQUEST_CODE = 0x1EAA
    }
}