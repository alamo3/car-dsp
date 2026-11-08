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

android {
    namespace = "dev.alamo3.cabineq"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.alamo3.cabineq"
        minSdk = 29
        targetSdk = 35
        versionCode = cabinVersionCode
        versionName = cabinVersionName
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

// Framework-only app (no AndroidX), so there is nothing to resolve beyond the build plugins.
dependencies {
}
