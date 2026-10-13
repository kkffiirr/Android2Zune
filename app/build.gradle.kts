plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "org.xune.ztransfer"
    compileSdk = 34
    defaultConfig {
        applicationId = "org.xune.ztransfer"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "0.4"
        // -PphoneOnly drops x86_64 (emulator-only) to shrink the APK
        ndk { abiFilters += if (project.hasProperty("phoneOnly")) listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}
