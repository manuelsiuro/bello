package com.bello.assistant.ui

import android.content.Context
import android.graphics.Color
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView

/**
 * Corner read-out for debugging on the tablet (FR-DIAG-02): conversation state, which provider
 * answered and how fast, memory. Off by default; `scripts/overlay.sh on` turns it on.
 */
class DebugOverlay(context: Context, private val lines: () -> List<String>) : TextView(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (visibility != View.VISIBLE) return
            text = lines().joinToString("\n")
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        visibility = View.GONE
        textSize = 11f
        setTextColor(Color.argb(190, 180, 255, 180))
        setBackgroundColor(Color.argb(120, 0, 0, 0))
        setPadding(10, 6, 10, 6)
    }

    fun show(on: Boolean) {
        visibility = if (on) View.VISIBLE else View.GONE
        handler.removeCallbacks(tick)
        if (on) handler.post(tick)
    }

    val isOn get() = visibility == View.VISIBLE

    companion object {
        private const val REFRESH_MS = 2_000L
        fun memoryLine(): String = "mem ${Debug.getPss() / 1024} MB"
    }
}
