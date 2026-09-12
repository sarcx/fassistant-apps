import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionName = providers.gradleProperty("VERSION_NAME").get()
val appVersionCode = providers.gradleProperty("VERSION_CODE").get().toInt()

// Machine-local settings. local.properties is gitignored, which is why the signing key lives
// there and not in the build script — the key is what the phones trust.
val localConfig = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun setting(localKey: String, envKey: String): String? =
    localConfig.getProperty(localKey) ?: System.getenv(envKey)

val signingKeystore = setting("fapps.keystore", "FAPPS_KEYSTORE")
    ?.let { file(it) }
    ?.takeIf { it.isFile }

// Whose public repositories to list, and the topic that marks one as part of the family. Adding
// an app to the catalogue is putting this topic on its repository — there is no list anywhere.
val catalogueOwner = setting("fapps.owner", "FAPPS_OWNER") ?: "sarcx"
val catalogueTopic = setting("fapps.topic", "FAPPS_TOPIC") ?: "fassistant"

// Where the app looks for its own updates. CI points this at the repository's latest release, so
// a build always knows where it came from. Empty means self-update is switched off.
val updateManifestUrl = setting("fapps.updateUrl", "FAPPS_UPDATE_URL").orEmpty()

android {
    namespace = "dev.todor.fassistantapps"
    compileSdk = 36
    // Pinned so CI installs exactly what has been built against locally.
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "dev.todor.fassistantapps"
        // Android 7.0, which is also the first release with per-app network security config —
        // and that is what carries the bundled root above.
        minSdk = 24
        // Deliberately low, matching the sibling repos. Here it earns its keep for one specific
        // reason: an app targeting 25 sees every installed package without QUERY_ALL_PACKAGES,
        // and reading installed versions is half of what this app does.
        targetSdk = 25
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")
        buildConfigField("String", "CATALOGUE_OWNER", "\"$catalogueOwner\"")
        buildConfigField("String", "CATALOGUE_TOPIC", "\"$catalogueTopic\"")
    }

    signingConfigs {
        if (signingKeystore != null) {
            create("upgrade") {
                storeFile = signingKeystore
                storePassword = setting("fapps.keystorePassword", "FAPPS_KEYSTORE_PASSWORD")
                keyAlias = setting("fapps.keyAlias", "FAPPS_KEY_ALIAS")
                keyPassword = setting("fapps.keyPassword", "FAPPS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Without the key a clone still builds and runs; it just cannot produce an APK that
        // upgrades an installed copy in place, because the signature will not match.
        val upgradeSigning = signingConfigs.findByName("upgrade")

        getByName("debug") {
            signingConfig = upgradeSigning ?: signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), file("proguard-rules.pro"))
            signingConfig = upgradeSigning ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    sourceSets.getByName("main").kotlin.srcDir("src/main/kotlin")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

/**
 * Produces everything a release needs: the APK under a versioned name for humans, a copy under a
 * stable name so an update URL never has to change, and the manifest the app polls.
 *
 * packageName and label are in the manifest because the catalogue reads every app's manifest,
 * including this one's, and cannot say "installed 0.1.0" without knowing which package on the
 * phone a repository corresponds to.
 */
tasks.register("dist") {
    dependsOn("assembleRelease")
    val releaseOutputs = layout.buildDirectory.dir("outputs/apk/release")
    val distDir = rootProject.layout.projectDirectory.dir("dist")
    val changelog = rootProject.file("CHANGELOG.md")
    val applicationId = android.defaultConfig.applicationId

    doLast {
        val built = releaseOutputs.get().asFile.listFiles { candidate -> candidate.extension == "apk" }
            ?.singleOrNull()
            ?: error("expected exactly one release APK in ${releaseOutputs.get().asFile}")

        val target = distDir.asFile.apply { mkdirs() }
        built.copyTo(File(target, "fassistant-apps-$appVersionName-$appVersionCode.apk"), overwrite = true)
        built.copyTo(File(target, "fassistant-apps.apk"), overwrite = true)

        val digest = MessageDigest.getInstance("SHA-256").digest(built.readBytes())
            .joinToString("") { "%02x".format(it) }

        File(target, "update.json").writeText(
            """
            {
              "versionCode": $appVersionCode,
              "versionName": "$appVersionName",
              "apkUrl": "fassistant-apps.apk",
              "sha256": "$digest",
              "packageName": "$applicationId",
              "label": "Fassistant Apps",
              "notes": "${jsonEscape(releaseNotes(changelog))}"
            }
            """.trimIndent() + "\n"
        )

        logger.lifecycle("dist: $appVersionName ($appVersionCode), ${built.length() / 1024} KB, sha256 $digest")
    }
}

fun jsonEscape(text: String): String = buildString {
    for (character in text) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
        }
    }
}

/** The top section of the changelog, which becomes the release notes and the in-app update notes. */
fun releaseNotes(changelog: File): String {
    if (!changelog.isFile) return ""
    val lines = changelog.readLines()
    val start = lines.indexOfFirst { it.startsWith("## ") }
    if (start < 0) return ""
    val rest = lines.drop(start + 1)
    val end = rest.indexOfFirst { it.startsWith("## ") }
    return (if (end < 0) rest else rest.take(end)).joinToString("\n").trim()
}
