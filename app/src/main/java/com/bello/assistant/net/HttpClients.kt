package com.bello.assistant.net

import android.content.Context
import com.bello.assistant.core.FileLog
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.security.Security
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/**
 * Single source of HTTPS clients: Conscrypt (TLS 1.3) + bundled CA roots from assets/cacert.pem.
 * The device's system CA store already rejects Let's Encrypt-signed sites (SP-04), so never
 * create an OkHttpClient without going through here.
 */
object HttpClients {
    private const val TAG = "net"
    @Volatile private var base: OkHttpClient? = null

    /** Installs Conscrypt as the first security provider. Call once from Application.onCreate. */
    fun installProvider() {
        if (Security.getProvider("Conscrypt") == null) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
            FileLog.i(TAG, "Conscrypt installed as provider #1")
        }
    }

    fun base(context: Context): OkHttpClient = base ?: synchronized(this) {
        base ?: build(context).also { base = it }
    }

    private fun build(context: Context): OkHttpClient {
        val pem = context.assets.open("cacert.pem").bufferedReader().use { it.readText() }
        val certs = PemTrustStore.parse(pem)
        val trustManager = PemTrustStore.trustManager(PemTrustStore.keyStore(certs))
        val sslContext = SSLContext.getInstance("TLS", Conscrypt.newProvider()).apply {
            init(null, arrayOf(trustManager), null)
        }
        FileLog.i(TAG, "HTTPS client built with ${certs.size} bundled CA roots")
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // CLEARTEXT is only for a local test endpoint reached through `adb reverse`;
            // every real provider is HTTPS.
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
