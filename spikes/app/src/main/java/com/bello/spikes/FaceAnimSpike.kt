package com.bello.spikes

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

/** SP-06: Minion-style face animation in WebView 95, measuring fps. */
class FaceAnimSpike(private val act: MainActivity) : Spike {
    private val id = "sp06"
    private var web: WebView? = null
    private val metrics = Metrics(act, id)

    @SuppressLint("SetJavaScriptEnabled")
    override fun command(action: String, intent: Intent) {
        when (action) {
            "start" -> {
                val w = web ?: WebView(act).also { v ->
                    v.settings.javaScriptEnabled = true
                    v.webViewClient = WebViewClient()
                    v.addJavascriptInterface(Bridge(), "Bello")
                    v.loadUrl("file:///android_asset/face/index.html")
                    web = v
                }
                act.show(w)
                metrics.start()
                Report.log(id, "STARTED")
            }
            "state" -> {
                val s = intent.getStringExtra("state") ?: "idle"
                web?.evaluateJavascript("setState('$s')", null)
                Report.log(id, "STATE $s")
            }
            "stop" -> {
                web?.let { act.hide(it); it.destroy() }
                web = null
                metrics.stop()
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun fps(report: String) = Report.log(id, "FPS $report")
    }
}
