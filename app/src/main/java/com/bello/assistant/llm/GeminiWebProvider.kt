package com.bello.assistant.llm

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.bello.assistant.core.FileLog
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Experimental, key-free provider (FR-GWEB-*): drives gemini.google.com signed out, in a WebView
 * hidden behind the face, by injecting [SCRIPT] (SP-02). Disabled by default.
 *
 * SP-02 findings this relies on: a new chat per question (follow-ups in the same chat hang), a
 * watchdog reload when nothing comes back, and text taken from the markdown panel only.
 *
 * The page is loaded only around a question: left open it keeps this tablet at ~48 % CPU and
 * ~190 MB, against a 12 % idle budget. Loading costs about 6 s before the first question.
 */
class GeminiWebProvider(
    private val context: Context,
    private val url: String = "https://gemini.google.com/app?hl=fr",
) : LlmProvider {

    override val id = "geminiweb"
    override val model = "gemini-web"

    private val main = Handler(Looper.getMainLooper())
    private val answers = ConcurrentHashMap<String, ArrayBlockingQueue<String>>()
    @Volatile private var web: WebView? = null
    @Volatile private var pageLoaded = false
    @Volatile private var host: ViewGroup? = null
    @Volatile private var warmingUp = false
    @Volatile private var unhealthyUntil = 0L
    private var seq = 0

    /**
     * The page needs a real view tree for its own layout checks, so it lives behind the face and
     * follows the activity: attached when the face is created, released when it goes away.
     */
    fun attach(next: ViewGroup?) {
        if (next == null) {
            releaseView()
            host = null
            return
        }
        if (host === next) return
        releaseView()
        host = next
        warmUp()
    }

    /**
     * Health check (FR-GWEB-06): the page must load *and* accept a question. A page that loads but
     * cannot be driven (redirect, sign-in wall, captcha) counts as a failure, and the provider is
     * skipped until the next check rather than costing 20 s on every question.
     */
    fun warmUp() {
        if (warmingUp) return
        warmingUp = true
        Thread({
            val loaded = runCatching { ensureLoaded(LOAD_TIMEOUT_MS) }.getOrDefault(false)
            val drivable = loaded && runCatching { probe() }.getOrDefault(false)
            unhealthyUntil = if (drivable) 0 else SystemClock.elapsedRealtime() + UNHEALTHY_MS
            warmingUp = false
            FileLog.i(TAG, "health check: " + when {
                drivable -> "page ready"
                loaded -> "page loaded but cannot be driven — skipped for ${UNHEALTHY_MS / 60_000} min"
                else -> "page unreachable — skipped for ${UNHEALTHY_MS / 60_000} min"
            })
            scheduleRelease()
        }, "geminiweb-health").start()
    }

    /** The editor appears a couple of seconds after the page settles, so keep asking. */
    private fun probe(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + PROBE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (probeOnce()) return true
            Thread.sleep(POLL_MS)
        }
        return false
    }

    /** Asks the injected script whether the page can take a question right now. */
    private fun probeOnce(): Boolean {
        val answer = ArrayBlockingQueue<String>(1)
        main.post {
            val view = web
            if (view == null) answer.offer("none")
            else view.evaluateJavascript(
                "${loadScript()}\n;String(!!(window.bello && bello.ready && bello.ready()));"
            ) { value -> answer.offer(value ?: "null") }
        }
        return answer.poll(PROBE_STEP_MS, TimeUnit.MILLISECONDS)?.contains("true") == true
    }

    /** The loaded page is far too expensive to keep around between questions. */
    private val release = Runnable {
        if (web != null) FileLog.i(TAG, "idle: releasing the page")
        releaseView()
    }

    private fun scheduleRelease() {
        main.removeCallbacks(release)
        main.postDelayed(release, IDLE_RELEASE_MS)
    }

    /** False while the last health check says the page cannot be driven (FR-GWEB-06). */
    override fun isReady(): Boolean = SystemClock.elapsedRealtime() >= unhealthyUntil

    @Synchronized
    override fun complete(request: LlmRequest): LlmResult = try {
        ask(request)
    } finally {
        scheduleRelease()
    }

    private fun ask(request: LlmRequest): LlmResult {
        main.removeCallbacks(release)
        if (!ensureLoaded(LOAD_TIMEOUT_MS)) {
            return LlmResult.Failed(FailureKind.NETWORK, "page did not load")
        }
        val reqId = "r${++seq}"
        val queue = ArrayBlockingQueue<String>(1)
        answers[reqId] = queue
        val prompt = promptOf(request)
        val started = SystemClock.elapsedRealtime()
        main.post {
            val script = "$SCRIPT_MARK${loadScript()}\n;bello.ask(${JSONObject.quote(prompt)}, ${JSONObject.quote(reqId)}, true);"
            web?.evaluateJavascript(script, null)
        }
        val json = queue.poll(ANSWER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        answers.remove(reqId)
        if (json == null) {
            FileLog.w(TAG, "no answer in ${ANSWER_TIMEOUT_MS} ms — reloading the page")
            reload()
            return LlmResult.Failed(FailureKind.TIMEOUT, "no answer in ${ANSWER_TIMEOUT_MS / 1000} s")
        }
        return parseAnswer(json, SystemClock.elapsedRealtime() - started)
    }

    /** The web UI has no system role, so persona and question travel together. */
    private fun promptOf(request: LlmRequest): String {
        val history = request.messages.joinToString("\n") {
            if (it.role == "user") "Question : ${it.content}" else "Ta réponse précédente : ${it.content}"
        }
        return "${request.system}\n\n$history"
    }

    private fun parseAnswer(json: String, ms: Long): LlmResult {
        val o = runCatching { JSONObject(json) }.getOrNull()
            ?: return LlmResult.Failed(FailureKind.EMPTY, "bad bridge payload")
        val error = o.optString("error")
        if (error.isNotEmpty()) {
            val kind = when {
                error.contains("blocked", true) || error.contains("captcha", true) -> FailureKind.BLOCKED
                error.contains("timeout", true) -> FailureKind.TIMEOUT
                else -> FailureKind.NETWORK
            }
            if (kind != FailureKind.BLOCKED) reload()
            return LlmResult.Failed(kind, error)
        }
        val text = o.optString("text").trim()
        if (text.isEmpty()) return LlmResult.Failed(FailureKind.EMPTY, "nothing speakable in the answer")
        FileLog.i(TAG, "answer chars=${text.length} firstTokenMs=${o.optInt("firstTokenMs")} totalMs=$ms")
        return LlmResult.Ok(text, model, ms)
    }

    // --- WebView plumbing ------------------------------------------------------------------------

    /**
     * Waits for the page, polling instead of waiting on a signal: `onPageFinished` can fire more
     * than once (the app is a single-page app) or not at all, and a missed signal must not leave
     * the assistant waiting for nothing.
     */
    private fun ensureLoaded(timeoutMs: Long): Boolean {
        if (pageLoaded && web != null) return true
        main.post {
            val view = web ?: create().also { web = it }
            view.loadUrl(url)
            FileLog.i(TAG, "loading $url")
        }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (pageLoaded) return true
            Thread.sleep(POLL_MS)
        }
        FileLog.w(TAG, "page load timed out after $timeoutMs ms")
        return false
    }

    private fun reload() {
        pageLoaded = false
        main.post { web?.loadUrl(url) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView {
        val view = WebView(context)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.databaseEnabled = true
        // Gemini refuses the stock WebView user agent; SP-02 used a Chrome-looking one.
        view.settings.userAgentString = view.settings.userAgentString
            .replace("; wv", "").replace(Regex("Version/\\S+ "), "")
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.addJavascriptInterface(Bridge(), "BelloWeb")
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(page: WebView, finishedUrl: String) {
                pageLoaded = true
                FileLog.i(TAG, "page finished $finishedUrl")
            }
        }
        // Behind the face (index 0): the page needs a real size for its layout checks, but the
        // opaque face covers it. Without a host it still runs, only less reliably.
        host?.addView(view, 0, ViewGroup.LayoutParams(MATCH, MATCH))
        return view
    }

    /** The script lives on the device when pushed, in the APK otherwise (FR-GWEB-04). */
    private fun loadScript(): String {
        val override = File(context.getExternalFilesDir(null), "gemini.js")
        return runCatching {
            if (override.isFile) override.readText()
            else context.assets.open("gemini/gemini.js").bufferedReader().use { it.readText() }
        }.getOrElse {
            FileLog.w(TAG, "cannot read gemini.js", it)
            ""
        }
    }

    /** FR-GWEB-10: give the page's memory back when the system asks. */
    fun onTrimMemory(level: Int) {
        if (level < TRIM_LEVEL) return
        FileLog.i(TAG, "releasing the hidden WebView (trim level $level)")
        releaseView()
    }

    private fun releaseView() {
        main.post {
            web?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
            web = null
            pageLoaded = false
        }
    }

    override fun close() = releaseView()

    private inner class Bridge {
        @JavascriptInterface
        fun log(message: String) = FileLog.i(TAG, "js $message")

        @JavascriptInterface
        fun result(reqId: String, json: String) {
            answers[reqId]?.offer(json) ?: FileLog.w(TAG, "late answer for $reqId")
        }
    }

    private companion object {
        const val TAG = "geminiweb"
        const val LOAD_TIMEOUT_MS = 40_000L
        const val ANSWER_TIMEOUT_MS = 75_000L
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val TRIM_LEVEL = 40 // TRIM_MEMORY_BACKGROUND
        const val POLL_MS = 200L
        const val IDLE_RELEASE_MS = 90_000L
        const val PROBE_TIMEOUT_MS = 25_000L
        const val PROBE_STEP_MS = 5_000L
        const val UNHEALTHY_MS = 30 * 60_000L
        const val SCRIPT_MARK = "/* bello gemini automation */\n"
    }
}
