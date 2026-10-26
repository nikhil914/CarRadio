plugins { id("com.android.application") }

// Replaces the unit's own radio app (com.syu.radio) so its Radio icon and hardware
// Radio key open Car Radio. Android installs it as an update to the firmware app only
// when it is signed with the same key, so it is built twice, once per public AOSP key:
// "testkey" and "platform". Remove it again: Settings → Apps → Radio → Uninstall updates.
android {
    namespace = "com.syu.radio"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.syu.radio"
        minSdk = 21
        targetSdk = 27          // matches firmware-era apps; avoids newer-target install checks
        // Must be higher than the firmware's com.syu.radio for Android to accept the update.
        versionCode = 2_000_000_000
        versionName = "CarRadio-proxy-1"
    }
    signingConfigs {
        create("testkey") { storeFile = rootProject.file("keystore/aosp/testkey.jks"); storePassword = "android"; keyAlias = "testkey"; keyPassword = "android" }
        create("platform") { storeFile = rootProject.file("keystore/aosp/platform.jks"); storePassword = "android"; keyAlias = "platform"; keyPassword = "android" }
    }
    flavorDimensions += "key"
    productFlavors {
        create("aospTestkey") { dimension = "key"; signingConfig = signingConfigs.getByName("testkey") }
        create("aospPlatform") { dimension = "key"; signingConfig = signingConfigs.getByName("platform") }
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false }
        // debug builds of the proxy also use the flavor key
        getByName("debug") { signingConfig = null }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = false }
}
