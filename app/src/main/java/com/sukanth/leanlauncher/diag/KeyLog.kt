package com.sukanth.leanlauncher.diag

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records the last remote key the home screen actually received.
 *
 * This exists because "the remote does not work" has several very different causes that
 * look identical from the sofa: the key never arrives, it arrives with an unexpected
 * key code, or it arrives with nothing focused to activate. Recording the raw key code
 * and the focused view tells them apart instead of guessing.
 */
object KeyLog {

    private val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var lastKeyCode: Int = 0

    @Volatile
    private var lastAction: Int = 0

    @Volatile
    private var lastFocus: String = ""

    @Volatile
    private var lastAt: Long = 0L

    @Volatile
    var activations: Int = 0
        private set

    fun record(keyCode: Int, action: Int, focusedView: String) {
        if (action != android.view.KeyEvent.ACTION_DOWN) return
        lastKeyCode = keyCode
        lastAction = action
        lastFocus = focusedView
        lastAt = System.currentTimeMillis()
    }

    fun recordActivation() {
        activations++
    }

    fun describe(): List<Pair<String, String>> = listOf(
        "Last remote key" to if (lastKeyCode == 0) {
            "none received yet"
        } else {
            RemoteKeys.name(lastKeyCode)
        },
        "Key arrived at" to if (lastAt > 0L) timestamp.format(Date(lastAt)) else "never",
        "Focus at that moment" to lastFocus.ifEmpty { "unknown" },
        "Activations performed" to activations.toString(),
    )
}