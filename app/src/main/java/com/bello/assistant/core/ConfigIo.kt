package com.bello.assistant.core

import android.content.Context
import com.bello.assistant.assistant.Feature
import com.bello.assistant.llm.LlmConfig
import com.bello.assistant.llm.ProviderConfig
import org.json.JSONArray
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
     * Keys live in two lists: the chat providers and the picture services (`images.providers`).
     */
    fun mask(root: JSONObject) {
        keyed(root).forEach { provider ->
            KEY_FIELDS.forEach { field ->
                if (provider.optString(field).isNotEmpty()) provider.put(field, MASK)
            }
        }
    }

    /** True when nothing in this document looks like a secret. */
    fun isMasked(json: String): Boolean {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return true
        return keyed(root).none { provider ->
            KEY_FIELDS.any { provider.optString(it).let { key -> key.isNotEmpty() && key != MASK } }
        }
    }

    /** Every entry of the document that may carry a key. */
    private fun keyed(root: JSONObject): List<JSONObject> = listOfNotNull(
        root.optJSONArray("providers"),
        root.optJSONObject("images")?.optJSONArray("providers"),
    ).flatMap { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }

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

    /** A key the export masked must not overwrite the real one — in either list. */
    private fun keepMaskedKeys(incoming: JSONObject, current: JSONObject) {
        keepMaskedKeys(incoming.optJSONArray("providers"), current.optJSONArray("providers"))
        keepMaskedKeys(
            incoming.optJSONObject("images")?.optJSONArray("providers"),
            current.optJSONObject("images")?.optJSONArray("providers"),
        )
    }

    private fun keepMaskedKeys(fresh: JSONArray?, existing: JSONArray?) {
        if (fresh == null || existing == null) return
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

    // --- One switch per provider, on the settings screen ---------------------------------------

    /**
     * A provider entry of config.json as the settings screen shows it. [keyless] is a service that
     * answers without a key but does less (Pollinations: a weaker model and its logo).
     */
    data class ProviderSwitch(val name: String, val enabled: Boolean, val hasKey: Boolean, val keyless: Boolean = false)

    fun providerSwitches(context: Context): List<ProviderSwitch> = providerSwitches(readConfig(context))

    /** What the file says, with a preset's own default when `enabled` is left out (Gemini Web is off). */
    fun providerSwitches(root: JSONObject): List<ProviderSwitch> =
        entries(root.optJSONArray("providers")).mapNotNull { provider ->
            val name = nameOf(provider).ifEmpty { return@mapNotNull null }
            val preset = LlmConfig.PRESETS[provider.optString("preset", provider.optString("id"))]
            val keyless = preset?.type == ProviderConfig.Type.GEMINI_WEB ||
                provider.optString("type").lowercase().let { it == "gemini-web" || it == "geminiweb" || it == "web" }
            ProviderSwitch(
                name = name,
                enabled = provider.optBoolean("enabled", preset?.enabled ?: true),
                hasKey = keyless || hasKey(provider),
            )
        }

    /** Writes the switch into config.json; the caller reloads through `MainActivity.reloadEverything()`. */
    fun setProviderEnabled(context: Context, name: String, on: Boolean): Boolean =
        writeSwitch(context, "PROVIDER", name, on) { setProviderEnabled(it, name, on) }

    /** Only `enabled` of the named entry changes; keys and everything else are left as they are. */
    fun setProviderEnabled(root: JSONObject, name: String, on: Boolean): Boolean =
        setEnabled(root.optJSONArray("providers"), name, on, ::nameOf)

    // --- One switch per picture service (`images.providers`) ---------------------------------

    fun imageSwitches(context: Context): List<ProviderSwitch> = imageSwitches(readConfig(context))

    /**
     * Named the way `ImageConfig.parse` names them. Cloudflare is usable only with a key and an
     * account id; Pollinations answers without a key, so it always has a switch.
     */
    fun imageSwitches(root: JSONObject): List<ProviderSwitch> =
        entries(root.optJSONObject("images")?.optJSONArray("providers")).mapNotNull { service ->
            val name = imageNameOf(service).ifEmpty { return@mapNotNull null }
            val preset = service.optString("preset", service.optString("id")).lowercase()
            val keyed = hasKey(service)
            ProviderSwitch(
                name = name,
                enabled = service.optBoolean("enabled", true),
                hasKey = if (preset == "cloudflare") keyed && service.optString("accountId").isNotBlank() else true,
                keyless = preset == "pollinations" && !keyed,
            )
        }

    /** Writes the switch into config.json; the caller reloads through `MainActivity.reloadEverything()`. */
    fun setImageEnabled(context: Context, name: String, on: Boolean): Boolean =
        writeSwitch(context, "IMAGE_PROVIDER", name, on) { setImageEnabled(it, name, on) }

    fun setImageEnabled(root: JSONObject, name: String, on: Boolean): Boolean =
        setEnabled(root.optJSONObject("images")?.optJSONArray("providers"), name, on, ::imageNameOf)

    private fun readConfig(context: Context): JSONObject =
        runCatching { JSONObject(AppConfig.file(context).readText()) }.getOrElse { JSONObject() }

    private fun writeSwitch(context: Context, what: String, name: String, on: Boolean, change: (JSONObject) -> Boolean): Boolean {
        val file = AppConfig.file(context)
        val root = runCatching { JSONObject(file.readText()) }.getOrElse { return false }
        if (!change(root)) return false
        return runCatching { file.writeText(root.toString(2)) }
            .onSuccess { FileLog.i(TAG, "${what}_SWITCHED $name enabled=$on") }
            .onFailure { FileLog.w(TAG, "${what}_SWITCH_FAILED $name", it) }
            .isSuccess
    }

    private fun setEnabled(array: JSONArray?, name: String, on: Boolean, nameOf: (JSONObject) -> String): Boolean {
        val entry = entries(array).firstOrNull { nameOf(it) == name } ?: return false
        entry.put("enabled", on)
        return true
    }

    private fun entries(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    private fun hasKey(entry: JSONObject) = KEY_FIELDS.any { entry.optString(it).isNotBlank() }

    /** A picture service is named by its id, or by its preset — as `ImageConfig.parse` does. */
    private fun imageNameOf(service: JSONObject): String =
        service.optString("id").ifEmpty { service.optString("preset") }

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
        put("pageImages", prefs.pageImages)
        put("disabledFeatures", JSONArray(prefs.disabledFeatures.map { it.key }.sorted()))
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

    /** An array of keys, or null when the document does not mention them; unknown keys are dropped. */
    fun disabledFeatures(settings: JSONObject): Set<Feature>? {
        val array = settings.optJSONArray("disabledFeatures") ?: return null
        return (0 until array.length()).mapNotNull { Feature.byKey(array.optString(it)) }.toSet()
    }

    /** Anything the document does not mention keeps its current value. */
    fun applySettings(prefs: Prefs, settings: JSONObject) {
        if (settings.has("wakeEnabled")) prefs.wakeEnabled = settings.optBoolean("wakeEnabled")
        settings.optString("wakeSensitivity").takeIf { it.isNotBlank() }?.let { prefs.wakeSensitivity = it.uppercase() }
        if (settings.has("ttsPitch")) prefs.ttsPitch = settings.optDouble("ttsPitch").toFloat()
        if (settings.has("ttsRate")) prefs.ttsRate = settings.optDouble("ttsRate").toFloat()
        if (settings.has("followUpMs")) prefs.followUpMs = settings.optInt("followUpMs")
        if (settings.has("pageOffers")) prefs.pageOffers = settings.optBoolean("pageOffers")
        if (settings.has("pageImages")) prefs.pageImages = settings.optBoolean("pageImages")
        disabledFeatures(settings)?.let { prefs.disabledFeatures = it }
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
