package com.bello.assistant.net

import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds a trust store from a PEM bundle (curl.se `cacert.pem`), ignoring the comment text
 * between certificates. Used instead of the device's 2017 system CA store (SP-04).
 */
object PemTrustStore {
    private val CERT = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----")

    fun parse(pem: String): List<X509Certificate> {
        val cf = CertificateFactory.getInstance("X.509")
        return CERT.findAll(pem).map { cf.generateCertificate(it.value.byteInputStream()) as X509Certificate }.toList()
    }

    fun keyStore(certs: List<X509Certificate>): KeyStore =
        KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certs.forEachIndexed { i, cert -> setCertificateEntry("ca$i", cert) }
        }

    fun trustManager(keyStore: KeyStore): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(keyStore)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }
}
