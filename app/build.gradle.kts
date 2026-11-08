plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.alamo3.cabineq"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.alamo3.cabineq"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

// Milestone 1 deliberately uses only the Android framework (no AndroidX),
// so there is nothing to resolve beyond the build plugins.
dependencies {
}
