plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// Optional ASCII-only output path for JVM test workers in accented Windows workspaces.
providers.gradleProperty("lumaBuildRoot").orNull?.let { layout.buildDirectory.set(file("$it/app")) }
val localSigning = listOf("LUMA_SIGNING_STORE", "LUMA_SIGNING_STORE_PASSWORD", "LUMA_SIGNING_KEY_ALIAS", "LUMA_SIGNING_KEY_PASSWORD")
    .map { providers.environmentVariable(it).orNull }
require(localSigning.all { it == null } || localSigning.all { !it.isNullOrBlank() }) {
    "Configure all four LUMA_SIGNING_* variables or leave all unset."
}
android {
    namespace = "com.lumacamera"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.lumacamera"
        minSdk = 29
        targetSdk = 34
        versionCode = 100
        versionName = "1.0.0"
        // Leave unset for the universal APK; target the phone's ABI to avoid unrelated native libraries.
        providers.gradleProperty("lumaAbi").orNull?.let { abi ->
            require(abi in setOf("arm64-v8a", "armeabi-v7a", "x86")) { "Unsupported lumaAbi: $abi" }
            ndk { abiFilters += abi }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (localSigning[0] != null) create("localRelease") {
            storeFile = file(localSigning[0]!!)
            storePassword = localSigning[1]
            keyAlias = localSigning[2]
            keyPassword = localSigning[3]
        }
    }
    buildTypes {
        release {
            // Keep JNI/model entry points intact in the first public distribution.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("localRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    lint { abortOnError = true }
    androidResources { noCompress += "tflite" }
}
dependencies {
    // 1.4.1 builds against SDK 34; later Media3 versions require a newer compile SDK.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    testImplementation("junit:junit:4.13.2")
}
