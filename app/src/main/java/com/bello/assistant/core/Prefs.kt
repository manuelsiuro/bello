package com.bello.assistant.core

import android.content.Context

/** Small typed wrapper around SharedPreferences. Full settings arrive in Phase 6. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("bello", Context.MODE_PRIVATE)

    /** When on, the service brings the face back to the foreground if it disappears. */
    var kioskEnabled: Boolean
        get() = sp.getBoolean("kiosk", true)
        set(v) = sp.edit().putBoolean("kiosk", v).apply()

    var perfIntervalSec: Int
        get() = sp.getInt("perfIntervalSec", 30)
        set(v) = sp.edit().putInt("perfIntervalSec", v).apply()

    /** Minion voice: higher pitch, slightly faster than normal speech (FR-TTS-02). */
    var ttsPitch: Float
        get() = sp.getFloat("ttsPitch", 1.6f)
        set(v) = sp.edit().putFloat("ttsPitch", v).apply()

    var ttsRate: Float
        get() = sp.getFloat("ttsRate", 1.05f)
        set(v) = sp.edit().putFloat("ttsRate", v).apply()

    /** How long to keep listening for a follow-up after an answer; 0 disables it (FR-CONV-07). */
    var followUpMs: Int
        get() = sp.getInt("followUpMs", 6000)
        set(v) = sp.edit().putInt("followUpMs", v).apply()
}
