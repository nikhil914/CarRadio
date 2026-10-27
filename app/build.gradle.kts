plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.nik.carradio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nik.carradio"
        // Head units in the field run Android 6 – 13; 21 keeps even very old units installable.
        minSdk = 21
        targetSdk = 34
        versionCode = 12
        versionName = "1.4.0"
    }

    signingConfigs {
        create("sideload") {
            // Fixed key committed to this private repo so every build (local or CI) can
            // update the installed app without uninstalling. Not a Play Store key.
            storeFile = rootProject.file("keystore/sideload.jks")
            storePassword = "carradio"
            keyAlias = "carradio"
            keyPassword = "carradio"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sideload")
        }
        getByName("release") {
            // R8 on: smaller dex, faster class loading on slow head units. JNI classes
            // (com.android.fmradio.*) are kept by name in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("screenshots", project.findProperty("screenshots") ?: "")
                // Robolectric renders are slow and download a 100 MB Android jar: only on request.
                if (project.findProperty("screenshots") == null) it.exclude("**/ScreenshotTest*")
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            }
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Installs the baseline profiles shipped inside Compose on first run (Android 7–8.1 path).
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // QR codes for sending the report without USB. Pure Java, R8 trims it.
    implementation("com.google.zxing:core:3.5.3")
    // MediaSessionCompat: steering-wheel keys + launcher metadata. Lighter than Media3 (no player).
    implementation("androidx.media:media:1.7.0")
    // Folder picker for importing a logo pack (SAF tree URIs).
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (android.jar only has stubs).
    testImplementation("org.json:json:20240303")
    // Screenshot renders of the UI on the JVM (opt-in: -Pscreenshots, see ScreenshotTest).
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.39.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.39.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
