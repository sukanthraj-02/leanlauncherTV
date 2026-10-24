package com.sukanth.leanlauncher.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.AttributeSet
import android.widget.TextView
import java.util.Calendar

/**
 * Clock that ticks once a minute and only while started.
 *
 * Aligns each repaint to the next minute boundary so the displayed time never lags,
 * without paying for a per-second timer.
 */
class ClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextView(context, attrs, defStyleAttr) {

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            text = currentTime()
            handler.postDelayed(this, millisUntilNextMinute())
        }
    }

    fun start() {
        if (running) return
        running = true
        text = currentTime()
        handler.postDelayed(tick, millisUntilNextMinute())
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun currentTime(): CharSequence =
        DateFormat.getTimeFormat(context).format(Calendar.getInstance().time)

    private fun millisUntilNextMinute(): Long {
        val now = System.currentTimeMillis()
        return 60_000L - (now % 60_000L)
    }
}