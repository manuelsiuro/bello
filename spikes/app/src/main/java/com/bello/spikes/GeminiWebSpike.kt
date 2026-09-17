package com.bello.spikes

import android.annotation.SuppressLint
import android.content.Intent
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/** SP-02: Drive gemini.google.com (signed out) inside an app WebView. */
class GeminiWebSpike(private val act: MainActivity) : Spike {
    private val id = "sp02"
    private var web: WebView? = null
    private val metrics = Metrics(act, id, 10000)
    private val sentAt = HashMap<String, Long>()
    private var seq = 0
    private var pendingInject: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun command(action: String, intent: Intent) {
        when (action) {
            "load" -> {
                val w = web ?: WebView(act).also { v ->
                    v.settings.javaScriptEnabled = true
                    v.settings.domStorageEnabled = true
                    v.settings.databaseEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
                    if (intent.getStringExtra("ua") == "chrome") {
                        v.settings.userAgentString = v.settings.userAgentString
                            .replace("; wv", "").replace(Regex("Version/\\S+ "), "")
                    }
                    v.addJavascriptInterface(Bridge(), "Bello")
                    v.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            Report.log(id, "PAGE_FINISHED url=$url")
                            pendingInject?.let { view.evaluateJavascript(it, null) }
                            pendingInject = null
                        }
                    }
                    web = v
                }
                Report.log(id, "UA ${w.settings.userAgentString}")
                act.show(w)
                metrics.start()
                w.loadUrl(intent.getStringExtra("url") ?: "https://gemini.google.com/app?hl=fr")
            }
            "ask" -> {
                val w = web ?: return Report.log(id, "NOT_LOADED")
                val prompt = intent.getStringExtra("prompt") ?: "Bonjour"
                val reqId = "r${++seq}"
                sentAt[reqId] = SystemClock.elapsedRealtime()
                val js = act.assets.open("gemini/gemini.js").bufferedReader().readText()
                val spa = intent.getBooleanExtra("newChat", false)
                val inject = "$js\n;bello.ask(${JSONObject.quote(prompt)}, '$reqId', $spa);"
                val fresh = intent.getBooleanExtra("fresh", false)
                Report.log(id, "ASK id=$reqId fresh=$fresh newChat=$spa prompt=$prompt")
                if (fresh) {
                    pendingInject = inject
                    w.loadUrl("https://gemini.google.com/app?hl=fr")
                } else {
                    w.evaluateJavascript(inject, null)
                }
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
        fun log(msg: String) = Report.log(id, "JS $msg")

        @JavascriptInterface
        fun result(reqId: String, json: String) {
            val ms = sentAt.remove(reqId)?.let { SystemClock.elapsedRealtime() - it } ?: -1
            val o = runCatching { JSONObject(json) }.getOrElse { JSONObject().put("error", json) }
            if (o.has("error")) Report.log(id, "ANSWER_FAIL id=$reqId ms=$ms error=${o.optString("error")}")
            else Report.log(id, "ANSWER_OK id=$reqId ms=$ms firstTokenMs=${o.optInt("firstTokenMs")} chars=${o.optString("text").length} text=${o.optString("text").replace("\n", " ⏎ ")}")
        }
    }
}
