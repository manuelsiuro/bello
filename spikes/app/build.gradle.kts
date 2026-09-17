plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bello.spikes"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bello.spikes"
        minSdk = 21
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("armeabi-v7a") }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.conscrypt:conscrypt-android:2.7.0")
    // Vosk 0.3.75 Java classes; native libvosk.so is patched for API 21 (see native/stdiofix).
    implementation(files("libs/vosk-android-0.3.75-classes.jar"))
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}
