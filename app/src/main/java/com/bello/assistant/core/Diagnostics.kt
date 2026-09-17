package com.bello.assistant.core

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.bello.assistant.net.HttpClients
import com.bello.assistant.voice.VoskRuntime
import okhttp3.Request
import org.vosk.Model

/** Platform self-check for the risky pieces proven in the spikes (TLS, Vosk native + model). */
class Diagnostics(private val context: Context) {

    data class Check(val name: String, val ok: Boolean, val detail: String)

    fun run(loadModel: Boolean = true): List<Check> {
        val checks = mutableListOf<Check>()
        checks += Check("device", true,
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.joinToString()}")
        checks += https("https Open-Meteo", "https://api.open-meteo.com/v1/forecast?latitude=43.66&longitude=6.92&current=temperature_2m")
        checks += https("https Let's Encrypt root", "https://valid-isrgrootx1.letsencrypt.org/")
        checks += https("https Gemini API", "https://generativelanguage.googleapis.com/v1beta/models", okCodes = 200..499)
        checks += step("vosk native") { VoskRuntime.load(); "loaded" }
        val present = VoskRuntime.isModelPresent(context)
        checks += Check("vosk model files", present, VoskRuntime.modelDir(context).absolutePath)
        if (present && loadModel) {
            checks += step("vosk model load") {
                val t0 = SystemClock.elapsedRealtime()
                Model(VoskRuntime.modelDir(context).absolutePath).close()
                "${SystemClock.elapsedRealtime() - t0} ms"
            }
        }
        checks.forEach { FileLog.i(TAG, "CHECK ${if (it.ok) "OK  " else "FAIL"} ${it.name}: ${it.detail}") }
        FileLog.i(TAG, "SELFCHECK_DONE ok=${checks.count { it.ok }}/${checks.size}")
        return checks
    }

    private fun https(name: String, url: String, okCodes: IntRange = 200..299) = step(name) {
        val t0 = SystemClock.elapsedRealtime()
        HttpClients.base(context).newCall(Request.Builder().url(url).build()).execute().use { r ->
            val detail = "http=${r.code} tls=${r.handshake?.tlsVersion?.javaName} ${SystemClock.elapsedRealtime() - t0} ms"
            check(r.code in okCodes) { detail }
            detail
        }
    }

    private fun step(name: String, block: () -> String): Check = try {
        Check(name, true, block())
    } catch (t: Throwable) {
        FileLog.w(TAG, "check '$name' failed", t)
        Check(name, false, "${t.javaClass.simpleName}: ${t.message}")
    }

    private companion object { const val TAG = "diag" }
}
