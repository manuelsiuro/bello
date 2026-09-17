package com.bello.assistant.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import com.bello.assistant.core.Diagnostics

/**
 * Phase 0 placeholder: runs the platform self-check and shows the results.
 * Replaced by the face UI in Phase 1. Re-run from the Mac with scripts/selfcheck.sh.
 */
class MainActivity : Activity() {
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        output = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(29, 43, 58))
            addView(output)
        })
        runSelfCheck()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SELFCHECK, false)) runSelfCheck()
    }

    private fun runSelfCheck() {
        output.text = "Bello — auto-diagnostic en cours…"
        Thread({
            val checks = Diagnostics(applicationContext).run()
            val text = buildString {
                append("Bello — auto-diagnostic ${checks.count { it.ok }}/${checks.size}\n\n")
                checks.forEach { append(if (it.ok) "✔ " else "✘ ").append(it.name).append("\n    ").append(it.detail).append("\n") }
            }
            runOnUiThread { output.text = text }
        }, "selfcheck").start()
    }

    companion object {
        const val EXTRA_SELFCHECK = "selfcheck"
    }
}
