package dev.todor.fassistantapps.net

import dev.todor.fassistantapps.BuildConfig
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * The one way this app talks to the network.
 *
 * Redirects are followed by hand rather than by HttpURLConnection, which stops following as soon
 * as the scheme changes. A "latest release" address is a chain of them that ends on a different
 * host, so an implementation that gave up at the first hop would fetch nothing at all.
 */
object Http {

    private const val TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 5

    // GitHub's API refuses requests that do not say who is asking.
    private val USER_AGENT = "fassistant-apps/" + BuildConfig.VERSION_NAME

    fun text(url: String, accept: String = "*/*", ssl: SSLSocketFactory? = null): String =
        open(url, accept, ssl).use { it.readBytes().decodeToString() }

    /**
     * [ssl] overrides which certificate authorities to believe, and only the download check passes
     * it. Everything else leaves it null and gets the network security config's answer, which is
     * the system store plus the root bundled in this APK.
     */
    fun open(url: String, accept: String = "*/*", ssl: SSLSocketFactory? = null): InputStream {
        var target = url
        repeat(MAX_REDIRECTS) {
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", USER_AGENT)
                // Applied per hop: the redirect that matters lands on a different host from the
                // one the request started on, and that is exactly the hop being tested.
                if (ssl != null && this is HttpsURLConnection) sslSocketFactory = ssl
            }
            when (val status = connection.responseCode) {
                in 200..299 -> return connection.inputStream

                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("redirect with no target")
                    connection.disconnect()
                    target = URL(URL(target), location).toString()
                }

                else -> {
                    connection.disconnect()
                    throw IllegalStateException("${URL(target).host} said $status")
                }
            }
        }
        throw IllegalStateException("too many redirects")
    }
}
