package com.bello.assistant.tools

import android.content.Context
import com.bello.assistant.core.FileLog
import com.bello.assistant.core.FrenchDates
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar

/** The cheapest station found for one fuel: what it costs, where it is, and how far. */
data class FuelStation(
    val fuel: String,
    val price: Double,
    val address: String,
    val town: String,
    val metres: Int,
)

/** Reading the government's price feed. Pure, unit tested on the real answer of 2026-09-18. */
object FuelData {

    /** What the household says, against the column the dataset uses. */
    private val FUELS = linkedMapOf(
        "gazole" to listOf("gazole", "gasoil", "gazol", "diesel"),
        "sp95" to listOf("sp95", "sp 95", "95", "sans plomb 95", "super 95", "sans plomb"),
        "sp98" to listOf("sp98", "sp 98", "98", "sans plomb 98", "super 98"),
        "e10" to listOf("e10", "e 10", "sp95 e10"),
        "e85" to listOf("e85", "e 85", "ethanol", "superethanol", "bioethanol"),
        "gplc" to listOf("gplc", "gpl", "gaz de petrole"),
    )

    /** How Bello says the column back: "sans plomb 95" is easier to hear than "sp95". */
    private val SPOKEN = mapOf(
        "gazole" to "gazole", "sp95" to "sans plomb 95", "sp98" to "sans plomb 98",
        "e10" to "sans plomb 95 E10", "e85" to "superéthanol E85", "gplc" to "GPL",
    )

    /** Longest first, so "sans plomb 98" is not read as the "sans plomb" of the pump next to it. */
    private val WORDS: List<Pair<String, String>> = FUELS.entries
        .flatMap { (fuel, words) -> words.map { it to fuel } }
        .sortedByDescending { it.first.length }

    /** The fuel named in a question, on already accent-stripped text; null when none is. */
    fun fuelIn(flat: String): String? = WORDS.firstOrNull { (word, _) ->
        Regex("(?<![a-z0-9])${Regex.escape(word)}(?![a-z0-9])").containsMatchIn(flat)
    }?.second

    fun isKnown(fuel: String): Boolean = FUELS.containsKey(fuel)

    fun spoken(fuel: String): String = SPOKEN[fuel] ?: fuel

    fun stations(json: String, fuel: String): List<FuelStation> = runCatching {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        (0 until results.length()).mapNotNull { i ->
            val row = results.optJSONObject(i) ?: return@mapNotNull null
            val price = row.optDouble("${fuel}_prix", Double.NaN).takeIf { !it.isNaN() } ?: return@mapNotNull null
            FuelStation(
                fuel = fuel,
                price = price,
                address = row.optString("adresse").trim(),
                town = row.optString("ville").trim(),
                metres = row.optDouble("dist", 0.0).toInt(),
            )
        }.sortedBy { it.price }
    }.getOrDefault(emptyList())
}

/**
 * The price of fuel around the house, from the government's live feed (Licence Ouverte, no key).
 * Prices left untouched for a week are dropped: the dataset keeps stations that stopped reporting,
 * and sending somebody across town for a price from last month is worse than saying nothing.
 */
class Fuel(context: Context) {

    private val app = context.applicationContext

    fun cheapest(latitude: Double, longitude: Double, fuel: String, now: Long): FuelStation? {
        if (!FuelData.isKnown(fuel)) return null
        val since = day(FrenchDates.addDays(now, -FRESH_DAYS))
        val point = "geom'POINT($longitude $latitude)'"
        val where = "within_distance(geom, $point, ${RADIUS_KM}km) AND ${fuel}_prix IS NOT NULL " +
            "AND ${fuel}_maj>=\"$since\""
        val select = "adresse,ville,${fuel}_prix,distance(geom, $point) as dist"
        val url = URL + "?where=" + encode(where) + "&select=" + encode(select) +
            "&order_by=" + encode("${fuel}_prix") + "&limit=3"
        val json = ToolHttp.getText(app, url, TAG) ?: return null
        return FuelData.stations(json, fuel).firstOrNull().also {
            FileLog.i(TAG, if (it == null) "no $fuel around" else "$fuel at ${it.price} in ${it.town}")
        }
    }

    private fun day(millis: Long): String = FrenchDates.calendar(millis).let {
        String.format(
            "%04d-%02d-%02d", it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1,
            it.get(Calendar.DAY_OF_MONTH),
        )
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

    private companion object {
        const val TAG = "fuel"
        const val URL = "https://data.economie.gouv.fr/api/explore/v2.1/catalog/datasets/" +
            "prix-des-carburants-en-france-flux-instantane-v2/records"
        /** Far enough to be worth the trip, near enough to still be the same errand. */
        const val RADIUS_KM = 10
        const val FRESH_DAYS = 7
    }
}
