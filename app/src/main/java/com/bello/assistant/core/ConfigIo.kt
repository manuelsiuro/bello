package com.bello.assistant.core

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * The whole of Bello's configuration as one JSON document (FR-SET-04, 05, 06).
 *
 * `config.json` on the device already holds the providers, the city and the feeds; everything else
 * lives in [Prefs]. Export merges the two so a single file can be edited on the Mac and pushed
 * back, and import takes the same shape apart again. Keys can be left out of an export, which is
 * what makes it safe to send someone.
 */
object ConfigIo {

    const val EXPORT_NAME = "bello-config-export.json"
    private const val TAG = "config"
    private const val MASK = "…"
    private val KEY_FIELDS = listOf("key", "apiKey")

    fun exportFile(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, EXPORT_NAME)

    /** The current configuration, with the device's own settings under "settings". */
    fun export(context: Context, includeKeys: Boolean): String {
        val file = AppConfig.file(context)
        val root = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        if (!includeKeys) mask(root)
        root.put("settings", settings(Prefs(context)))
        return root.toString(2)
    }

    /**
     * A key can be written as "key" or as "apiKey" — the config parser accepts both, so both have
     * to be masked. Getting this wrong writes the real keys into a file somebody then e-mails.
     */
    fun mask(root: JSONObject) {
        val providers = root.optJSONArray("providers") ?: return
        for (i in 0 until providers.length()) {
            val provider = providers.optJSONObject(i) ?: continue
            KEY_FIELDS.forEach { field ->
                if (provider.optString(field).isNotEmpty()) provider.put(field, MASK)
            }
        }
    }

    /** True when nothing in this document looks like a secret. */
    fun isMasked(json: String): Boolean {
        val providers = runCatching { JSONObject(json).optJSONArray("providers") }.getOrNull() ?: return true
        for (i in 0 until providers.length()) {
            val provider = providers.optJSONObject(i) ?: continue
            if (KEY_FIELDS.any { provider.optString(it).let { key -> key.isNotEmpty() && key != MASK } }) return false
        }
        return true
    }

    fun writeExport(context: Context, includeKeys: Boolean): File {
        val file = exportFile(context)
        file.writeText(export(context, includeKeys))
        FileLog.i(TAG, "CONFIG_EXPORTED ${file.absolutePath} keys=$includeKeys")
        return file
    }

    /**
     * Applies a document produced by [export] — or hand-written, or pushed over `adb`. A masked or
     * missing key keeps the one already on the device, so an export without keys can be edited and
     * pushed back without locking Bello out of its providers.
     */
    fun import(context: Context, json: String): Result {
        val incoming = runCatching { JSONObject(json) }.getOrElse {
            FileLog.w(TAG, "CONFIG_IMPORT_FAILED not JSON", it)
            return Result(false, "Ce fichier n'est pas du JSON.")
        }
        val settings = incoming.optJSONObject("settings")
        if (settings != null) applySettings(Prefs(context), settings)
        incoming.remove("settings")
        val target = AppConfig.file(context)
        val current = runCatching { JSONObject(target.readText()) }.getOrElse { JSONObject() }
        keepMaskedKeys(incoming, current)
        // Only replace the provider side when the file actually carries one.
        val merged = if (incoming.length() == 0) current else incoming
        runCatching { target.writeText(merged.toString(2)) }.onFailure {
            FileLog.w(TAG, "CONFIG_IMPORT_FAILED cannot write ${target.name}", it)
            return Result(false, "Je n'ai pas pu écrire la configuration.")
        }
        val problems = AppConfig.parse(merged.toString()).llm.problems
        problems.forEach { FileLog.w(TAG, "config: $it") }
        FileLog.i(TAG, "CONFIG_IMPORTED settings=${settings != null} providers=${incoming.length() > 0}")
        return Result(true, if (problems.isEmpty()) "Configuration appliquée." else problems.first())
    }

    fun importFile(context: Context, path: String?): Result {
        val file = path?.let { File(it) } ?: exportFile(context)
        if (!file.isFile) return Result(false, "Aucun fichier à importer (${file.name}).")
        return import(context, runCatching { file.readText() }.getOrDefault(""))
    }

    data class Result(val ok: Boolean, val message: String)

    /** A key the export masked must not overwrite the real one. */
    private fun keepMaskedKeys(incoming: JSONObject, current: JSONObject) {
        val fresh = incoming.optJSONArray("providers") ?: return
        val existing = current.optJSONArray("providers") ?: return
        for (i in 0 until fresh.length()) {
            val provider = fresh.optJSONObject(i) ?: continue
            if (KEY_FIELDS.any { provider.optString(it).let { key -> key.isNotEmpty() && key != MASK } }) continue
            val name = nameOf(provider)
            for (j in 0 until existing.length()) {
                val old = existing.optJSONObject(j) ?: continue
                if (nameOf(old) != name) continue
                KEY_FIELDS.forEach { field ->
                    old.optString(field).takeIf { it.isNotEmpty() }?.let { provider.put(field, it) }
                }
            }
        }
    }

    /** Providers are named by their preset, or by an explicit id. */
    private fun nameOf(provider: JSONObject): String =
        provider.optString("preset").ifEmpty { provider.optString("id") }

    fun settings(prefs: Prefs): JSONObject = JSONObject().apply {
        put("wakeEnabled", prefs.wakeEnabled)
        put("wakeSensitivity", prefs.wakeSensitivity)
        put("ttsPitch", prefs.ttsPitch.toDouble())
        put("ttsRate", prefs.ttsRate.toDouble())
        put("followUpMs", prefs.followUpMs)
        put("pageOffers", prefs.pageOffers)
        put("pagePort", prefs.pagePort)
        put("nightStart", prefs.nightStart)
        put("nightEnd", prefs.nightEnd)
        put("nightBrightness", prefs.nightBrightness.toDouble())
        put("presenceEnabled", prefs.presenceEnabled)
        put("presenceIntervalSec", prefs.presenceIntervalSec)
        put("greetAfterMinutes", prefs.greetAfterMinutes)
        put("greetAloud", prefs.greetAloud)
        put("kiosk", prefs.kioskEnabled)
        put("overlay", prefs.overlayEnabled)
        put("settingsPin", prefs.settingsPin)
    }

    /** Anything the document does not mention keeps its current value. */
    fun applySettings(prefs: Prefs, settings: JSONObject) {
        if (settings.has("wakeEnabled")) prefs.wakeEnabled = settings.optBoolean("wakeEnabled")
        settings.optString("wakeSensitivity").takeIf { it.isNotBlank() }?.let { prefs.wakeSensitivity = it.uppercase() }
        if (settings.has("ttsPitch")) prefs.ttsPitch = settings.optDouble("ttsPitch").toFloat()
        if (settings.has("ttsRate")) prefs.ttsRate = settings.optDouble("ttsRate").toFloat()
        if (settings.has("followUpMs")) prefs.followUpMs = settings.optInt("followUpMs")
        if (settings.has("pageOffers")) prefs.pageOffers = settings.optBoolean("pageOffers")
        if (settings.has("pagePort")) prefs.pagePort = settings.optInt("pagePort").coerceIn(1024, 65535)
        NightMode.parse(settings.optString("nightStart"))?.let { prefs.nightStart = NightMode.format(it) }
        NightMode.parse(settings.optString("nightEnd"))?.let { prefs.nightEnd = NightMode.format(it) }
        if (settings.has("nightBrightness")) {
            prefs.nightBrightness = settings.optDouble("nightBrightness").toFloat().coerceIn(0.01f, 1f)
        }
        if (settings.has("presenceEnabled")) prefs.presenceEnabled = settings.optBoolean("presenceEnabled")
        if (settings.has("presenceIntervalSec")) {
            prefs.presenceIntervalSec = settings.optInt("presenceIntervalSec").coerceIn(1, 60)
        }
        if (settings.has("greetAfterMinutes")) prefs.greetAfterMinutes = settings.optInt("greetAfterMinutes").coerceIn(0, 240)
        if (settings.has("greetAloud")) prefs.greetAloud = settings.optBoolean("greetAloud")
        if (settings.has("kiosk")) prefs.kioskEnabled = settings.optBoolean("kiosk")
        if (settings.has("overlay")) prefs.overlayEnabled = settings.optBoolean("overlay")
        if (settings.has("settingsPin")) prefs.settingsPin = settings.optString("settingsPin")
    }
}
