package com.bello.assistant.core

/**
 * When the room is asleep (FR-ON-06). Pure, so the awkward part — a window that crosses midnight —
 * is settled in tests rather than at 23:00 on the tablet.
 */
object NightMode {

    const val DEFAULT_START = "23:00"
    const val DEFAULT_END = "07:00"

    /** Minutes since midnight, or null if it is not a time. */
    fun parse(text: String?): Int? {
        val parts = text?.trim()?.split(":", "h") ?: return null
        if (parts.size < 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    fun format(minuteOfDay: Int): String =
        "%02d:%02d".format(minuteOfDay / 60 % 24, minuteOfDay % 60)

    /**
     * Night runs from [start] to [end], normally across midnight. Equal times mean no night at all,
     * which is how the setting is turned off.
     */
    fun isNight(minuteOfDay: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> minuteOfDay in start until end
        else -> minuteOfDay >= start || minuteOfDay < end
    }

    /** Minutes until the night ends; 0 when it is not night. */
    fun minutesUntilEnd(minuteOfDay: Int, start: Int, end: Int): Int {
        if (!isNight(minuteOfDay, start, end)) return 0
        return if (end > minuteOfDay) end - minuteOfDay else end + 24 * 60 - minuteOfDay
    }
}
