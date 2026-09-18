package com.bello.assistant.core

import android.content.Context

/**
 * Every setting that belongs to this tablet rather than to the answer providers: the ones the
 * settings screen shows, `scripts/settings.sh` exports, and [ConfigIo] writes back.
 */
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

    /** Listening for "Bello" without a tap (FR-WAKE-02). */
    var wakeEnabled: Boolean
        get() = sp.getBoolean("wakeEnabled", true)
        set(v) = sp.edit().putBoolean("wakeEnabled", v).apply()

    /** How eager the wake word is: LOW, NORMAL or HIGH (FR-WAKE-03). */
    var wakeSensitivity: String
        get() = sp.getString("wakeSensitivity", "NORMAL") ?: "NORMAL"
        set(v) = sp.edit().putString("wakeSensitivity", v).apply()

    /** How long to keep listening for a follow-up after an answer; 0 disables it (FR-CONV-07). */
    var followUpMs: Int
        get() = sp.getInt("followUpMs", 6000)
        set(v) = sp.edit().putInt("followUpMs", v).apply()

    // --- A page for the phone (FR-PAGE-*) ----------------------------------------------------

    /** Offer the details on the phone after a recipe, a how-to or a list (FR-PAGE-01). */
    var pageOffers: Boolean
        get() = sp.getBoolean("pageOffers", true)
        set(v) = sp.edit().putBoolean("pageOffers", v).apply()

    /** Where the tablet serves its pages; `adb forward tcp:8080 tcp:8080` reaches it from the Mac. */
    var pagePort: Int
        get() = sp.getInt("pagePort", 8080)
        set(v) = sp.edit().putInt("pagePort", v).apply()

    // --- Night mode (FR-ON-06) -------------------------------------------------------------

    /** "23:00" and "07:00"; equal times mean the screen never dims. */
    var nightStart: String
        get() = sp.getString("nightStart", NightMode.DEFAULT_START) ?: NightMode.DEFAULT_START
        set(v) = sp.edit().putString("nightStart", v).apply()

    var nightEnd: String
        get() = sp.getString("nightEnd", NightMode.DEFAULT_END) ?: NightMode.DEFAULT_END
        set(v) = sp.edit().putString("nightEnd", v).apply()

    /** 0.01–1.0: how dim the face goes at night. Never fully dark — it is a night light too. */
    var nightBrightness: Float
        get() = sp.getFloat("nightBrightness", 0.05f)
        set(v) = sp.edit().putFloat("nightBrightness", v).apply()

    // --- Presence (FR-PRES-*) --------------------------------------------------------------

    var presenceEnabled: Boolean
        get() = sp.getBoolean("presenceEnabled", true)
        set(v) = sp.edit().putBoolean("presenceEnabled", v).apply()

    /** How often a camera frame is actually looked at; every 2 s costs ≈7.5 % CPU (SP-05). */
    var presenceIntervalSec: Int
        get() = sp.getInt("presenceIntervalSec", 2)
        set(v) = sp.edit().putInt("presenceIntervalSec", v).apply()

    /** How long the room must have been empty before somebody is greeted on arrival. */
    var greetAfterMinutes: Int
        get() = sp.getInt("greetAfterMinutes", 5)
        set(v) = sp.edit().putInt("greetAfterMinutes", v).apply()

    /** The greeting is a face, not a voice, unless this is turned on (FR-PRES-02). */
    var greetAloud: Boolean
        get() = sp.getBoolean("greetAloud", false)
        set(v) = sp.edit().putBoolean("greetAloud", v).apply()

    // --- Settings screen (FR-ON-07) ---------------------------------------------------------

    /** Empty means the long press opens the settings straight away. */
    var settingsPin: String
        get() = sp.getString("settingsPin", "") ?: ""
        set(v) = sp.edit().putString("settingsPin", v).apply()

    var overlayEnabled: Boolean
        get() = sp.getBoolean("overlayEnabled", false)
        set(v) = sp.edit().putBoolean("overlayEnabled", v).apply()
}
