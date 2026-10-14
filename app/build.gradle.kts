plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "io.github.kkffiirr.zuneandroid"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.github.kkffiirr.zuneandroid"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "0.7"
        // -PphoneOnly drops x86_64 (emulator-only) to shrink the APK
        ndk { abiFilters += if (project.hasProperty("phoneOnly")) listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}
