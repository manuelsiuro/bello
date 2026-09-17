package com.bello.assistant.service

/** Decides when the watchdog should bring the face activity back. Pure, unit tested. */
object KioskPolicy {
    const val GRACE_MS = 60_000L

    fun shouldRelaunch(
        kioskEnabled: Boolean,
        activityVisible: Boolean,
        lastVisibleAtMs: Long,
        nowMs: Long,
        graceMs: Long = GRACE_MS,
    ): Boolean = kioskEnabled && !activityVisible && nowMs - lastVisibleAtMs >= graceMs
}

/** Foreground visibility of the face activity, shared between activity and service. */
object FaceVisibility {
    @Volatile var visible = false
        private set
    @Volatile var lastVisibleAtMs = 0L
        private set

    fun onShown(nowMs: Long) { visible = true; lastVisibleAtMs = nowMs }
    fun onHidden(nowMs: Long) { visible = false; lastVisibleAtMs = nowMs }
}
