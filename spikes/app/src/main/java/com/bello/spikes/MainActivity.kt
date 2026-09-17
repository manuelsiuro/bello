package com.bello.spikes

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Command-driven spike host. Drive from the Mac with:
 *   adb shell am start -n com.bello.spikes/.MainActivity --es spike sp01 --es action listen
 */
class MainActivity : Activity() {
    lateinit var container: FrameLayout
    lateinit var status: TextView

    private val spikes by lazy {
        mapOf(
            "sp01" to SpeechSpike(this),
            "sp02" to GeminiWebSpike(this),
            "sp03" to VoskSpike(this),
            "sp04" to TlsSpike(this),
            "sp05" to CameraSpike(this),
            "sp06" to FaceAnimSpike(this),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Report.init(this)
        WebView.setWebContentsDebuggingEnabled(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        container = FrameLayout(this)
        status = TextView(this).apply { textSize = 22f; setPadding(24, 24, 24, 24); text = "Bello Spikes ready" }
        container.addView(status)
        setContentView(container)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val id = intent?.getStringExtra("spike") ?: return
        val action = intent.getStringExtra("action") ?: "start"
        val spike = spikes[id] ?: return Report.log("host", "unknown spike $id")
        status.text = "$id / $action"
        Report.log(id, "CMD action=$action extras=${intent.extras?.keySet()?.joinToString()}")
        runCatching { spike.command(action, intent) }
            .onFailure { Report.log(id, "CMD_ERROR ${it.javaClass.simpleName}: ${it.message}") }
    }

    fun show(view: View) {
        if (view.parent == null) container.addView(view, 0, FrameLayout.LayoutParams(-1, -1))
        view.bringToFront()
        status.bringToFront()
    }

    fun hide(view: View) {
        container.removeView(view)
    }

    override fun onDestroy() {
        spikes.values.forEach { runCatching { it.command("stop", Intent()) } }
        super.onDestroy()
    }
}

interface Spike {
    fun command(action: String, intent: Intent)
}
