package dev.todor.fassistantapps.check

import android.content.Context
import android.os.Build
import android.os.SystemClock
import dev.todor.fassistantapps.BuildConfig
import dev.todor.fassistantapps.catalogue.ReleaseManifest
import dev.todor.fassistantapps.catalogue.sha256
import dev.todor.fassistantapps.net.Http
import dev.todor.fassistantapps.net.TrustConfig
import dev.todor.fassistantapps.net.TrustStore
import java.io.File
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import javax.net.ssl.SSLHandshakeException

/**
 * Milestone one: does a real GitHub release actually download on this phone?
 *
 * Everything the catalogue does rests on that, and on Android 7.0 it is not a given — the host
 * serving the bytes has a certificate chaining to a root that release never shipped.
 *
 * The same fetch runs twice, once believing only the authorities the phone came with and once
 * also believing the root inside this APK. Comparing the two says whether the bundled root is
 * load-bearing on this phone or merely redundant, from one install, with nothing plugged in.
 */
class DownloadCheck(private val context: Context) {

    private val manifestUrl =
        "https://github.com/${BuildConfig.CATALOGUE_OWNER}/fassistant-android/releases/latest/download/update.json"

    private val listingUrl =
        "https://api.github.com/users/${BuildConfig.CATALOGUE_OWNER}/repos?per_page=1"

    fun run(report: (String) -> Unit) {
        report("fassistant-apps ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        report("Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}, ${Build.MODEL}")

        val phoneHasRoot = runCatching { TrustStore.hasIsrgRootX1() }
        report(
            when (phoneHasRoot.getOrNull()) {
                true -> "This phone's own certificates include ISRG Root X1"
                false -> "This phone's own certificates do NOT include ISRG Root X1"
                null -> "Could not read this phone's certificates: ${reason(phoneHasRoot.exceptionOrNull())}"
            }
        )

        runCatching { TrustConfig.bundledRoot(context) }
            .onSuccess {
                val expires = SimpleDateFormat("yyyy-MM-dd", Locale.UK).format(it.notAfter)
                report("The app carries ${it.subjectX500Principal.name.substringAfter("CN=").substringBefore(",")}, good until $expires")
            }
            .onFailure { report("The app could not read its own bundled certificate: ${reason(it)}") }

        report("")

        if (!step(report, "Reaching api.github.com") { "${Http.text(listingUrl).length} bytes of JSON" }) return

        // The same fetch, twice, differing only in which authorities are believed.
        val withoutBundled = attempt(report, "Fetching a release manifest, phone's certificates only") {
            Http.text(manifestUrl, ssl = TrustConfig.systemOnly()).length
        }
        val withBundled = attempt(report, "Fetching it again, adding the root this app carries") {
            Http.text(manifestUrl, ssl = TrustConfig.withBundledRoot(context)).length
        }

        if (withBundled == null) {
            report("")
            report("FAILED — even with the bundled root the manifest would not load.")
            report("That is not the certificate problem. Check the network and try again.")
            return
        }

        report("")

        val manifest = attempt(report, "Reading what that manifest says") {
            ReleaseManifest.parse(Http.text(manifestUrl))
        } ?: return
        report("  ${manifest.label ?: "fassistant"} ${manifest.versionName} (${manifest.versionCode})")
        report("  published checksum ${manifest.sha256.take(16)}…")

        // Resolved against the manifest's own address, so the app never has to know where GitHub
        // keeps the bytes — only where the manifest was.
        val apkUrl = URL(URL(manifestUrl), manifest.apkUrl).toString()
        val target = File(context.cacheDir, "check-${manifest.versionCode}.apk")

        val downloaded = attempt(report, "Downloading ${manifest.apkUrl}") {
            val started = SystemClock.elapsedRealtime()
            Http.open(apkUrl).use { input -> target.outputStream().use { input.copyTo(it) } }
            report("  ${target.length() / 1024} KB in ${SystemClock.elapsedRealtime() - started} ms")
            target.length()
        }

        if (downloaded == null || downloaded == 0L) {
            target.delete()
            report("")
            report("FAILED — the APK did not download.")
            return
        }

        val actual = sha256(target)
        report("  computed checksum ${actual.take(16)}…")
        target.delete()

        report("")
        if (actual != manifest.sha256) {
            report("FAILED — the download does not match the checksum the release published.")
            return
        }

        report("PASSED — a real release downloaded and matched its published checksum.")
        report(
            if (withoutBundled == null) {
                "This phone NEEDS the bundled root: the same fetch failed without it. Keep it in every build."
            } else {
                "This phone would have managed without the bundled root. It stays anyway, for older phones."
            }
        )
    }

    /** Runs one step, reporting either what it produced or why it did not. */
    private fun <T> attempt(report: (String) -> Unit, label: String, work: () -> T): T? {
        report("$label…")
        return try {
            work()
        } catch (e: Exception) {
            report("  FAILED — ${reason(e)}")
            if (e is SSLHandshakeException) {
                report("  (a rejected certificate — which is what this check is looking for)")
            }
            null
        }
    }

    private fun step(report: (String) -> Unit, label: String, work: () -> String): Boolean {
        val outcome = attempt(report, label, work) ?: return false
        report("  $outcome")
        return true
    }

    private fun reason(e: Throwable?): String =
        e?.let { "${it.javaClass.simpleName}: ${it.message ?: "no detail"}" } ?: "unknown"
}
