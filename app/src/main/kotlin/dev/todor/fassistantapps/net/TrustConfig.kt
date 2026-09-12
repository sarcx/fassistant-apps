package dev.todor.fassistantapps.net

import android.content.Context
import dev.todor.fassistantapps.R
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * The two ways of deciding which certificate authorities to believe: what the phone shipped with,
 * and that plus the root this app carries.
 *
 * Both exist so one APK can answer whether the bundled root is doing any work on this particular
 * phone. Asking that by building the app twice would mean installing twice, and these phones are
 * never plugged into anything — the question has to be answerable from the screen.
 *
 * Normal traffic does not go through here. It uses the network security config, which already
 * trusts the system store plus the bundled root.
 */
object TrustConfig {

    /** Only the authorities the platform shipped. This is what Android 7.0 would do unaided. */
    fun systemOnly(): SSLSocketFactory = factoryFor(systemStore())

    /** The same, plus the root in res/raw — what the app actually uses. */
    fun withBundledRoot(context: Context): SSLSocketFactory =
        factoryFor(systemStore().apply { setCertificateEntry("isrg-root-x1", bundledRoot(context)) })

    fun bundledRoot(context: Context): X509Certificate =
        context.resources.openRawResource(R.raw.isrg_root_x1).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    /**
     * Copies the platform's own authorities into a fresh store.
     *
     * AndroidCAStore holds both what the platform shipped and anything the user has added, under
     * "system:" and "user:" aliases. Only the first kind answers the question this check asks, so
     * a user-installed authority cannot make a 7.0 phone look like a 7.1.1 one.
     */
    private fun systemStore(): KeyStore {
        val platform = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        for (alias in platform.aliases()) {
            if (!alias.startsWith("system:")) continue
            val certificate = platform.getCertificate(alias) ?: continue
            store.setCertificateEntry(alias, certificate)
        }
        return store
    }

    private fun factoryFor(store: KeyStore): SSLSocketFactory {
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(store)
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
    }
}
