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
}
