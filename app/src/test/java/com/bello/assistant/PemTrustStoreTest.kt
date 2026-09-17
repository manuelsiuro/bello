package com.bello.assistant

import com.bello.assistant.net.PemTrustStore
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PemTrustStoreTest {
    private val bundle = File("src/main/assets/cacert.pem").readText()

    @Test
    fun parsesBundledCaIgnoringCommentText() {
        val certs = PemTrustStore.parse(bundle)
        assertTrue("expected > 100 roots, got ${certs.size}", certs.size > 100)
    }

    @Test
    fun bundleContainsRootsNeededByTargetServices() {
        val subjects = PemTrustStore.parse(bundle).map { it.subjectX500Principal.name }
        // Let's Encrypt (Open-Meteo, franceinfo) and Google Trust Services (Gemini, Groq, Anthropic).
        assertTrue(subjects.any { "ISRG Root X1" in it })
        assertTrue(subjects.any { "GTS Root R1" in it })
        assertTrue(subjects.any { "GTS Root R4" in it })
    }

    @Test
    fun buildsTrustManagerWithAcceptedIssuers() {
        val tm = PemTrustStore.trustManager(PemTrustStore.keyStore(PemTrustStore.parse(bundle)))
        assertTrue(tm.acceptedIssuers.size > 100)
    }
}
