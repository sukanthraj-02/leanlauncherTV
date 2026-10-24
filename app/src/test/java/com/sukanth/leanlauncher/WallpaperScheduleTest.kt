package com.sukanth.leanlauncher

import com.sukanth.leanlauncher.wallpaper.wallpaperDelayMs
import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperScheduleTest {

    @Test
    fun `never changed is due immediately`() {
        assertEquals(0L, wallpaperDelayMs(nowMs = 1_000L, lastChangeAtMs = 0L, intervalMs = 60_000L))
    }

    @Test
    fun `elapsed interval is due immediately`() {
        assertEquals(
            0L,
            wallpaperDelayMs(nowMs = 120_000L, lastChangeAtMs = 60_000L, intervalMs = 60_000L),
        )
    }

    @Test
    fun `overdue is due immediately`() {
        assertEquals(
            0L,
            wallpaperDelayMs(nowMs = 500_000L, lastChangeAtMs = 60_000L, intervalMs = 60_000L),
        )
    }

    @Test
    fun `partial interval returns the remaining time`() {
        assertEquals(
            45_000L,
            wallpaperDelayMs(nowMs = 75_000L, lastChangeAtMs = 60_000L, intervalMs = 60_000L),
        )
    }

    @Test
    fun `clock moving backwards is treated as due`() {
        assertEquals(
            0L,
            wallpaperDelayMs(nowMs = 30_000L, lastChangeAtMs = 60_000L, intervalMs = 60_000L),
        )
    }

    @Test
    fun `non positive interval is due immediately`() {
        assertEquals(0L, wallpaperDelayMs(nowMs = 1_000L, lastChangeAtMs = 0L, intervalMs = 0L))
    }
}
