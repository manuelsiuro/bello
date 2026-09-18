package com.bello.assistant.core

import java.util.Calendar
import java.util.TimeZone

/**
 * Dates as the household reads them: in the timezone of the house, counted in whole days, and
 * written in words a voice can read. Pure and unit tested — the month and weekday names are
 * spelled out here rather than taken from the platform, because a 2014 tablet and a desktop JVM
 * do not always agree on them.
 */
object FrenchDates {

    val PARIS: TimeZone = TimeZone.getTimeZone("Europe/Paris")

    private val WEEKDAYS = arrayOf(
        "dimanche", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi",
    )
    private val MONTHS = arrayOf(
        "janvier", "février", "mars", "avril", "mai", "juin",
        "juillet", "août", "septembre", "octobre", "novembre", "décembre",
    )

    private val ISO = Regex("^(\\d{4})-(\\d{2})-(\\d{2})(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2}))?)?")

    fun calendar(millis: Long): Calendar =
        Calendar.getInstance(PARIS).apply { timeInMillis = millis }

    /** Midnight, in the house's timezone, of the day [millis] falls in. */
    fun midnight(millis: Long): Long = calendar(millis).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun addDays(millis: Long, days: Int): Long =
        calendar(millis).apply { add(Calendar.DAY_OF_MONTH, days) }.timeInMillis

    fun year(millis: Long): Int = calendar(millis).get(Calendar.YEAR)

    /** Whole days from one day to the other, the two short and long days of the year included. */
    fun daysBetween(from: Long, to: Long): Int =
        Math.round((midnight(to) - midnight(from)) / DAY_MS.toDouble()).toInt()

    fun sameDay(a: Long, b: Long): Boolean = midnight(a) == midnight(b)

    /** "2026-11-01" → midnight of that day in the house's timezone. */
    fun parseDay(text: String): Long? {
        val m = ISO.find(text.trim()) ?: return null
        return runCatching {
            Calendar.getInstance(PARIS).apply {
                clear()
                set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt())
            }.timeInMillis
        }.getOrNull()
    }

    /**
     * "2026-10-16T22:00:00+00:00" → the instant it names. The government's school calendar writes
     * its days as UTC midnights of the house's timezone, which is why the offset can be ignored
     * and the value read as UTC: 22:00 UTC in October is midnight in Paris.
     */
    fun parseInstantUtc(text: String): Long? {
        val m = ISO.find(text.trim()) ?: return null
        return runCatching {
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(
                    m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt(),
                    m.groupValues[4].toIntOrNull() ?: 0, m.groupValues[5].toIntOrNull() ?: 0,
                    m.groupValues[6].toIntOrNull() ?: 0,
                )
            }.timeInMillis
        }.getOrNull()
    }

    fun weekday(millis: Long): String = WEEKDAYS[calendar(millis).get(Calendar.DAY_OF_WEEK) - 1]

    fun month(millis: Long): String = MONTHS[calendar(millis).get(Calendar.MONTH)]

    /** "premier" rather than "1er": a voice reads the word, not the abbreviation. */
    fun dayNumber(millis: Long): String =
        calendar(millis).get(Calendar.DAY_OF_MONTH).let { if (it == 1) "premier" else it.toString() }

    /** "dimanche premier novembre" — no year: everything Bello answers is within the year. */
    fun say(millis: Long): String = "${weekday(millis)} ${dayNumber(millis)} ${month(millis)}"

    /** "premier novembre", when the weekday has already been said. */
    fun sayDayMonth(millis: Long): String = "${dayNumber(millis)} ${month(millis)}"

    const val DAY_MS = 24 * 60 * 60 * 1000L
}
