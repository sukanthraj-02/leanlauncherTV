package com.sukanth.leanlauncher.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.sukanth.leanlauncher.data.Prefs
import com.sukanth.leanlauncher.data.WallpaperSource
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.random.Random

/**
 * Owns the optional photo wallpaper and its refresh timer.
 *
 * Three sources are supported: Wallhaven's General category (its People category is
 * excluded), images chosen through Android's document picker, and images discovered on a
 * user-supplied web page. Remote images are decoded straight from a bounded response into
 * one RGB_565 bitmap. Nothing is written to storage: replacing the image drops the old
 * bitmap, so the process only ever holds one screen-sized bitmap.
 *
 * Downloads run only while [start] has been called and [stop] has not. The home activity
 * drives those from onStart/onStop, so a backgrounded launcher never touches the network.
 */
internal class WallpaperController(
    context: Context,
    private val prefs: Prefs,
    private val onImage: (Bitmap) -> Unit,
    private val onClear: () -> Unit,
) {
    private val appContext = context.applicationContext

    private val metrics = appContext.resources.displayMetrics
    private val targetWidth = metrics.widthPixels.coerceIn(MIN_WIDTH, MAX_WIDTH)
    private val targetHeight = metrics.heightPixels.coerceIn(MIN_HEIGHT, MAX_HEIGHT)

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wallpaper").apply { isDaemon = true }
    }

    init {
        // Earlier builds kept the photo on disk. Remove any leftover file once.
        appContext.cacheDir.listFiles()
            ?.filter { it.name.startsWith(LEGACY_CACHE_PREFIX) }
            ?.forEach { it.delete() }
    }

    @Volatile
    private var visible = false

    /** Bumped on every start/stop so a result from an earlier visible period is dropped. */
    private var generation = 0

    private var displayed = false
    private var loading = false
    private var pending: Future<*>? = null

    private var localCursor = 0
    private var webPageUrl: String? = null
    private var webImages: List<String> = emptyList()
    private var webCursor = 0

    private val refresh = Runnable { download() }

    fun start() {
        if (visible) return
        visible = true
        generation++
        if (!prefs.wallpaperEnabled) {
            handler.removeCallbacks(refresh)
            clear()
            return
        }
        updateSchedule()
    }

    fun stop() {
        if (!visible) return
        visible = false
        generation++
        handler.removeCallbacks(refresh)
        pending?.cancel(true)
        pending = null
        loading = false
    }

    fun shutdown() {
        stop()
        worker.shutdownNow()
    }

    private fun updateSchedule() {
        handler.removeCallbacks(refresh)
        if (!visible || !prefs.wallpaperEnabled) return
        // With no disk cache a new process has nothing to show, so its first visit is
        // always due. Within one process the normal interval applies.
        val delay = if (displayed) {
            wallpaperDelayMs(
                nowMs = System.currentTimeMillis(),
                lastChangeAtMs = prefs.lastWallpaperChangeAt,
                intervalMs = prefs.wallpaperIntervalMinutes.toLong() * MINUTE_MS,
            )
        } else {
            0L
        }
        if (delay == 0L) {
            download()
        } else {
            handler.postDelayed(refresh, delay)
        }
    }

    private fun download() {
        if (!visible || !prefs.wallpaperEnabled || loading) return
        loading = true
        val expected = generation
        pending = worker.submit {
            val bitmap = fetchBitmap()
            handler.post {
                if (expected != generation) {
                    bitmap?.recycle()
                    return@post
                }
                loading = false
                if (!visible || !prefs.wallpaperEnabled) {
                    bitmap?.recycle()
                    return@post
                }
                if (bitmap == null) {
                    // Keep whatever is already on screen and try again later. The retry is
                    // scheduled directly because a zero-delay reschedule would spin.
                    handler.postDelayed(refresh, RETRY_MS)
                    return@post
                }
                prefs.lastWallpaperChangeAt = System.currentTimeMillis()
                displayed = true
                onImage(bitmap)
                updateSchedule()
            }
        }
    }

    private fun fetchBitmap(): Bitmap? = when (prefs.wallpaperSource) {
        WallpaperSource.BUILTIN -> fetchBuiltinBitmap()
        WallpaperSource.LOCAL -> fetchLocalBitmap()
        WallpaperSource.WEB -> fetchWebBitmap()
    }

    /**
     * Wallhaven's categories are bit flags: 100 is General only, so its People and Anime
     * categories never enter the result. SFW is the only purity requested.
     */
    private fun fetchBuiltinBitmap(): Bitmap? {
        val searchUrl = "https://wallhaven.cc/api/v1/search" +
            "?categories=100&purity=100&sorting=random" +
            "&atleast=${targetWidth}x${targetHeight}&ratios=16x9"
        val body = fetchText(searchUrl, MAX_PAGE_BYTES, "application/json") ?: return null
        val data = runCatching { JSONObject(body).optJSONArray("data") }.getOrNull() ?: return null
        // Only the full-size image. Wallhaven's thumbnails are around 432x243, which
        // become visibly soft when stretched to a 720p or 1080p screen.
        val urls = buildList {
            for (index in 0 until minOf(data.length(), BUILTIN_CANDIDATES)) {
                val item = data.optJSONObject(index) ?: continue
                item.optString("path")
                    .takeIf(::isHttpUrl)
                    ?.let(::add)
            }
        }.distinct()
        for (url in urls.shuffled()) {
            fetchRemoteBitmap(url)?.let { return it }
        }
        return null
    }

    private fun fetchLocalBitmap(): Bitmap? {
        val uris = prefs.localWallpaperUris
        if (uris.isEmpty()) return null
        val start = localCursor
        repeat(minOf(uris.size, MAX_ATTEMPTS)) { offset ->
            val index = (start + offset) % uris.size
            val bytes = readLocalBytes(uris[index]) ?: return@repeat
            val bitmap = decode(bytes) ?: return@repeat
            localCursor = (index + 1) % uris.size
            return bitmap
        }
        return null
    }

    private fun fetchWebBitmap(): Bitmap? {
        val page = prefs.wallpaperWebUrl ?: return null
        if (page != webPageUrl || webImages.isEmpty()) {
            val images = fetchWebImages(page) ?: return null
            if (images.isEmpty()) return null
            webPageUrl = page
            webImages = images
            webCursor = Random.nextInt(images.size)
        }
        val images = webImages
        val start = webCursor
        repeat(minOf(images.size, MAX_ATTEMPTS)) { offset ->
            val index = (start + offset) % images.size
            val bitmap = fetchRemoteBitmap(images[index]) ?: return@repeat
            webCursor = (index + 1) % images.size
            return bitmap
        }
        // Rebuild the listing next time instead of retrying the same dead addresses.
        webPageUrl = null
        webImages = emptyList()
        return null
    }

    private fun fetchWebImages(url: String): List<String>? {
        val connection = openConnection(
            url,
            "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        ) ?: return null
        return try {
            if (connection.responseCode !in 200..299) return null
            val contentType = connection.contentType
            val finalUrl = connection.url.toString()
            if (WebWallpaper.isImageContentType(contentType) ||
                (
                    WebWallpaper.looksLikeImageUrl(finalUrl) &&
                        !WebWallpaper.isMarkupContentType(contentType)
                    )
            ) {
                return listOf(finalUrl)
            }
            if (!WebWallpaper.isMarkupContentType(contentType)) return null
            val bytes = readBounded(connection.inputStream, MAX_PAGE_BYTES) ?: return null
            val charset = charsetOf(contentType)
            val text = runCatching { String(bytes, charset) }
                .getOrElse { String(bytes, Charsets.UTF_8) }
            WebWallpaper.extractImageUrls(text, finalUrl)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchRemoteBitmap(url: String): Bitmap? {
        val connection = openConnection(url, "image/*") ?: return null
        return try {
            if (connection.responseCode !in 200..299) return null
            val contentType = connection.contentType
            if (
                contentType != null &&
                !WebWallpaper.isImageContentType(contentType) &&
                WebWallpaper.isMarkupContentType(contentType)
            ) {
                return null
            }
            val bytes = readBounded(connection.inputStream, MAX_IMAGE_BYTES) ?: return null
            decode(bytes)
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchText(url: String, maxBytes: Int, accept: String): String? {
        val connection = openConnection(url, accept) ?: return null
        return try {
            if (connection.responseCode !in 200..299) return null
            val bytes = readBounded(connection.inputStream, maxBytes) ?: return null
            String(bytes, charsetOf(connection.contentType))
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun readLocalBytes(uri: String): ByteArray? = runCatching {
        appContext.contentResolver.openInputStream(Uri.parse(uri))?.use {
            readBounded(it, MAX_IMAGE_BYTES)
        }
    }.getOrNull()

    private fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val sample = sampleSizeFor(
                sourceWidth = bounds.outWidth,
                sourceHeight = bounds.outHeight,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
            )
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull()
    }

    private fun openConnection(url: String, accept: String): HttpURLConnection? {
        val parsed = runCatching { URL(url.replace(" ", "%20")) }.getOrNull() ?: return null
        if (parsed.protocol.lowercase() !in setOf("http", "https")) return null
        return runCatching {
            (parsed.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", accept)
                // Byte counts must match the bounded read below.
                setRequestProperty("Accept-Encoding", "identity")
            }
        }.getOrNull()
    }

    /** Reads at most [maxBytes], returning null rather than buffering an oversized file. */
    private fun readBounded(stream: InputStream, maxBytes: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(maxBytes, INITIAL_BUFFER_BYTES))
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            total += read
            if (total > maxBytes) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun charsetOf(contentType: String?): Charset {
        val name = contentType
            ?.substringAfter("charset=", "")
            ?.substringBefore(';')
            ?.trim()
            ?.trim('"', '\'')
        if (name.isNullOrBlank()) return Charsets.UTF_8
        return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)

    private fun clear() {
        displayed = false
        onClear()
    }

    private companion object {
        const val LEGACY_CACHE_PREFIX = "wallpaper.cache"
        const val USER_AGENT = "leanlauncherTV/1.0"
        const val MINUTE_MS = 60_000L
        const val RETRY_MS = 60_000L
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val MIN_WIDTH = 640
        const val MIN_HEIGHT = 360
        const val MAX_WIDTH = 1920
        const val MAX_HEIGHT = 1080
        const val MAX_PAGE_BYTES = 2_000_000
        const val MAX_IMAGE_BYTES = 16_000_000
        const val INITIAL_BUFFER_BYTES = 64 * 1024
        const val COPY_BUFFER_BYTES = 16 * 1024
        const val MAX_ATTEMPTS = 4
        const val BUILTIN_CANDIDATES = 8
    }
}
