package com.bello.assistant.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.bello.assistant.assistant.Assistant
import com.bello.assistant.assistant.ConversationPolicy
import com.bello.assistant.assistant.Feature
import com.bello.assistant.assistant.Router
import com.bello.assistant.assistant.ToolReplies
import com.bello.assistant.assistant.Turn
import com.bello.assistant.core.AppConfig
import com.bello.assistant.core.ConfigIo
import com.bello.assistant.core.Diagnostics
import com.bello.assistant.core.FileLog
import com.bello.assistant.core.NightMode
import com.bello.assistant.core.Prefs
import com.bello.assistant.net.Connectivity
import com.bello.assistant.net.LocalAddress
import com.bello.assistant.net.PageStore
import com.bello.assistant.tools.PageHtml
import com.bello.assistant.presence.Presence
import com.bello.assistant.BelloApp
import com.bello.assistant.llm.LlmGateway
import com.bello.assistant.service.AssistantService
import com.bello.assistant.service.FaceVisibility

/**
 * Full-screen, always-on face (FR-ON-01/02/04, FR-FACE-*), with a text input bar (FR-CONV-03).
 *
 * adb extras (see scripts/): `selfcheck` (bool), `state` (face state), `text` (typed question),
 * `speak`, `tap` (bool), `voice` ("pitch,rate"), `kiosk` ("on"/"off"), `overlay` ("on"/"off"),
 * `llm` ("status"/"reload"), `wake`, `presence`, `night`, `settings`, `page` ("demo"/"status"/"off"),
 * `ring` (schedule id), `crash` (bool, debug builds).
 */
class MainActivity : Activity(), FaceView.Listener {
    private lateinit var face: FaceView
    private lateinit var assistant: Assistant
    private lateinit var inputBar: LinearLayout
    private lateinit var input: EditText
    private lateinit var prefs: Prefs
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var overlay: DebugOverlay
    private lateinit var root: FrameLayout
    private lateinit var gateway: LlmGateway
    private lateinit var router: Router
    private lateinit var settingsView: SettingsView
    private lateinit var presence: Presence
    /** null = follow the clock; true/false = forced, for testing night mode at ten in the morning. */
    private var nightOverride: Boolean? = null
    private var night = false
    private lateinit var network: Connectivity
    /** While a page's QR code is on the face, in elapsed-realtime ms; 0 when none (FR-PAGE-05). */
    private var pageShownUntil = 0L
    private val hidePageRunnable = Runnable { hidePage("timeout") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // Always-on: keep the screen lit, and after a reboot wake it and pass a non-secure keyguard.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)

        face = FaceView(this, this)
        root = FrameLayout(this)
        gateway = (application as BelloApp).gateway()
        // The hidden Gemini Web page (if enabled) sits behind the face, at index 0.
        gateway.attachWebHost(root)
        network = Connectivity(this) { online ->
            face.showOffline(!online)
            FileLog.i(TAG, "network=${if (online) "back" else "lost"}")
        }
        router = newRouter(gateway)
        assistant = Assistant(this, face, router, isNight = { night })
        assistant.onTurnChanged = { onTurnChanged() }
        presence = Presence(this, prefs, presenceListener)
        root.addView(face, FrameLayout.LayoutParams(-1, -1))
        root.addView(keyboardButton(), FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(16), dp(16))
        })
        inputBar = inputBar()
        root.addView(inputBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(96), 0, dp(96), dp(16))
        })
        overlay = DebugOverlay(this) { overlayLines() }
        root.addView(overlay, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        settingsView = SettingsView(this, prefs, settingsHost)
        root.addView(settingsView, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        overlay.show(prefs.overlayEnabled)

        AssistantService.start(this, "activity")
        applyNight()
        presence.start()
        network.start()
        FileLog.i(TAG, "created reason=${intent.getStringExtra(EXTRA_LAUNCH_REASON) ?: "launcher"} uptime=${SystemClock.elapsedRealtime() / 1000}s")
        handleCommands(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCommands(intent)
    }

    override fun onResume() {
        super.onResume()
        FaceVisibility.onShown(SystemClock.elapsedRealtime())
        hideSystemUi()
        refreshCountdown()
        applyNight()
    }

    override fun onPause() {
        FaceVisibility.onHidden(SystemClock.elapsedRealtime())
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    /** As the home screen, Back must not leave the face. */
    override fun onBackPressed() {
        if (settingsView.isOpen) settingsView.hide()
        else if (inputBar.visibility == View.VISIBLE) toggleInput(false)
    }

    override fun onFaceReady() {
        FileLog.i(TAG, "FACE_READY uptime=${SystemClock.elapsedRealtime() / 1000}s")
    }

    override fun onFaceTap() {
        if (inputBar.visibility == View.VISIBLE) toggleInput(false) else assistant.onTap()
    }

    override fun onQrHidden() = hidePage("tap")

    override fun onDestroy() {
        presence.release()
        network.stop()
        ticker.removeCallbacks(nightWatch)
        ticker.removeCallbacks(countdown)
        ticker.removeCallbacks(hidePageRunnable)
        assistant.release()
        gateway.attachWebHost(null)
        super.onDestroy()
    }

    /** FR-GWEB-10: let the hidden Gemini page go when the system is short on memory. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        gateway.onTrimMemory(level)
    }

    private fun overlayLines(): List<String> = buildList {
        add("turn=${assistant.currentTurn} ${com.bello.assistant.core.PerfMonitor.lastLine}")
        add(if (network.isOnline()) "network ok" else "NO NETWORK")
        add("last=${gateway.lastSource ?: "—"} ${gateway.lastLatencyMs} ms")
        add(assistant.wakeStatus())
        add("${presence.status()} night=$night")
        add("pages ${(application as BelloApp).pages().status()}")
        addAll(gateway.statusLines())
    }

    /** The way into the settings, and the way out of a kiosk (FR-SET-01, FR-ON-07). */
    override fun onFaceLongPress() {
        FileLog.i(TAG, "settings opened")
        wake(FULL_BRIGHTNESS)
        settingsView.open()
    }

    /** Timers, alarms and pages talk back to the conversation through here. */
    private val routerListener = object : Router.Listener {
        override fun onSchedulesChanged() = refreshCountdown()

        override fun onStopRequested() {
            assistant.stopRinging("voice")
            hidePage("stop")
        }

        override fun onPageReady(url: String, qrRows: List<String>, caption: String, spoken: String) = runOnUiThread {
            showPage(url, qrRows, caption)
            assistant.announce(spoken, FaceState.HAPPY)
        }

        override fun onPageFailed(spoken: String) = runOnUiThread { assistant.announce(spoken, FaceState.SAD) }
    }

    // --- A page for the phone (FR-PAGE-05) --------------------------------------------------------

    private fun pageShowing() = SystemClock.elapsedRealtime() < pageShownUntil

    /** Main thread. The card stays a few minutes at full brightness; nothing else hides it early. */
    private fun showPage(url: String, qrRows: List<String>, caption: String) {
        ticker.removeCallbacks(hidePageRunnable)
        face.showQr(qrRows, caption, url)
        pageShownUntil = SystemClock.elapsedRealtime() + PAGE_SHOWN_MS
        ticker.postDelayed(hidePageRunnable, PAGE_SHOWN_MS)
        wake(brightnessFor())
        FileLog.i(TAG, "PAGE_SHOWN url=$url")
    }

    private fun hidePage(reason: String) {
        ticker.removeCallbacks(hidePageRunnable)
        if (pageShownUntil == 0L) return
        pageShownUntil = 0L
        face.hideQr()
        FileLog.i(TAG, "PAGE_HIDDEN reason=$reason")
        applyNight()
    }

    /** The shortest running timer is shown under the clock and ticks every second (FR-TOOL-02). */
    private val countdown = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val next = router.schedules().minByOrNull { it.dueAt }
            if (next == null) {
                face.showCountdown("")
                return
            }
            face.showCountdown(clockText(next.remainingMs(now)))
            ticker.postDelayed(this, 1_000)
        }
    }

    private fun refreshCountdown() {
        ticker.removeCallbacks(countdown)
        ticker.post(countdown)
    }

    private fun clockText(remainingMs: Long): String {
        val total = (remainingMs + 999) / 1000
        return if (total >= 3600) String.format("%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
        else String.format("%d:%02d", total / 60, total % 60)
    }


    // --- Night, presence and settings (FR-ON-06, FR-PRES-*, FR-SET-*) --------------------------

    /** Once a minute is often enough to notice that it has become eleven o'clock. */
    private val nightWatch = object : Runnable {
        override fun run() {
            applyNight()
            ticker.postDelayed(this, NIGHT_CHECK_MS)
        }
    }

    private fun applyNight() {
        ticker.removeCallbacks(nightWatch)
        ticker.postDelayed(nightWatch, NIGHT_CHECK_MS)
        val start = NightMode.parse(prefs.nightStart) ?: return
        val end = NightMode.parse(prefs.nightEnd) ?: return
        val now = java.util.Calendar.getInstance()
        val minuteOfDay = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val wasNight = night
        night = nightOverride ?: NightMode.isNight(minuteOfDay, start, end)
        if (night != wasNight) {
            FileLog.i(TAG, "NIGHT=$night (${prefs.nightStart}–${prefs.nightEnd})")
            // The camera sleeps with the house; the wake word does not (FR-PRES-03, FR-ON-06).
            if (night) presence.stop("night") else presence.start()
        }
        val busy = assistant.currentTurn != Turn.IDLE
        wake(brightnessFor())
        if (night != wasNight || !busy) face.setState(ConversationPolicy.face(assistant.currentTurn, night))
    }

    /** Brighten for the conversation, and let it fade back afterwards (FR-ON-06). */
    private fun onTurnChanged() {
        if (night) wake(brightnessFor())
    }

    /**
     * The one rule for the screen: dim only at night, when nobody is talking to Bello and no page
     * is on show — a phone has to be able to read the code (FR-ON-06, FR-PAGE-05).
     */
    private fun brightnessFor(): Float =
        if (night && assistant.currentTurn == Turn.IDLE && !pageShowing()) prefs.nightBrightness else FULL_BRIGHTNESS

    private fun wake(brightness: Float) {
        val params = window.attributes
        if (params.screenBrightness == brightness) return
        params.screenBrightness = brightness
        window.attributes = params
    }

    private val presenceListener = object : Presence.Listener {
        override fun onArrived(afterLongAbsence: Boolean) {
            if (!afterLongAbsence || night) return
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            assistant.greet(prefs.greetAloud, hour)
        }

        override fun onLeft() = Unit
    }

    private val settingsHost = object : SettingsView.Host {
        override fun onSettingsChanged(what: String) {
            FileLog.i(TAG, "settings changed: $what")
            when (what) {
                "wake" -> assistant.wakeCommand(if (prefs.wakeEnabled) prefs.wakeSensitivity else "off")
                "voice" -> assistant.setVoiceParams(prefs.ttsPitch, prefs.ttsRate)
                "night" -> applyNight()
                "presence" -> restartPresence()
                "overlay" -> overlay.show(prefs.overlayEnabled)
                "import", "providers" -> reloadEverything()
            }
        }

        override fun providerSwitches(): List<ConfigIo.ProviderSwitch> = ConfigIo.providerSwitches(this@MainActivity)

        override fun setProviderEnabled(name: String, on: Boolean): Boolean =
            ConfigIo.setProviderEnabled(this@MainActivity, name, on)

        override fun testProvider() = Thread({
            val answer = gateway.answer("Dis bonjour en une phrase.")
            runOnUiThread { assistant.speakNow(answer.text) }
        }, "test-provider").start()

        override fun testVoice() = assistant.speakNow("Bello ! Ma voix fonctionne.")

        override fun testMicrophone() {
            settingsView.hide()
            assistant.onTap()
        }

        override fun testWakeWord() {
            settingsView.hide()
            assistant.wakeCommand("status")
            assistant.speakNow("Dis « Bello » pour voir.")
        }

        override fun providerLines(): List<String> = gateway.statusLines()

        override fun memoryLine(): String = router.memoryLine()

        override fun forgetEverything() = router.forgetEverything()

        override fun close() {
            settingsView.hide()
            hideSystemUi()
            applyNight()
        }
    }

    /** Settings changed: apply them to a camera that may be running, stopped or unavailable. */
    private fun restartPresence() {
        presence.stop("settings")
        ticker.postDelayed({ presence.start() }, PRESENCE_RESTART_MS)
    }

    /** The one way a Router is made, so both constructions get the same switches. */
    private fun newRouter(gateway: LlmGateway) =
        Router(this, gateway, AppConfig.load(this), routerListener, (application as BelloApp).pages(),
            isOnline = network::isOnline, offersEnabled = { prefs.pageOffers },
            featureEnabled = { prefs.isEnabled(it) }, imagesEnabled = { prefs.pageImages })

    private fun reloadEverything() {
        val fresh = (application as BelloApp).reloadGateway()
        gateway = fresh
        fresh.attachWebHost(root)
        router = newRouter(fresh)
        assistant.setResponder(router)
        assistant.setVoiceParams(prefs.ttsPitch, prefs.ttsRate)
        assistant.wakeCommand(if (prefs.wakeEnabled) prefs.wakeSensitivity else "off")
        overlay.show(prefs.overlayEnabled)
        restartPresence()
        applyNight()
        FileLog.i(TAG, "CONFIG_APPLIED")
    }

    private fun handleCommands(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_SELFCHECK, false)) {
            Thread({ Diagnostics(applicationContext).run() }, "selfcheck").start()
        }
        intent.getStringExtra(EXTRA_KIOSK)?.let {
            prefs.kioskEnabled = it == "on"
            FileLog.i(TAG, "kiosk=${prefs.kioskEnabled}")
        }
        intent.getStringExtra(EXTRA_STATE)?.let { s ->
            FaceState.fromJs(s)?.let { face.setState(it) } ?: FileLog.w(TAG, "unknown state '$s'")
        }
        intent.getStringExtra(EXTRA_OVERLAY)?.let { overlay.show(it == "on") }
        intent.getStringExtra(EXTRA_WAKE)?.let { assistant.wakeCommand(it) }
        intent.getLongExtra(EXTRA_RING, -1).takeIf { it >= 0 }?.let { id ->
            assistant.ring(router.ringingText(id))
            refreshCountdown()
        }
        intent.getStringExtra(EXTRA_LLM)?.let { command ->
            when (command) {
                "status" -> overlayLines().forEach { FileLog.i(TAG, "LLM_STATUS $it") }
                // Through the router, always: answering straight from the gateway would leave
                // Bello without its clock, timers, tools or memory until the next restart.
                "reload" -> {
                    reloadEverything()
                    FileLog.i(TAG, "LLM_RELOADED")
                    overlayLines().forEach { FileLog.i(TAG, "LLM_STATUS $it") }
                }
                else -> FileLog.w(TAG, "unknown llm command '$command'")
            }
        }
        intent.getStringExtra(EXTRA_PRESENCE)?.let { command ->
            when (command) {
                "on" -> { prefs.presenceEnabled = true; presence.start() }
                "off" -> { prefs.presenceEnabled = false; presence.stop("command") }
                "check" -> presence.diagnose()
                else -> FileLog.i(TAG, "PRESENCE ${presence.status()}")
            }
        }
        intent.getStringExtra(EXTRA_FEATURE)?.let { command ->
            // "off:weather", "on:tv", "status"
            val feature = Feature.byKey(command.substringAfter(':', ""))
            when {
                command == "status" -> Unit
                feature == null -> FileLog.w(TAG, "unknown feature command '$command'")
                command.startsWith("on:") -> prefs.setEnabled(feature, true)
                command.startsWith("off:") -> prefs.setEnabled(feature, false)
                else -> FileLog.w(TAG, "unknown feature command '$command'")
            }
            Feature.values().forEach { FileLog.i(TAG, "FEATURE ${it.key}=${if (prefs.isEnabled(it)) "on" else "off"}") }
        }
        intent.getStringExtra(EXTRA_NIGHT)?.let { command ->
            nightOverride = when (command) { "on" -> true; "off" -> false; else -> null }
            applyNight()
            FileLog.i(TAG, "NIGHT_MODE command=$command night=$night")
        }
        intent.getStringExtra(EXTRA_SETTINGS)?.let { command ->
            when {
                command == "open" -> onFaceLongPress()
                command == "export" -> FileLog.i(TAG, "SETTINGS ${ConfigIo.writeExport(this, false).absolutePath}")
                command == "export-keys" -> FileLog.i(TAG, "SETTINGS ${ConfigIo.writeExport(this, true).absolutePath}")
                command.startsWith("import") -> {
                    val path = command.substringAfter("import:", "").ifBlank { null }
                    val result = ConfigIo.importFile(this, path)
                    FileLog.i(TAG, "SETTINGS import ok=${result.ok} ${result.message}")
                    if (result.ok) reloadEverything()
                }
                command == "status" -> FileLog.i(TAG, "SETTINGS ${ConfigIo.settings(prefs)}")
                else -> FileLog.w(TAG, "unknown settings command '$command'")
            }
        }
        intent.getStringExtra(EXTRA_PAGE)?.let { command ->
            val pages = (application as BelloApp).pages()
            when (command) {
                // The whole path without a chat provider: the built-in recipe, served and shown —
                // with its picture when a picture service is configured and switched on.
                "demo" -> Thread({
                    val host = pages.address() ?: "127.0.0.1"
                    val image = if (!prefs.pageImages) null else router.images.illustrate(PageHtml.SAMPLE_IMAGE_PROMPT)
                        ?.let { PageStore.Image(it.bytes, it.contentType) }
                    val page = pages.publish(PageHtml.SAMPLE_MARKDOWN, host, image = image)
                    if (page == null) FileLog.w(TAG, "PAGE_DEMO failed")
                    else runOnUiThread {
                        showPage(page.url, page.qrRows, ToolReplies.qrCaption(page.title))
                        assistant.announce(ToolReplies.pageReady(page.title), FaceState.HAPPY)
                    }
                }, "page-demo").start()
                "status" -> Thread({
                    FileLog.i(TAG, "PAGE_STATUS ${pages.status()} shown=${pageShowing()} wifi=${LocalAddress.wifiIpv4() ?: "-"}")
                }, "page-status").start()
                "off" -> {
                    hidePage("command")
                    Thread({ pages.stop() }, "page-off").start()
                }
                "images:on", "images:off" -> {
                    prefs.pageImages = command == "images:on"
                    FileLog.i(TAG, "PAGE_IMAGES=${if (prefs.pageImages) "on" else "off"}")
                }
                "images" -> Thread({
                    val lines = router.images.statusLines().ifEmpty { listOf("no picture service in config.json") }
                    FileLog.i(TAG, "PAGE_IMAGES=${if (prefs.pageImages) "on" else "off"} " + lines.joinToString(" | "))
                }, "page-images").start()
                else -> FileLog.w(TAG, "unknown page command '$command'")
            }
        }
        intent.getStringExtra(EXTRA_TEXT)?.let { assistant.onUserText(it) }
        intent.getStringExtra(EXTRA_SPEAK)?.let { assistant.speakNow(it) }
        if (intent.getBooleanExtra(EXTRA_TAP, false)) assistant.onTap()
        intent.getStringExtra(EXTRA_VOICE)?.let { spec ->
            val parts = spec.split(",")
            val pitch = parts.getOrNull(0)?.toFloatOrNull()
            val rate = parts.getOrNull(1)?.toFloatOrNull()
            if (pitch != null && rate != null) assistant.setVoiceParams(pitch, rate)
        }
        if (intent.getBooleanExtra(EXTRA_CRASH, false) && isDebuggable()) {
            FileLog.w(TAG, "crash requested (debug build) — testing crash logging and restart")
            throw IllegalStateException("Bello test crash")
        }
    }

    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    private fun keyboardButton() = TextView(this).apply {
        text = "Aa"
        textSize = 24f
        gravity = Gravity.CENTER
        setTextColor(Color.argb(170, 255, 255, 255))
        background = rounded(Color.argb(60, 255, 255, 255))
        contentDescription = "Écrire une question"
        setOnClickListener { toggleInput(inputBar.visibility != View.VISIBLE) }
    }

    private fun inputBar(): LinearLayout {
        input = EditText(this).apply {
            hint = "Écris ta question…"
            textSize = 22f
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(140, 255, 255, 255))
            background = null
            setOnEditorActionListener { _, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEND || enter) { submit(); true } else false
            }
        }
        val send = TextView(this).apply {
            text = "OK"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(255, 214, 0))
            setOnClickListener { submit() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            background = rounded(Color.rgb(20, 30, 42))
            setPadding(dp(20), dp(6), dp(12), dp(6))
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(send, LinearLayout.LayoutParams(dp(56), dp(56)))
        }
    }

    private fun submit() {
        val text = input.text.toString()
        input.setText("")
        toggleInput(false)
        assistant.onUserText(text)
    }

    private fun toggleInput(show: Boolean) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) {
            inputBar.visibility = View.VISIBLE
            input.requestFocus()
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            inputBar.visibility = View.GONE
            hideSystemUi()
        }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(32).toFloat()
    }

    private fun isDebuggable() =
        applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "ui"
        const val EXTRA_SELFCHECK = "selfcheck"
        const val EXTRA_STATE = "state"
        const val EXTRA_TEXT = "text"
        const val EXTRA_KIOSK = "kiosk"
        const val EXTRA_LAUNCH_REASON = "launchReason"
        const val EXTRA_CRASH = "crash"
        const val EXTRA_SPEAK = "speak"
        const val EXTRA_TAP = "tap"
        const val EXTRA_VOICE = "voice"
        const val EXTRA_OVERLAY = "overlay"
        const val EXTRA_LLM = "llm"
        const val EXTRA_RING = "ring"
        const val EXTRA_WAKE = "wake"
        const val EXTRA_SETTINGS = "settings"
        const val EXTRA_NIGHT = "night"
        const val EXTRA_PRESENCE = "presence"
        const val EXTRA_PAGE = "page"
        const val EXTRA_FEATURE = "feature"
        const val NIGHT_CHECK_MS = 60_000L
        /** How long the QR code stays on the face: time to find the phone and scan (FR-PAGE-05). */
        const val PAGE_SHOWN_MS = 180_000L
        /** Long enough for the camera to be handed back before it is asked for again. */
        const val PRESENCE_RESTART_MS = 700L
        /** BRIGHTNESS_OVERRIDE_NONE: back to whatever the system would do. */
        const val FULL_BRIGHTNESS = -1f
    }
}
