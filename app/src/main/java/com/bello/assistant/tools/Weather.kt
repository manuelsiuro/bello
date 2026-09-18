package com.bello.assistant.tools

import android.content.Context
import com.bello.assistant.core.FileLog
import org.json.JSONObject

/**
 * Weather from Open-Meteo (FR-TOOL-05): no key, no quota, and it was the first HTTPS endpoint
 * proven on this tablet in SP-04. City names are resolved through Open-Meteo's own geocoder.
 */
class Weather(private val context: Context) {

    data class Place(val name: String, val latitude: Double, val longitude: Double)

    private val places = HashMap<String, Place?>()

    /** A spoken French sentence, or null when the service could not be reached. */
    fun report(city: String?, tomorrow: Boolean, defaultCity: String): String? {
        val wanted = city?.takeIf { it.isNotBlank() } ?: defaultCity
        val place = place(wanted) ?: return "Je ne trouve pas $wanted sur la carte."
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}" +
            "&longitude=${place.longitude}&current=temperature_2m,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=2"
        val json = ToolHttp.getText(context, url, TAG) ?: return null
        return runCatching { sentence(JSONObject(json), place, tomorrow) }.getOrElse {
            FileLog.w(TAG, "cannot read the forecast", it)
            null
        }
    }

    private fun sentence(root: JSONObject, place: Place, tomorrow: Boolean): String {
        val daily = root.getJSONObject("daily")
        val day = if (tomorrow) 1 else 0
        val high = daily.getJSONArray("temperature_2m_max").getDouble(day).roundedDegrees()
        val low = daily.getJSONArray("temperature_2m_min").getDouble(day).roundedDegrees()
        val sky = WmoCodes.french(daily.getJSONArray("weather_code").getInt(day))
        val rain = daily.optJSONArray("precipitation_probability_max")?.optInt(day, -1) ?: -1
        val rainPart = if (rain >= RAIN_WORTH_MENTIONING) " Risque de pluie : $rain pour cent." else ""
        if (tomorrow) {
            return "Demain à ${place.name}, $sky, entre $low et $high degrés.$rainPart"
        }
        val current = root.getJSONObject("current")
        val now = current.getDouble("temperature_2m").roundedDegrees()
        val nowSky = WmoCodes.french(current.getInt("weather_code"))
        return "À ${place.name}, il fait $now degrés, $nowSky. " +
            "Aujourd'hui, entre $low et $high degrés.$rainPart"
    }

    private fun Double.roundedDegrees() = Math.round(this).toInt()

    /** Geocoding is cached: the same living room asks about the same town every day. */
    fun place(city: String): Place? = places.getOrPut(city.lowercase()) {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            city.trim().replace(" ", "+") + "&count=1&language=fr&format=json"
        val json = ToolHttp.getText(context, url, TAG) ?: return@getOrPut null
        runCatching {
            val first = JSONObject(json).optJSONArray("results")?.optJSONObject(0)
                ?: return@runCatching null
            Place(first.getString("name"), first.getDouble("latitude"), first.getDouble("longitude"))
        }.getOrNull()
    }


    private companion object {
        const val TAG = "weather"
        const val RAIN_WORTH_MENTIONING = 30
    }
}

/** WMO weather codes in plain French, ready to be spoken. Pure, unit tested. */
object WmoCodes {
    fun french(code: Int): String = when (code) {
        0 -> "ciel dégagé"
        1 -> "plutôt ensoleillé"
        2 -> "partiellement nuageux"
        3 -> "ciel couvert"
        45, 48 -> "du brouillard"
        51, 53, 55 -> "de la bruine"
        56, 57 -> "de la bruine verglaçante"
        61, 63 -> "de la pluie"
        65 -> "de fortes pluies"
        66, 67 -> "de la pluie verglaçante"
        71, 73, 75, 77 -> "de la neige"
        80, 81 -> "des averses"
        82 -> "de fortes averses"
        85, 86 -> "des averses de neige"
        95 -> "de l'orage"
        96, 99 -> "de l'orage avec de la grêle"
        else -> "un temps difficile à décrire"
    }
}
