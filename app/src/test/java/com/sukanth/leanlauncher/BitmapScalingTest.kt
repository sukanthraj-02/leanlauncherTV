package com.sukanth.leanlauncher

import com.sukanth.leanlauncher.wallpaper.sampleSizeFor
import org.junit.Assert.assertEquals
import org.junit.Test

class BitmapScalingTest {

    @Test
    fun `a screen sized image is not downsampled`() {
        assertEquals(1, sampleSizeFor(1280, 720, 1280, 720))
    }

    @Test
    fun `a smaller image is not upsampled`() {
        assertEquals(1, sampleSizeFor(640, 360, 1280, 720))
    }

    @Test
    fun `a 4k image is halved`() {
        assertEquals(2, sampleSizeFor(3840, 2160, 1280, 720))
    }

    @Test
    fun `a 4k image is halved for a 1080p screen`() {
        assertEquals(2, sampleSizeFor(3840, 2160, 1920, 1080))
    }

    @Test
    fun `sampling keeps at least one target dimension`() {
        // 1000 / 2 = 500, which is below the 640 target width, so no sampling is safe.
        assertEquals(1, sampleSizeFor(1000, 700, 640, 360))
    }

    @Test
    fun `invalid dimensions fall back to full size`() {
        assertEquals(1, sampleSizeFor(0, 0, 1280, 720))
        assertEquals(1, sampleSizeFor(1920, 1080, 0, 0))
    }
}
