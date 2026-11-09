plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes -PcabinVersionCode=<run number> -PcabinVersionName=0.1.<run number>.
// The in-app updater compares versionCode with the number at the end of the release tag.
val cabinVersionCode = (project.findProperty("cabinVersionCode") as String?)?.toInt() ?: 1
val cabinVersionName = (project.findProperty("cabinVersionName") as String?) ?: "0.1.0-dev"

// Release signing comes from CI secrets. Every release must use the same key, otherwise
// Android refuses to install updates over the existing app.
val signingKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")
val signingPassword: String? = System.getenv("SIGNING_PASSWORD")

// Phones (Galaxy Z Fold 6 and everything current) are arm64. Emulators are x86_64, so pass
// -PcabinAbis=x86_64 to build a test APK for one; releases keep the default.
val cabinAbis: List<String> = (project.findProperty("cabinAbis") as String?)
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?: listOf("arm64-v8a")

// -PcabinAppIdSuffix=.aaTest installs a test build next to a release build instead of replacing
// it (Android Auto lists media apps by package, so both show up).
val cabinAppIdSuffix: String = (project.findProperty("cabinAppIdSuffix") as String?) ?: ""

android {
    namespace = "dev.alamo3.cabineq"
    compileSdk = 35

    buildFeatures {
        buildConfig = true   // BuildConfig.APPLICATION_ID: the art provider authority follows it
    }

    defaultConfig {
        applicationId = "dev.alamo3.cabineq" + cabinAppIdSuffix
        minSdk = 29
        targetSdk = 35
        versionCode = cabinVersionCode
        versionName = cabinVersionName

        ndk {
            abiFilters += cabinAbis
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    signingConfigs {
        if (signingKeystorePath != null && signingPassword != null) {
            create("release") {
                storeFile = file(signingKeystorePath)
                storePassword = signingPassword
                keyAlias = "cabineq"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
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
}
