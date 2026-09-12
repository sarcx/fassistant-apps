package dev.todor.fassistantapps.catalogue

import org.json.JSONObject

/**
 * One app's published release, read from the update.json that sits beside its APK.
 *
 * [packageName] and [label] arrived with this catalogue, and releases cut before it do not carry
 * them. An app whose manifest omits the package name still gets a row and can still be installed;
 * what it cannot do is say whether it is already on the phone, so both are optional here rather
 * than required.
 */
class ReleaseManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val packageName: String?,
    val label: String?,
    val notes: String,
) {
    companion object {
        fun parse(text: String): ReleaseManifest {
            val json = JSONObject(text)
            return ReleaseManifest(
                versionCode = json.getInt("versionCode"),
                versionName = json.optString("versionName", json.getInt("versionCode").toString()),
                apkUrl = json.getString("apkUrl"),
                sha256 = json.optString("sha256").lowercase(),
                packageName = json.optString("packageName").takeIf { it.isNotEmpty() },
                label = json.optString("label").takeIf { it.isNotEmpty() },
                notes = json.optString("notes"),
            )
        }
    }
}
