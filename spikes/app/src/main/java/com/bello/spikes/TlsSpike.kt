package com.bello.spikes

import android.content.Intent
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import org.conscrypt.Conscrypt
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** SP-04: HTTPS to all planned endpoints with system TLS vs Conscrypt, system trust vs bundled CA. */
class TlsSpike(private val act: MainActivity) : Spike {
    private val id = "sp04"

    private val urls = listOf(
        "https://generativelanguage.googleapis.com/v1beta/models",
        "https://api.groq.com/openai/v1/models",
        "https://api.mistral.ai/v1/models",
        "https://api.cerebras.ai/v1/models",
        "https://openrouter.ai/api/v1/models",
        "https://api.anthropic.com/v1/models",
        "https://api.open-meteo.com/v1/forecast?latitude=43.66&longitude=6.92&current=temperature_2m",
        "https://www.lemonde.fr/rss/une.xml",
        "https://www.francetvinfo.fr/titres.rss",
        "https://gemini.google.com/app",
        "https://valid-isrgrootx1.letsencrypt.org/",
    )

    override fun command(action: String, intent: Intent) {
        if (action != "run") return
        Thread { runAll() }.start()
    }

    private fun runAll() {
        val conscrypt = Conscrypt.newProvider()
        val systemTm = trustManager(null)
        val bundledTm = trustManager(bundledKeyStore())
        val modes = listOf(
            Triple("system-tls+system-ca", SSLContext.getInstance("TLS"), systemTm),
            Triple("conscrypt+system-ca", SSLContext.getInstance("TLS", conscrypt), systemTm),
            Triple("conscrypt+bundled-ca", SSLContext.getInstance("TLS", conscrypt), bundledTm),
        )
        var pass = 0
        var total = 0
        for ((name, ctx, tm) in modes) {
            ctx.init(null, arrayOf(tm), null)
            val client = OkHttpClient.Builder()
                .sslSocketFactory(ctx.socketFactory, tm)
                .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
            for (url in urls) {
                val t0 = System.currentTimeMillis()
                val line = runCatching {
                    client.newCall(Request.Builder().url(url).header("User-Agent", "BelloSpike/0.1").build())
                        .execute().use { r ->
                            val hs = r.handshake
                            "OK http=${r.code} proto=${r.protocol} tls=${hs?.tlsVersion?.javaName} cipher=${hs?.cipherSuite?.javaName} bytes=${r.body?.bytes()?.size}"
                        }
                }.getOrElse { "FAIL ${it.javaClass.simpleName}: ${it.message?.take(160)}" }
                if (name == "conscrypt+bundled-ca") { total++; if (line.startsWith("OK")) pass++ }
                Report.log(id, "TLS mode=$name url=$url ms=${System.currentTimeMillis() - t0} $line")
            }
            client.connectionPool.evictAll()
        }
        Report.log(id, "SUMMARY conscrypt+bundled-ca pass=$pass/$total")
    }

    private fun trustManager(ks: KeyStore?): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun bundledKeyStore(): KeyStore {
        val pem = act.assets.open("cacert.pem").bufferedReader().readText()
        val cf = CertificateFactory.getInstance("X.509")
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----").findAll(pem)
            .forEachIndexed { i, m ->
                val cert = cf.generateCertificate(m.value.byteInputStream()) as X509Certificate
                ks.setCertificateEntry("ca$i", cert)
            }
        Report.log(id, "BUNDLED_CA count=${ks.size()}")
        return ks
    }
}
