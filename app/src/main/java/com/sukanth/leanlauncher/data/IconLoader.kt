package com.sukanth.leanlauncher.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import com.sukanth.leanlauncher.R
import java.util.concurrent.Executors

/**
 * Loads tile icons off the main thread into a small shared cache.
 *
 * Requests carry the tile key as the ImageView tag; the result is applied only if the tag
 * still matches, so a recycled tile never shows another app's icon.
 */
class IconLoader(context: Context, private val cache: IconCache = IconCache.get()) {

    private val packageManager: PackageManager =
        context.applicationContext.packageManager
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "icon-loader").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun load(entry: AppEntry, targetPx: Int, imageView: ImageView) {
        val key = entry.key
        imageView.setTag(R.id.tag_icon_key, key)

        cache.get(key)?.let {
            imageView.setImageBitmap(it)
            return
        }

        imageView.setImageBitmap(null)
        executor.execute {
            val bitmap = decode(entry, targetPx)
            if (bitmap != null) cache.put(key, bitmap)
            mainHandler.post {
                if (imageView.getTag(R.id.tag_icon_key) == key && bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    fun shutdown() = executor.shutdownNow()

    private fun decode(entry: AppEntry, targetPx: Int): Bitmap? {
        val drawable = try {
            packageManager.getActivityIcon(entry.component)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } ?: try {
            packageManager.getApplicationIcon(entry.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } ?: return null
        return drawableToBitmap(drawable, targetPx)
    }

    // Drawable.opacity is deprecated, but it is the only way to learn whether an icon has
    // transparency before a bitmap is allocated for it. A drawable that cannot answer
    // reports TRANSLUCENT, so the miss costs an ARGB_8888 tile and never a dark fringe.
    @Suppress("DEPRECATION")
    private fun drawableToBitmap(drawable: Drawable, targetPx: Int): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val source = drawable.bitmap
            if (source.width == targetPx && source.height == targetPx) return source
            return Bitmap.createScaledBitmap(source, targetPx, targetPx, true)
        }

        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: targetPx
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: targetPx
        val scale = minOf(
            targetPx.toFloat() / width,
            targetPx.toFloat() / height,
            1f,
        )
        val outputWidth = (width * scale).toInt().coerceAtLeast(1)
        val outputHeight = (height * scale).toInt().coerceAtLeast(1)

        // Opaque icons drop to RGB_565, which halves their memory: ~40 KiB instead of
        // ~81 KiB per tile. Icons with transparency keep ARGB_8888 or they would show dark
        // fringes around antialiased edges. Opacity is asked of the drawable rather than of
        // a BitmapDrawable alone, so adaptive, vector and layer icons can qualify too; a
        // drawable that cannot answer reports TRANSLUCENT and simply keeps ARGB_8888.
        val config = if (drawable.opacity == PixelFormat.OPAQUE) {
            Bitmap.Config.RGB_565
        } else {
            Bitmap.Config.ARGB_8888
        }
        val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, config)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, outputWidth, outputHeight)
        drawable.draw(canvas)
        return bitmap
    }
}