package com.sukanth.leanlauncher.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import com.sukanth.leanlauncher.data.IconCache
import java.util.Locale

/**
 * Reports what this process is actually holding right now.
 *
 * Exists because "the launcher must be light" is not a number anyone can assert. The
 * important caveat: on any Android version the ART runtime itself, the app's code cache
 * and the window/graphics buffers dominate. This launcher can keep its *own* allocations
 * small, but no app on Android has a total footprint measured in kilobytes.
 *
 * The platform reports two different units here: `Debug` PSS readings are already in
 * kilobytes, while `Runtime` and the icon cache are in bytes. Formatting both with one
 * helper used to print a 512 KiB budget as "524288 KB".
 */
object MemoryProbe {

    fun rows(context: Context, iconCache: IconCache = IconCache.get()): List<Pair<String, String>> {
        val runtime = Runtime.getRuntime()
        val javaUsed = runtime.totalMemory() - runtime.freeMemory()

        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)

        return buildList {
            add("Total PSS" to kb(Debug.getPss().toLong()))
            add("  native heap" to kb(info.nativePss.toLong()))
            add("  dalvik heap" to kb(info.dalvikPss.toLong()))
            add("  graphics" to kb(info.getMemoryStat("summary.graphics")?.toLong() ?: 0L))
            add("Java heap used" to bytes(javaUsed))
            add("Java heap total" to bytes(runtime.totalMemory()))
            add("Java heap max" to bytes(runtime.maxMemory()))
            add("Native heap alloc" to bytes(Debug.getNativeHeapAllocatedSize()))
            add(
                "Icon cache" to
                    "${bytes(iconCache.usedBytes().toLong())} of ${bytes(iconCache.budgetBytes.toLong())}",
            )
            add("Icons cached" to iconCache.entryCount().toString())
            // activeCount avoids materialising a stack trace for every thread just to
            // count them, which on this screen would be a large transient allocation.
            add("Threads" to Thread.activeCount().toString())
            add("Low-memory device" to lowMemoryDevice(context).toString())
        }
    }

    private fun lowMemoryDevice(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ActivityManager::class.java)
        manager?.isLowRamDevice == true
    }.getOrDefault(false)

    /** Debug PSS and MemoryInfo values are kilobytes, not bytes. */
    private fun kb(kilobytes: Long): String =
        if (kilobytes >= 1024) String.format(Locale.US, "%.1f MB", kilobytes / 1024.0)
        else "$kilobytes KB"

    /** Heap measurements and cache sizes are bytes. */
    private fun bytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
