package com.bello.assistant.tools

import android.content.Context
import com.bello.assistant.core.FileLog
import com.bello.assistant.core.FrenchDates
import com.bello.assistant.core.TextCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** A public holiday: the name the government's file gives it, and the day it falls on. */
data class PublicHoliday(val name: String, val day: Long)

/**
 * A school break: what the ministry calls it, when it starts, and when the children go back.
 * A one-day break (the bridge after Ascension) is written with the same start and end in the
 * dataset, so [resume] is pushed to the day after rather than trusted as it comes.
 */
data class SchoolBreak(val description: String, val start: Long, val resume: Long) {
    /** The last day off: the eve of the day classes resume. */
    val lastDay: Long get() = FrenchDates.addDays(resume, -1)
}

/** Reading the two government files. Pure, unit tested on the real answers of 2026-09-18. */
object HolidayData {

    /** `{"2026-11-01": "Toussaint", …}`, in the order of the calendar. */
    fun publicHolidays(json: String): List<PublicHoliday> = runCatching {
        val root = JSONObject(json)
        root.keys().asSequence().mapNotNull { key ->
            val day = FrenchDates.parseDay(key) ?: return@mapNotNull null
            val name = root.optString(key).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PublicHoliday(name, day)
        }.sortedBy { it.day }.toList()
    }.getOrDefault(emptyList())

    /**
     * The `results` of the Opendatasoft query. Summer is published twice, once for the children
     * and once for the teachers: the household is asking about the children.
     */
    fun schoolBreaks(json: String): List<SchoolBreak> = runCatching {
        val results: JSONArray = JSONObject(json).optJSONArray("results") ?: return emptyList()
        (0 until results.length()).mapNotNull { i ->
            val row = results.optJSONObject(i) ?: return@mapNotNull null
            if (row.optString("population").equals("Enseignants", ignoreCase = true)) return@mapNotNull null
            val description = row.optString("description").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val start = FrenchDates.parseInstantUtc(row.optString("start_date")) ?: return@mapNotNull null
            val end = FrenchDates.parseInstantUtc(row.optString("end_date")) ?: return@mapNotNull null
            SchoolBreak(description, start, if (end > start) end else FrenchDates.addDays(start, 1))
        }.distinctBy { it.description to it.start }.sortedBy { it.start }
    }.getOrDefault(emptyList())

    /** The break the day falls in, if any. */
    fun currentBreak(breaks: List<SchoolBreak>, now: Long): SchoolBreak? =
        breaks.firstOrNull { now >= it.start && now < it.resume }

    fun nextBreak(breaks: List<SchoolBreak>, now: Long): SchoolBreak? =
        breaks.firstOrNull { it.start > now }
}

/**
 * The two calendars everybody in the house asks about: the public holidays
 * (`calendrier.api.gouv.fr`, one file per year, three hundred bytes) and the school breaks
 * (`data.education.gouv.fr`, filtered to the household's zone). Both are free, need no key, and
 * are kept on the tablet once fetched — a year's holidays never change.
 */
class Holidays(
    context: Context,
    /** Beside the logs and the configuration, so `adb` can see what the tablet is answering from. */
    cacheDir: File = File(
        context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir,
        "cache",
    ),
) {

    private val app = context.applicationContext
    private val cache = TextCache(cacheDir)

    /** The next public holiday from [now] on, today included. */
    fun nextPublicHoliday(now: Long, zone: String, allowNetwork: Boolean): PublicHoliday? {
        val today = FrenchDates.midnight(now)
        val year = FrenchDates.year(now)
        publicHolidays(year, zone, allowNetwork).firstOrNull { it.day >= today }?.let { return it }
        // In December the next one is in January: the following year is its own small file.
        return publicHolidays(year + 1, zone, allowNetwork).firstOrNull { it.day >= today }
    }

    /** The public holiday falling on that day, if it is one. */
    fun publicHolidayOn(day: Long, zone: String, allowNetwork: Boolean): PublicHoliday? {
        val midnight = FrenchDates.midnight(day)
        return publicHolidays(FrenchDates.year(day), zone, allowNetwork).firstOrNull { it.day == midnight }
    }

    private fun publicHolidays(year: Int, zone: String, allowNetwork: Boolean): List<PublicHoliday> {
        val json = cache.text("feries-$zone-$year", A_YEAR_MS) {
            if (!allowNetwork) null
            else ToolHttp.getText(app, "$FERIES_URL/$zone/$year.json", TAG)
        } ?: return emptyList()
        return HolidayData.publicHolidays(json).also {
            if (it.isEmpty()) FileLog.w(TAG, "no public holiday read for $zone $year")
        }
    }

    /**
     * The school breaks still to come for the household's zone. The query already drops what is
     * over, and the list is kept for a week: it changes once a year.
     */
    fun schoolBreaks(now: Long, zone: String, academy: String, allowNetwork: Boolean): List<SchoolBreak> {
        val json = cache.text("ecole-$academy-$zone", A_WEEK_MS) {
            if (!allowNetwork) null else ToolHttp.getText(app, schoolUrl(now, zone, academy), TAG)
        } ?: return emptyList()
        return HolidayData.schoolBreaks(json).also {
            if (it.isEmpty()) FileLog.w(TAG, "no school break read for $academy / $zone")
        }
    }

    private fun schoolUrl(now: Long, zone: String, academy: String): String {
        val calendar = FrenchDates.calendar(now)
        val today = String.format(
            "%04d-%02d-%02d", calendar.get(java.util.Calendar.YEAR),
            calendar.get(java.util.Calendar.MONTH) + 1, calendar.get(java.util.Calendar.DAY_OF_MONTH),
        )
        val where = "zones=\"$zone\" AND location=\"$academy\" AND end_date>=\"$today\""
        return SCHOOL_URL +
            "?where=" + URLEncoder.encode(where, "UTF-8") +
            "&select=" + URLEncoder.encode("description,start_date,end_date,population", "UTF-8") +
            "&order_by=start_date&limit=20"
    }

    private companion object {
        const val TAG = "holidays"
        const val FERIES_URL = "https://calendrier.api.gouv.fr/jours-feries"
        const val SCHOOL_URL =
            "https://data.education.gouv.fr/api/explore/v2.1/catalog/datasets/fr-en-calendrier-scolaire/records"
        const val A_WEEK_MS = 7 * 24 * 60 * 60 * 1000L
        const val A_YEAR_MS = 300 * 24 * 60 * 60 * 1000L
    }
}
