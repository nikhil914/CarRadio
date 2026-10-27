plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// "Send to Car": one APK for both ends. On the phone it adds "Send to car" to the
// Share menu; on the head unit it runs a receiver that opens Waze. This first version
// is a probe: it tests every delivery channel on the real unit before the app is final.
android {
    namespace = "com.nik.sendtocar"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nik.sendtocar"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Probe only: reuses the repo's sideload key. The final app gets its own key kept out of git.
        create("sideload") {
            storeFile = rootProject.file("keystore/sideload.jks")
            storePassword = "carradio"
            keyAlias = "carradio"
            keyPassword = "carradio"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("sideload") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint { abortOnError = false }
}

dependencies {
    // QR codes for the diagnostics report (same approach as Car Radio).
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
