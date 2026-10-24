package com.sukanth.leanlauncher.wallpaper

/**
 * Milliseconds until the next wallpaper should be fetched.
 *
 * Zero means "now". A missing timestamp (first run, or "Next wallpaper now") is due
 * immediately, and a clock that moved backwards is also treated as due so the schedule
 * repairs itself instead of waiting for a negative interval.
 *
 * Kept free of Android types so the edge cases can be exercised by a plain unit test.
 */
internal fun wallpaperDelayMs(nowMs: Long, lastChangeAtMs: Long, intervalMs: Long): Long {
    if (intervalMs <= 0L) return 0L
    if (lastChangeAtMs <= 0L || nowMs < lastChangeAtMs) return 0L
    return (intervalMs - (nowMs - lastChangeAtMs)).coerceAtLeast(0L)
}
