package dev.todor.fassistantapps.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import dev.todor.fassistantapps.R
import dev.todor.fassistantapps.catalogue.Catalogue
import dev.todor.fassistantapps.catalogue.CatalogueEntry
import dev.todor.fassistantapps.catalogue.sha256
import dev.todor.fassistantapps.net.Http
import java.io.File
import java.net.URL

/** A download that arrived but must not be installed. The message is written for the screen. */
class Refused(message: String) : Exception(message)

/**
 * Fetches an app's released APK and refuses to hand it to Android unless two things hold: the bytes
 * match the checksum its manifest published, and the APK is signed by the same key as this app.
 *
 * The second check works across apps because the whole family is signed with one key, and it is
 * what tells a real Fassistant release from anything else at that address. Android would reject a
 * mismatched update anyway, but checking here makes the failure a sentence instead of an opaque
 * installer error — and it covers first installs, which Android has nothing to compare against.
 */
object Installer {

    fun fetch(context: Context, entry: CatalogueEntry): File {
        val manifest = checkNotNull(entry.manifest) { "${entry.repo} has no release to install" }
        // Resolved against the manifest's own address, so the app never has to know where GitHub
        // keeps the bytes — only where the manifest was.
        val apkUrl = URL(URL(Catalogue.manifestUrl(entry.repo)), manifest.apkUrl).toString()
        val apk = File(downloads(context), "${entry.repo}-${manifest.versionCode}.apk")
        Http.open(apkUrl).use { input -> apk.outputStream().use { input.copyTo(it) } }

        val problem = when {
            sha256(apk) != manifest.sha256 -> R.string.install_checksum_failed
            !signedLikeUs(context, apk) -> R.string.install_signature_failed
            else -> return apk
        }
        apk.delete()
        throw Refused(context.getString(problem))
    }

    /**
     * Opens Android's own install screen on the APK, which asks the user and does the install.
     *
     * Not a PackageInstaller session, although that is the newer route. Xiaomi's Android, with its
     * default "MIUI optimization" on, refuses sessions from ordinary apps with
     * "INSTALL_FAILED_INTERNAL_ERROR: Permission Denied", and the only cure on the phone is a
     * developer setting. The install screen is allowed there, and works the same everywhere else.
     *
     * There is deliberately no check beforehand for permission to install apps. On Android 8 and
     * later, canRequestPackageInstalls() answers false for any app targeting below 26 — this one
     * targets 25 — however the setting is set, so asking first sends the user to the settings page
     * on every tap. The install screen checks the real setting instead, and when it is off says so
     * and links to it, then carries on with the install.
     *
     * The APK stays on disk, because the install screen reads it whenever the user confirms.
     * [clearDownloads] removes it later.
     */
    fun handOver(context: Context, apk: File) {
        val address = Uri.parse("content://${context.packageName}.apks/${apk.name}")
        val install = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(address, ApkProvider.MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(install)
    }

    /** Where downloaded APKs wait for the install screen. [ApkProvider] serves nothing else. */
    fun downloads(context: Context): File = File(context.cacheDir, "apks").apply { mkdirs() }

    /** Called at startup, when no install screen from this run can still be reading one. */
    fun clearDownloads(context: Context) {
        downloads(context).listFiles()?.forEach { it.delete() }
    }

    private fun signedLikeUs(context: Context, apk: File): Boolean {
        @Suppress("DEPRECATION")
        val flag = PackageManager.GET_SIGNATURES

        @Suppress("DEPRECATION")
        val candidate = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flag)?.signatures

        @Suppress("DEPRECATION")
        val running = context.packageManager.getPackageInfo(context.packageName, flag).signatures

        if (candidate.isNullOrEmpty() || running.isNullOrEmpty()) return false
        return fingerprints(candidate) == fingerprints(running)
    }

    private fun fingerprints(signatures: Array<Signature>): Set<String> =
        signatures.map { sha256(it.toByteArray()) }.toSet()
}
