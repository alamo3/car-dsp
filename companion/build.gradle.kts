import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CabinEQ's YouTube companion: yt-dlp on the phone, asked by CabinEQ for a playable link when it
// wants one. A separate app so CabinEQ itself (which goes to Google Play) carries no downloader;
// this one is only ever installed from GitHub releases. Same version numbers and the same signing
// key as CabinEQ.
val cabinVersionCode = (project.findProperty("cabinVersionCode") as String?)?.toInt() ?: 1
val cabinVersionName = (project.findProperty("cabinVersionName") as String?) ?: "0.1.0-dev"

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingSetting(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(prop)?.takeIf { it.isNotBlank() }
val signingKeystorePath: String? = signingSetting("SIGNING_KEYSTORE_PATH", "cabineq.storeFile")
    ?: File(System.getProperty("user.home"), ".android/cabineq-release.jks").takeIf { it.exists() }?.absolutePath
val signingPassword: String? = signingSetting("SIGNING_PASSWORD", "cabineq.storePassword")
val signingKeyAlias: String = signingSetting("SIGNING_KEY_ALIAS", "cabineq.keyAlias") ?: "cabineq"
val signingKeyPassword: String? = signingSetting("SIGNING_KEY_PASSWORD", "cabineq.keyPassword") ?: signingPassword
val hasSigningKey = signingKeystorePath != null && signingPassword != null && File(signingKeystorePath).exists()

android {
    namespace = "dev.alamo3.cabineq.yt"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.alamo3.cabineq.yt"
        minSdk = 29
        // Never on Play, so it is not held to Play's target level; 35 is what Seal ships with.
        targetSdk = 35
        versionCode = cabinVersionCode
        versionName = cabinVersionName
        // The bundled Python and yt-dlp are native code: phones only (arm64, plus 32-bit arm).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (hasSigningKey) {
            create("cabineq") {
                storeFile = file(signingKeystorePath!!)
                storePassword = signingPassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("cabineq")?.let { signingConfig = it }
        }
        debug {
            signingConfigs.findByName("cabineq")?.let { signingConfig = it }
        }
    }

    // youtubedl-android runs Python from the extracted native libraries.
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    // Wraps the real yt-dlp (the name is historical); yt-dlp itself updates from its GitHub releases.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
}
