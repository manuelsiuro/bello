package com.bello.assistant.tools

import android.content.Context
import com.bello.assistant.assistant.Intents
import com.bello.assistant.core.FileLog
import org.json.JSONObject

/**
 * Jokes in French, with the reading that the service's own "safe" flag cannot be trusted: in
 * thirteen samples taken while choosing it, three were about a dictator or about blondes, all
 * flagged safe. So every joke is read here before it is told, and the house has its own small
 * book for when the answer is no, or when there is no network.
 */
object JokeBook {

    /** Told when nothing better comes back; also the whole repertoire with no network. */
    val BUNDLED: List<String> = listOf(
        "Que dit un escargot quand il croise une limace ?… Regarde, un nudiste !",
        "Pourquoi les poissons détestent-ils l'ordinateur ?… Parce qu'ils ont peur du Net !",
        "Quel est le comble pour un électricien ?… De ne pas être au courant !",
        "Que fait une fraise sur un cheval ?… Tagada, tagada !",
        "Pourquoi les canards sont-ils toujours à l'heure ?… Parce qu'ils sont dans l'étang !",
        "Comment appelle-t-on un chien qui a des lunettes ?… Un optichien !",
        "Quel est le fruit préféré des Minions ?… La banane, évidemment !",
    )

    /** Told once, by anybody, and the room has heard enough of it. */
    private val BANNED_IDS = setOf(54, 79, 147)

    private val BANNED_WORDS = listOf(
        "hitler", "nazi", "juif", "juive", "blonde", "blondes", "suicide", "viol", "sexe",
        "sexuel", "raciste", "drogue", "prostituee", "alcoolique",
    )

    fun isAcceptable(id: Int, text: String): Boolean {
        if (id in BANNED_IDS) return false
        val flat = Intents.deaccent(text.lowercase())
        return BANNED_WORDS.none { word ->
            Regex("(?<![a-z])${Regex.escape(word)}(?![a-z])").containsMatchIn(flat)
        }
    }

    /** The service tells a joke in one piece or in two; a voice needs the pause in between. */
    fun parse(json: String): Pair<Int, String>? = runCatching {
        val root = JSONObject(json)
        if (root.optBoolean("error", false)) return null
        val id = root.optInt("id", -1)
        val text = when (root.optString("type")) {
            "twopart" -> {
                val setup = root.optString("setup").trim()
                val delivery = root.optString("delivery").trim()
                if (setup.isEmpty() || delivery.isEmpty()) return null
                "$setup… $delivery"
            }
            else -> root.optString("joke").trim()
        }
        text.takeIf { it.isNotBlank() }?.let { id to it }
    }.getOrNull()

    /** Anything but the one just told: a Minion who repeats himself stops being funny. */
    fun nextBundled(last: String?): String {
        val choices = BUNDLED.filter { it != last }.ifEmpty { BUNDLED }
        return choices.random()
    }
}

class Jokes(context: Context) {

    private val app = context.applicationContext
    @Volatile private var last: String? = null

    /** Never null: there is always a joke in the house. */
    fun tell(allowNetwork: Boolean): String {
        if (allowNetwork) {
            repeat(TRIES) {
                val json = ToolHttp.getText(app, URL, TAG) ?: return@repeat
                val joke = JokeBook.parse(json) ?: return@repeat
                if (!JokeBook.isAcceptable(joke.first, joke.second)) {
                    FileLog.i(TAG, "JOKE_REFUSED id=${joke.first}")
                    return@repeat
                }
                if (joke.second == last) return@repeat
                last = joke.second
                return joke.second
            }
        }
        return JokeBook.nextBundled(last).also { last = it }
    }

    private companion object {
        const val TAG = "jokes"
        const val URL = "https://v2.jokeapi.dev/joke/Any?lang=fr&safe-mode"
        const val TRIES = 3
    }
}
