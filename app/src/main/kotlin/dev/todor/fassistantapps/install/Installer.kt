package dev.todor.fassistantapps.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
        val apk = File(context.cacheDir, "${entry.repo}-${manifest.versionCode}.apk")
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
     * Opens a PackageInstaller session and commits it. A sideloaded app cannot install silently, so
     * this ends with Android's own confirmation, raised by [InstallResultReceiver].
     */
    fun handOver(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val session = installer.createSession(
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        )

        installer.openSession(session).use { open ->
            open.openWrite(apk.name, 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                open.fsync(out)
            }
            // Mutable on purpose: the installer fills this in with its own status extras.
            val pending = PendingIntent.getBroadcast(
                context,
                session,
                Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            open.commit(pending.intentSender)
        }
        // The session holds its own copy now.
        apk.delete()
    }

    /**
     * Below Android 8 the permission is one switch for the whole phone, and it is already on: this
     * app was itself sideloaded to get here.
     */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Only reached when [canInstall] said no, which cannot happen below Android 8. */
    @Suppress("InlinedApi")
    fun permissionIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

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
