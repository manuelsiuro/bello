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
import com.bello.assistant.core.Diagnostics
import com.bello.assistant.core.FileLog
import com.bello.assistant.core.Prefs
import com.bello.assistant.service.AssistantService
import com.bello.assistant.service.FaceVisibility

/**
 * Full-screen, always-on face (FR-ON-01/02/04, FR-FACE-*), with a text input bar (FR-CONV-03).
 *
 * adb extras (see scripts/): `selfcheck` (bool), `state` (face state), `text` (typed question),
 * `kiosk` ("on"/"off").
 */
class MainActivity : Activity(), FaceView.Listener {
    private lateinit var face: FaceView
    private lateinit var assistant: Assistant
    private lateinit var inputBar: LinearLayout
    private lateinit var input: EditText
    private lateinit var prefs: Prefs

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
        assistant = Assistant(face)
        val root = FrameLayout(this)
        root.addView(face, FrameLayout.LayoutParams(-1, -1))
        root.addView(keyboardButton(), FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(16), dp(16))
        })
        inputBar = inputBar()
        root.addView(inputBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins(dp(96), 0, dp(96), dp(16))
        })
        setContentView(root)

        AssistantService.start(this, "activity")
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
        if (inputBar.visibility == View.VISIBLE) toggleInput(false)
    }

    override fun onFaceReady() {
        FileLog.i(TAG, "FACE_READY uptime=${SystemClock.elapsedRealtime() / 1000}s")
    }

    override fun onFaceTap() {
        if (inputBar.visibility == View.VISIBLE) toggleInput(false) else assistant.onTap()
    }

    override fun onFaceLongPress() {
        FileLog.i(TAG, "long press (settings arrive in Phase 6)")
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
        intent.getStringExtra(EXTRA_TEXT)?.let { assistant.onUserText(it) }
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
    }
}
