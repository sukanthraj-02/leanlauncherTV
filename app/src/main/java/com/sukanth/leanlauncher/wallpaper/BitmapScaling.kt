package com.sukanth.leanlauncher.wallpaper

/**
 * Largest power-of-two sample size that still leaves the decoded image at least as large
 * as the screen. Sampling only by powers of two keeps decoding fast and avoids the
 * fractional resizing BitmapFactory would otherwise do.
 */
internal fun sampleSizeFor(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
    var sample = 1
    while (
        sourceWidth / (sample * 2) >= targetWidth &&
        sourceHeight / (sample * 2) >= targetHeight
    ) {
        sample *= 2
    }
    return sample
}
