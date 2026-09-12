package dev.todor.fassistantapps.catalogue

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.todor.fassistantapps.BuildConfig
import dev.todor.fassistantapps.net.Http
import dev.todor.fassistantapps.net.RateLimited
import org.json.JSONObject
import java.io.File

/** What the catalogue offers to do about one app, once its release is compared with the phone. */
enum class Standing {
    /** Not on the phone. */
    INSTALL,

    /** On the phone, and the release is newer. */
    UPDATE,

    /** On the phone and current. */
    CURRENT,

    /** Released, but its manifest predates the catalogue and names no package to look for. */
    UNIDENTIFIED,

    /** Tagged, but its release could not be read. */
    BROKEN,
}

class InstalledVersion(val versionName: String, val versionCode: Long)

class CatalogueEntry(
    val repo: String,
    val manifest: ReleaseManifest?,
    val installed: InstalledVersion?,
    val problem: String?,
) {
    val label: String get() = manifest?.label ?: repo

    val standing: Standing
        get() = when {
            manifest == null -> Standing.BROKEN
            manifest.packageName == null -> Standing.UNIDENTIFIED
            installed == null -> Standing.INSTALL
            installed.versionCode < manifest.versionCode -> Standing.UPDATE
            else -> Standing.CURRENT
        }
}

class CatalogueResult(
    val entries: List<CatalogueEntry>,
    val fetchedAt: Long,
    val fromCache: Boolean,
    val problem: String?,
    val rateLimited: Boolean,
)

/**
 * Assembles the list: which apps exist, what each has released, and what is on the phone.
 *
 * The published manifests are cached on disk so a refresh that cannot reach GitHub still has
 * something to show. What is installed is never cached — it is read fresh every time, because it
 * changes underneath the app whenever the user installs or removes something.
 */
object Catalogue {

    fun load(context: Context): CatalogueResult {
        val cache = Cache(context)

        return try {
            val repos = Discovery.repositories()
            val manifests = repos.associateWith { repo ->
                runCatching { Http.text(manifestUrl(repo)) }.getOrNull()
            }
            cache.write(manifests)
            CatalogueResult(
                entries = manifests.map { (repo, text) -> entry(context, repo, text) },
                fetchedAt = System.currentTimeMillis(),
                fromCache = false,
                problem = null,
                rateLimited = false,
            )
        } catch (e: Exception) {
            val saved = cache.read()
            CatalogueResult(
                entries = saved.manifests.map { (repo, text) -> entry(context, repo, text) },
                fetchedAt = saved.fetchedAt,
                fromCache = true,
                problem = e.message ?: e.javaClass.simpleName,
                rateLimited = e is RateLimited,
            )
        }
    }

    /** Where an app publishes what it has released. "latest" makes this address permanent. */
    private fun manifestUrl(repo: String) =
        "https://github.com/${BuildConfig.CATALOGUE_OWNER}/$repo/releases/latest/download/update.json"

    private fun entry(context: Context, repo: String, manifestText: String?): CatalogueEntry {
        val manifest = manifestText?.let { runCatching { ReleaseManifest.parse(it) }.getOrNull() }
        return CatalogueEntry(
            repo = repo,
            manifest = manifest,
            installed = manifest?.packageName?.let { installedVersion(context, it) },
            problem = if (manifestText == null) "no release to read" else if (manifest == null) "its release could not be understood" else null,
        )
    }

    /**
     * Every installed package is visible without QUERY_ALL_PACKAGES because this app targets 25,
     * which is one of the reasons it does.
     */
    private fun installedVersion(context: Context, packageName: String): InstalledVersion? = try {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        InstalledVersion(info.versionName ?: "?", versionCodeOf(info))
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

    private class Cache(context: Context) {

        private val file = File(context.filesDir, "catalogue.json")

        class Saved(val manifests: Map<String, String?>, val fetchedAt: Long)

        fun write(manifests: Map<String, String?>) {
            val repos = JSONObject()
            for ((repo, text) in manifests) {
                if (text != null) repos.put(repo, text)
            }
            val root = JSONObject()
                .put("fetchedAt", System.currentTimeMillis())
                .put("repos", repos)
            runCatching { file.writeText(root.toString()) }
        }

        fun read(): Saved {
            val text = runCatching { file.readText() }.getOrNull() ?: return Saved(emptyMap(), 0)
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return Saved(emptyMap(), 0)
            val repos = root.optJSONObject("repos") ?: return Saved(emptyMap(), 0)
            val manifests = repos.keys().asSequence().associateWith { repos.optString(it) }
            return Saved(manifests, root.optLong("fetchedAt"))
        }
    }
}
