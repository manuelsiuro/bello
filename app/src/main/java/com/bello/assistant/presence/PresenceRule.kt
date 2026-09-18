package com.bello.assistant.presence

/**
 * Turns "a frontal face was seen in this frame" into "somebody is here" (FR-PRES-01, 02).
 *
 * The detector only finds faces looking straight at the tablet (SP-05), so somebody sitting in the
 * room reading is seen every few frames at best. Presence therefore means *seen recently*, and it
 * takes a stretch of nothing at all to decide the room is empty — otherwise Bello would greet the
 * same person every time they looked down.
 *
 * Pure and clock-injected, so the timings are tested instead of waited for.
 */
class PresenceRule(
    private val absentAfterMs: Long = 60_000,
    private val greetAfterMs: Long = 5 * 60_000,
    /** Bello has just started: the room counts as empty since then, and nobody has been missed. */
    startedAt: Long = 0,
) {
    enum class Change {
        /** Somebody is here who was not, and has been away long enough to be greeted. */
        ARRIVED_AND_MISSED,
        /** Somebody is here who was not, but only stepped out for a moment. */
        ARRIVED,
        /** Nobody has been seen for a while. */
        LEFT,
    }

    var isPresent = false
        private set
    private var lastSeenAt = 0L
    private var lastLeftAt = startedAt

    /** [seen] is what the last frame found; returns what changed, or null if nothing did. */
    fun update(seen: Boolean, now: Long): Change? {
        if (seen) {
            lastSeenAt = now
            if (isPresent) return null
            isPresent = true
            return if (now - lastLeftAt >= greetAfterMs) Change.ARRIVED_AND_MISSED else Change.ARRIVED
        }
        if (!isPresent || now - lastSeenAt < absentAfterMs) return null
        isPresent = false
        lastLeftAt = now
        return Change.LEFT
    }

    fun secondsSinceSeen(now: Long): Long = if (lastSeenAt == 0L) -1 else (now - lastSeenAt) / 1000
}
