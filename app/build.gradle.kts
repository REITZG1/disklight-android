plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.filelight.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.filelight.android"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Shizuku API (ADB shell uid 2000) — provider menulis binder di AndroidManifest.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
