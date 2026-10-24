package com.sukanth.leanlauncher.data

import android.graphics.Bitmap
import android.util.LruCache
import java.util.concurrent.atomic.AtomicInteger

/**
 * Byte-budgeted icon cache, shared across the process.
 *
 * Sized in kilobytes rather than entry count because entry count is the wrong unit: a
 * 72dp tile at 2x is 144 px, which is ~81 KiB as ARGB_8888 and ~40 KiB as RGB_565, so
 * even a "small" cache of a few dozen icons would dwarf everything else the launcher
 * allocates. Icons are cheap to re-decode on a miss, so a small budget is the right
 * trade for a low-memory television.
 *
 * On API 26+ bitmap pixels live in native memory, so this budget is graphics PSS rather
 * than Java heap: the cache can be resized without changing the launcher's heap floor.
 */
class IconCache(budgetBytes: Int = DEFAULT_BUDGET_BYTES) {

    /**
     * Live entry count.
     *
     * [LruCache.size] reports the sum of `sizeOf`, i.e. bytes, so the count has to be
     * tracked separately. Reading it as `size()` used to report bytes and made the
     * diagnostics claim tens of thousands of cached icons.
     */
    private val entries = AtomicInteger()

    private val cache = object : LruCache<String, Bitmap>(budgetBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount

        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: Bitmap,
            newValue: Bitmap?,
        ) {
            // A replaced entry keeps its slot; only a real eviction removes one.
            if (evicted) entries.decrementAndGet()
        }
    }

    val budgetBytes: Int get() = cache.maxSize()

    /** Bytes currently held, for the memory read-out. */
    fun usedBytes(): Int = cache.size()

    fun entryCount(): Int = entries.get()

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        // An icon larger than the whole budget would evict everything else and still not fit.
        if (bitmap.allocationByteCount > budgetBytes) return
        if (cache.get(key) == null) entries.incrementAndGet()
        cache.put(key, bitmap)
    }

    /**
     * Drops every icon, for when the home screen is no longer visible.
     *
     * Releasing the references is all this can do: pixel memory is freed by the framework
     * on its next collection. Forcing one here (the old `System.gc()`) ran a stop-the-world
     * collection on the UI thread at the exact moment the device wanted memory back.
     */
    fun trim() {
        cache.evictAll()
    }

    /**
     * Keeps only [share] of the budget, most recently used icons first.
     *
     * Used while the launcher is still on screen, where emptying the cache would re-decode
     * every visible tile on the next key press.
     */
    fun trimToShare(share: Double) {
        cache.trimToSize((budgetBytes * share).toInt().coerceAtLeast(1))
    }

    companion object {
        /** Deliberately small. Raise it only if scrolling visibly stutters. */
        const val DEFAULT_BUDGET_BYTES = 512 * 1024

        @Volatile
        private var instance: IconCache? = null

        fun get(): IconCache = instance ?: synchronized(this) {
            instance ?: IconCache().also { instance = it }
        }
    }
}
