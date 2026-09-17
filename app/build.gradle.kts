plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bello.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bello.assistant"
        // Target device: Galaxy Tab 4 SM-T530, Android 5.0.2 (API 21), armeabi-v7a only.
        minSdk = 21
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        // JNA (used by Vosk) needs java.util.function on API 21.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
    lint { abortOnError = false; checkReleaseBuilds = false }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.conscrypt:conscrypt-android:2.7.0")

    // Vosk 0.3.75 Java classes; native libvosk.so in jniLibs is patched for API 21
    // (needs libstdiofix.so, see spikes/native/stdiofix and docs/feasibility-results.md SP-03).
    implementation(files("libs/vosk-android-0.3.75-classes.jar"))
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    testImplementation("junit:junit:4.13.2")
}
