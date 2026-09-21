package com.bello.assistant.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.bello.assistant.assistant.Feature
import com.bello.assistant.core.ConfigIo
import com.bello.assistant.core.FileLog
import com.bello.assistant.core.NightMode
import com.bello.assistant.core.Prefs

/**
 * The hidden settings screen (FR-SET-01..03, FR-ON-07): a long press on the face opens it, behind a
 * PIN if one is set. Everything here is also in the configuration file — this is for changing one
 * thing without a Mac, and for the test buttons, which are the fastest way to find out which part
 * of a silent tablet is broken.
 */
@SuppressLint("ViewConstructor")
class SettingsView(
    context: Context,
    private val prefs: Prefs,
    private val host: Host,
) : FrameLayout(context) {

    /** What the settings need from the running app. */
    interface Host {
        fun onSettingsChanged(what: String)
        fun testProvider()
        fun testVoice()
        fun testMicrophone()
        fun testWakeWord()
        fun providerLines(): List<String>
        fun providerSwitches(): List<ConfigIo.ProviderSwitch>
        /** False when config.json could not be written; true means the gateway must be rebuilt. */
        fun setProviderEnabled(name: String, on: Boolean): Boolean
        fun memoryLine(): String
        fun forgetEverything()
        fun close()
    }

    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(16), dp(24), dp(24))
    }
    private var pinEntry: EditText? = null
    private var status: TextView? = null

    init {
        setBackgroundColor(Color.rgb(16, 24, 34))
        visibility = View.GONE
        isClickable = true   // so taps do not fall through to the face
        addView(ScrollView(context).apply { addView(body) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Long press on the face: straight in, or the PIN first (FR-ON-07). */
    fun open() {
        if (prefs.settingsPin.isBlank()) showSettings() else askPin()
        visibility = View.VISIBLE
    }

    fun hide() {
        visibility = View.GONE
        body.removeAllViews()
    }

    val isOpen get() = visibility == View.VISIBLE

    private fun askPin() {
        body.removeAllViews()
        title("Code")
        val entry = EditText(context).apply {
            hint = "Code à 4 chiffres"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            textSize = 24f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(120, 255, 255, 255))
        }
        pinEntry = entry
        body.addView(entry)
        row(button("Ouvrir") {
            if (entry.text.toString() == prefs.settingsPin) showSettings()
            else {
                entry.setText("")
                entry.hint = "Ce n'est pas le bon code"
            }
        }, button("Retour") { host.close() })
    }

    private fun showSettings() {
        body.removeAllViews()
        title("Réglages")

        section("Mot de réveil")
        toggle("Écouter « Bello »", prefs.wakeEnabled) {
            prefs.wakeEnabled = it
            host.onSettingsChanged("wake")
        }
        choice("Sensibilité", listOf("LOW", "NORMAL", "HIGH"), prefs.wakeSensitivity) {
            prefs.wakeSensitivity = it
            host.onSettingsChanged("wake")
        }

        section("Voix")
        number("Hauteur", prefs.ttsPitch, 0.1f, 0.5f, 2.5f) {
            prefs.ttsPitch = it
            host.onSettingsChanged("voice")
        }
        number("Vitesse", prefs.ttsRate, 0.05f, 0.5f, 2.0f) {
            prefs.ttsRate = it
            host.onSettingsChanged("voice")
        }
        number("Relance (secondes)", prefs.followUpMs / 1000f, 1f, 0f, 20f) {
            prefs.followUpMs = (it * 1000).toInt()
        }

        // Read on every question: a switch applies at once, without a reload (docs/features.md).
        section("Fonctions")
        Feature.values().forEach { feature ->
            toggle(feature.label.replaceFirstChar { it.uppercase() }, prefs.isEnabled(feature)) {
                prefs.setEnabled(feature, it)
                FileLog.i("settings", "FEATURE ${feature.key}=${if (it) "on" else "off"}")
            }
        }
        toggle("Détails sur le téléphone (code QR)", prefs.pageOffers) { prefs.pageOffers = it }
        note("Une fonction désactivée le dit quand on la demande. Sans la discussion, Bello ne répond qu'avec ses outils.")

        section("Nuit")
        time("Début", prefs.nightStart) { prefs.nightStart = it; host.onSettingsChanged("night") }
        time("Fin", prefs.nightEnd) { prefs.nightEnd = it; host.onSettingsChanged("night") }
        number("Luminosité", prefs.nightBrightness, 0.05f, 0.01f, 1f) {
            prefs.nightBrightness = it
            host.onSettingsChanged("night")
        }

        section("Présence")
        toggle("Caméra", prefs.presenceEnabled) {
            prefs.presenceEnabled = it
            host.onSettingsChanged("presence")
        }
        number("Regarder toutes les (s)", prefs.presenceIntervalSec.toFloat(), 1f, 1f, 30f) {
            prefs.presenceIntervalSec = it.toInt()
            host.onSettingsChanged("presence")
        }
        number("Saluer après (min)", prefs.greetAfterMinutes.toFloat(), 1f, 0f, 120f) {
            prefs.greetAfterMinutes = it.toInt()
            host.onSettingsChanged("presence")
        }
        toggle("Saluer à voix haute", prefs.greetAloud) { prefs.greetAloud = it }
        note("Aucune image n'est enregistrée ni envoyée.")

        section("Fournisseurs")
        host.providerLines().forEach { note(it) }
        host.providerSwitches().forEach { provider ->
            if (!provider.hasKey) note("${provider.name} : sans clé")
            else toggle(provider.name, provider.enabled) {
                if (host.setProviderEnabled(provider.name, it)) host.onSettingsChanged("providers")
                else say("Je n'ai pas pu écrire la configuration.")
            }
        }
        note("Les clés et l'ordre se modifient dans le fichier de configuration.")

        section("Mémoire")
        note(host.memoryLine())
        row(button("Tout oublier") {
            host.forgetEverything()
            say("Mémoire effacée.")
        })

        section("Essais")
        row(button("Fournisseur") { host.testProvider() }, button("Voix") { host.testVoice() })
        row(button("Micro") { host.testMicrophone() }, button("Mot de réveil") { host.testWakeWord() })

        section("Configuration")
        row(button("Exporter") {
            say("Exporté : ${ConfigIo.writeExport(context, includeKeys = false).name} (sans les clés)")
        }, button("Exporter avec les clés") {
            say("Exporté : ${ConfigIo.writeExport(context, includeKeys = true).name}")
        })
        row(button("Importer") {
            val result = ConfigIo.importFile(context, null)
            say(result.message)
            if (result.ok) host.onSettingsChanged("import")
        })

        section("Divers")
        toggle("Garder le visage au premier plan", prefs.kioskEnabled) { prefs.kioskEnabled = it }
        toggle("Infos de débogage", prefs.overlayEnabled) {
            prefs.overlayEnabled = it
            host.onSettingsChanged("overlay")
        }
        time("Code d'accès (vide = aucun)", prefs.settingsPin, hint = "1234") { prefs.settingsPin = it }

        section("Sources")
        note(
            "Météo et qualité de l'air : Open-Meteo (CC BY 4.0). " +
                "Encyclopédie : Wikipédia en français (CC BY-SA). " +
                "Jours fériés, vacances scolaires, prix des carburants : services publics " +
                "(Licence Ouverte 2.0). Blagues : JokeAPI."
        )

        status = TextView(context).apply {
            textSize = 16f
            setTextColor(Color.rgb(255, 214, 0))
            setPadding(0, dp(12), 0, 0)
        }
        body.addView(status)
        row(button("Fermer") { host.close() })
    }

    private fun say(message: String) {
        status?.text = message
        FileLog.i("settings", message)
    }

    // --- Small view helpers ------------------------------------------------------------------

    private fun title(text: String) = body.addView(TextView(context).apply {
        this.text = text
        textSize = 30f
        setTextColor(Color.WHITE)
        setPadding(0, 0, 0, dp(8))
    })

    private fun section(text: String) = body.addView(TextView(context).apply {
        this.text = text
        textSize = 20f
        setTextColor(Color.rgb(255, 214, 0))
        setPadding(0, dp(18), 0, dp(4))
    })

    private fun note(text: String) = body.addView(TextView(context).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.argb(170, 255, 255, 255))
    })

    private fun label(text: String) = TextView(context).apply {
        this.text = text
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun button(text: String, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.WHITE)
        background = rounded(Color.argb(60, 255, 255, 255))
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = body.addView(LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(4), 0, dp(4))
        views.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(8), 0) }) }
    })

    private fun toggle(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
        var current = value
        lateinit var action: Button
        action = button(if (current) "oui" else "non") {
            current = !current
            action.text = if (current) "oui" else "non"
            onChange(current)
        }
        row(label(text), action)
    }

    private fun choice(text: String, options: List<String>, value: String, onChange: (String) -> Unit) {
        val buttons = ArrayList<Button>()
        options.forEach { option ->
            buttons += button(option.lowercase()) {
                onChange(option)
                buttons.forEach { it.background = rounded(Color.argb(60, 255, 255, 255)) }
                buttons[options.indexOf(option)].background = rounded(Color.argb(150, 255, 214, 0))
            }
        }
        buttons.getOrNull(options.indexOfFirst { it.equals(value, true) })
            ?.let { it.background = rounded(Color.argb(150, 255, 214, 0)) }
        row(label(text), *buttons.toTypedArray())
    }

    /** A value with a minus and a plus: no keyboard, and nothing to mistype. */
    private fun number(text: String, value: Float, step: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
        var current = value
        val shown = label(format(current))
        fun set(next: Float) {
            current = next.coerceIn(min, max)
            shown.text = format(current)
            onChange(current)
        }
        row(label(text), button("−") { set(current - step) }, shown, button("+") { set(current + step) })
    }

    private fun format(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else "%.2f".format(value)

    private fun time(text: String, value: String, hint: String = "23:00", onChange: (String) -> Unit) {
        val entry = EditText(context).apply {
            setText(value)
            this.hint = hint
            textSize = 18f
            setSingleLine()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(120, 255, 255, 255))
            setOnFocusChangeListener { _, focused ->
                if (!focused) {
                    val text = this.text.toString().trim()
                    // A time is normalised, a PIN is taken as typed.
                    val clean = if (hint.contains(":")) NightMode.parse(text)?.let(NightMode::format) else text
                    if (clean != null) {
                        setText(clean)
                        onChange(clean)
                    } else setText(value)
                }
            }
        }
        row(label(text), entry)
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(12).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
