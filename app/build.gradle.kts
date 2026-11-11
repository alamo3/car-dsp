import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes -PcabinVersionCode=<run number> -PcabinVersionName=0.1.<run number>.
// The in-app updater compares versionCode with the number at the end of the release tag.
val cabinVersionCode = (project.findProperty("cabinVersionCode") as String?)?.toInt() ?: 1
val cabinVersionName = (project.findProperty("cabinVersionName") as String?) ?: "0.1.0-dev"

// Every build must use the SAME key, otherwise Android refuses to install one over the other and
// you end up with "two copies" (or an install error). CI passes the release key through env; the
// same key can be supplied locally through local.properties (never committed) or dropped at
// ~/.android/cabineq-release.jks. Debug builds then use it too, so `installDebug` replaces the
// release build in place instead of failing with a signature mismatch.
//
//   local.properties:
//     cabineq.storeFile=/home/you/.android/cabineq-release.jks
//     cabineq.storePassword=…
//     cabineq.keyAlias=cabineq
//     cabineq.keyPassword=…            (defaults to storePassword)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingSetting(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: localProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val signingKeystorePath: String? = signingSetting("SIGNING_KEYSTORE_PATH", "cabineq.storeFile")
    ?: File(System.getProperty("user.home"), ".android/cabineq-release.jks")
        .takeIf { it.exists() }?.absolutePath
val signingPassword: String? = signingSetting("SIGNING_PASSWORD", "cabineq.storePassword")
val signingKeyAlias: String = signingSetting("SIGNING_KEY_ALIAS", "cabineq.keyAlias") ?: "cabineq"
val signingKeyPassword: String? =
    signingSetting("SIGNING_KEY_PASSWORD", "cabineq.keyPassword") ?: signingPassword
val hasSigningKey = signingKeystorePath != null && signingPassword != null &&
    File(signingKeystorePath).exists()

// Phones (Galaxy Z Fold 6 and everything current) are arm64; emulators are x86_64. A debug build
// carries both, so Run works on either: Android picks the device's own ABI. Without it, an
// arm64-only APK installs on an x86_64 emulator and runs under binary translation, where the
// native DSP is far too slow and playback stalls for reasons that have nothing to do with the app.
// Releases stay arm64-only (smaller, and the only thing a phone needs). -PcabinAbis=a,b overrides
// both.
val cabinAbisOverride: List<String>? = (project.findProperty("cabinAbis") as String?)
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.takeIf { it.isNotEmpty() }
val releaseAbis: List<String> = cabinAbisOverride ?: listOf("arm64-v8a")
val debugAbis: List<String> = cabinAbisOverride ?: listOf("arm64-v8a", "x86_64")

// -PcabinAppIdSuffix=.aaTest installs a test build next to a release build instead of replacing
// it (Android Auto lists media apps by package, so both show up).
val cabinAppIdSuffix: String = (project.findProperty("cabinAppIdSuffix") as String?) ?: ""

logger.lifecycle(
    "CabinEQ: appId=dev.alamo3.cabineq$cabinAppIdSuffix, " +
        "signing=" + (if (hasSigningKey) "the shared key ($signingKeyAlias)" else "debug key (CI builds will not update over this)")
)

android {
    namespace = "dev.alamo3.cabineq"
    compileSdk = 36

    buildFeatures {
        buildConfig = true   // BuildConfig.APPLICATION_ID: the art provider authority follows it
    }

    defaultConfig {
        applicationId = "dev.alamo3.cabineq" + cabinAppIdSuffix
        minSdk = 29
        targetSdk = 36
        versionCode = cabinVersionCode
        versionName = cabinVersionName
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
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
            ndk { abiFilters += releaseAbis }
        }
        debug {
            // Same key as CI: a dev build installs over the release build, not beside it.
            signingConfigs.findByName("cabineq")?.let { signingConfig = it }
            ndk { abiFilters += debugAbis }
        }
        // Side-by-side test build: its own package, so it installs next to a Play-signed release
        // (which a locally built APK can never update over). Not part of a release or CI.
        create("solotest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".solotest"
            versionNameSuffix = "-solotest"
            signingConfig = signingConfigs.getByName("debug")
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // android.util.Log and friends return defaults in JVM tests instead of throwing.
        unitTests.isReturnDefaultValues = true
    }
}

// The app is otherwise framework-only. androidx.media is here for one thing the framework is
// missing: MediaBrowserServiceCompat.onSearch, which is how a car's media UI searches a source
// (the framework MediaBrowserService has no search API at all).
// Native DSP lives in src/main/cpp and is built with CMake + the NDK.
dependencies {
    implementation("androidx.media:media:1.7.0")
    // QR codes for the jam session (pure Java, no Android code): the car shows one to scan.
    implementation("com.google.zxing:core:3.5.3")
    // Car App Library: the media templates Android Auto/AAOS render for YT Music and Spotify
    // (sections of tiles, tabs). 1.8 adds CATEGORY_MEDIA + MediaPlaybackManager.
    implementation("androidx.car.app:app:1.9.0-alpha03")
    // The second player engine (Settings → Playback → Player engine). ExoPlayer only: no UI,
    // session or streaming-format modules. The DSP engine plugs in as an audio processor.
    implementation("androidx.media3:media3-exoplayer:1.10.1")

    // JVM tests (src/test): the framework's org.json is only a stub off-device, so tests bring
    // a real one.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
