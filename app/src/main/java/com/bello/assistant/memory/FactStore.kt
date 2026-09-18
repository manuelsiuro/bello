package com.bello.assistant.memory

import com.bello.assistant.assistant.Intents
import com.bello.assistant.core.FileLog

/** One thing the user asked Bello to remember. */
data class Fact(val id: Long, val text: String, val createdAt: Long)

/**
 * Long-term memory (FR-MEM-03/04): facts the user dictated, stored on the tablet and added to the
 * system prompt whatever provider answers (FR-MEM-05).
 */
class FactStore(private val db: BelloDb) {

    fun add(text: String, now: Long = System.currentTimeMillis()): Fact {
        val clean = text.trim().trimEnd('.', '!', '?')
        val id = db.writableDatabase.insert("facts", null, db.values("text" to clean, "created_at" to now))
        FileLog.i(TAG, "remembered #$id: $clean")
        return Fact(id, clean, now)
    }

    fun all(): List<Fact> = db.readableDatabase.query(
        "facts", arrayOf("id", "text", "created_at"), null, null, null, null, "created_at ASC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(Fact(cursor.getLong(0), cursor.getString(1), cursor.getLong(2)))
        }
    }

    fun delete(id: Long): Boolean =
        db.writableDatabase.delete("facts", "id = ?", arrayOf(id.toString())) > 0

    fun clear(): Int = db.writableDatabase.delete("facts", null, null)

    /** Deletes the memories the user meant with "oublie …"; returns what was removed. */
    fun forget(query: String): List<Fact> {
        val hits = FactMatch.matches(all(), query)
        hits.forEach { delete(it.id) }
        FileLog.i(TAG, "forgot ${hits.size} fact(s) for \"$query\"")
        return hits
    }

    /** The block added to the system prompt, or empty when there is nothing to remember. */
    fun promptBlock(): String {
        val facts = all()
        if (facts.isEmpty()) return ""
        return "Ce que tu sais sur la personne qui te parle :\n" +
            facts.joinToString("\n") { "- ${it.text}" }
    }

    private companion object { const val TAG = "memory" }
}

/** Which stored fact the user means. Pure, unit tested. */
object FactMatch {

    /**
     * A fact matches when the words of the request are in it (or the other way round for a short
     * request): "oublie mon café" must find "mon café préféré est l'espresso", but must not delete
     * an unrelated memory just because both contain "mon".
     */
    fun matches(facts: List<Fact>, query: String): List<Fact> {
        val wanted = contentWords(query)
        if (wanted.isEmpty()) return emptyList()
        return facts.filter { fact ->
            val words = contentWords(fact.text)
            val common = wanted.count { it in words }
            common == wanted.size || (common >= 2 && common >= wanted.size - 1)
        }
    }

    private val NOISE = setOf(
        "que", "qui", "quoi", "le", "la", "les", "un", "une", "des", "du", "de", "d", "l",
        "est", "et", "a", "au", "aux", "en", "mon", "ma", "mes", "ton", "ta", "tes", "ce", "c",
        "je", "tu", "il", "elle", "on", "moi", "toi", "pour", "avec", "sur", "dans",
    )

    fun contentWords(text: String): Set<String> =
        Intents.deaccent(text).split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 1 && it !in NOISE }
            .toSet()
}
