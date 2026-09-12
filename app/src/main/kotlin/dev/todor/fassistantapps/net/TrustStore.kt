package dev.todor.fassistantapps.net

import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Reads the phone's own certificate store, to answer one question: is ISRG Root X1 in it?
 *
 * Android added that root in 7.1.1. On 7.0 it is missing, and since GitHub's release assets chain
 * up to it, every download fails there unless the app supplies the root itself — which is why the
 * APK carries a copy.
 *
 * Deliberately reads AndroidCAStore rather than asking for the default trust managers. Those
 * reflect the app's own network security config, so on a 7.0 phone running a build that bundles
 * the root they would answer "present" and hide the very thing worth knowing.
 */
object TrustStore {

    fun hasIsrgRootX1(): Boolean = systemCertificates().any {
        it.subjectX500Principal.name.contains("CN=ISRG Root X1")
    }

    private fun systemCertificates(): List<X509Certificate> {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        return store.aliases().toList()
            // User-added authorities live in the same store under a "user:" prefix. Only what the
            // platform shipped counts for this question.
            .filter { it.startsWith("system:") }
            .mapNotNull { store.getCertificate(it) as? X509Certificate }
    }
}
