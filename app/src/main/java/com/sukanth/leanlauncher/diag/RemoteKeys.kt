package com.sukanth.leanlauncher.diag

import android.view.KeyEvent

/**
 * Key codes that a TV remote's OK / Centre button is known to send.
 *
 * Cheap boxes and third-party remotes are inconsistent: some report DPAD_CENTER, some
 * ENTER, and some identify as a gamepad and send BUTTON_A. Handling all of them is the
 * difference between a usable launcher and one where only the D-pad appears to work.
 */
object RemoteKeys {

    fun isCenter(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_1,
        KeyEvent.KEYCODE_SPACE,
        -> true

        else -> false
    }

    /** True for the four directions, which are navigation rather than activation. */
    fun isDirection(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        -> true

        else -> false
    }

    fun name(keyCode: Int): String = runCatching { KeyEvent.keyCodeToString(keyCode) }
        .getOrDefault(keyCode.toString())
}