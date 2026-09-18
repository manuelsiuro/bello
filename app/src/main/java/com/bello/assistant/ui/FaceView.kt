package com.bello.assistant.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.bello.assistant.core.FileLog

/**
 * The Minion face: a WebView showing local assets/face/index.html.
 * Calls made before the page is ready are queued and flushed on `onReady`.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class FaceView(context: Context, private val listener: Listener) : WebView(context) {

    interface Listener {
        fun onFaceReady()
        fun onFaceTap()
        fun onFaceLongPress()
    }

    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayList<String>()
    private var ready = false

    init {
        setBackgroundColor(Color.rgb(29, 43, 58))
        settings.javaScriptEnabled = true
        settings.allowFileAccess = true
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        webViewClient = WebViewClient()
        addJavascriptInterface(Bridge(), "BelloNative")
        loadUrl("file:///android_asset/face/index.html")
    }

    fun setState(state: FaceState) = run(FaceScript.call("setState", state.js))

    /** Expression asked for by the model, shown on top of the current state (FR-FACE-04). */
    fun setEmotion(emotion: FaceState?) = run(FaceScript.call("setEmotion", emotion?.js ?: ""))

    /** Running timer, shown under the clock; empty hides it (FR-TOOL-02). */
    fun showCountdown(text: String) = run(FaceScript.call("showCountdown", text))

    /** A quiet corner note while there is no network (NFR-REL-02). */
    fun showOffline(offline: Boolean) = run("bello.showOffline($offline);")

    fun showUser(text: String) = run(FaceScript.call("showUser", text))

    fun showAnswer(text: String) = run(FaceScript.call("showAnswer", text))

    fun clearSubtitles() = run(FaceScript.call("clearSubtitles"))

    private fun run(script: String) = main.post {
        if (ready) evaluateJavascript(script, null) else pending += script
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onReady() = main.post {
            ready = true
            pending.forEach { evaluateJavascript(it, null) }
            pending.clear()
            listener.onFaceReady()
        }

        @JavascriptInterface
        fun onTap() = main.post { listener.onFaceTap() }

        @JavascriptInterface
        fun onLongPress() = main.post { listener.onFaceLongPress() }

        @JavascriptInterface
        fun log(message: String) = FileLog.i("face", message)
    }
}
