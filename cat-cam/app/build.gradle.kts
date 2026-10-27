plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yungerman.catcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yungerman.catcam"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "2.2"

        // 64-bit ARM only: MediaPipe ships no 32-bit library, so a 32-bit phone should be
        // refused at install time rather than crash at the first detection.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Fixed keystore so future versions install as updates over this one.
    signingConfigs {
        create("release") {
            storeFile = file("catcam.keystore")
            storePassword = "catcam123"
            keyAlias = "catcam"
            keyPassword = "catcam123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // The detector model is memory-mapped from assets, so it must stay uncompressed.
    androidResources {
        noCompress += "tflite"
    }

    // Compressed APK: this app is sideloaded, so download size matters more than install size.
    packaging {
        jniLibs.useLegacyPackaging = true
        dex.useLegacyPackaging = true
    }
}

dependencies {
    val camerax = "1.4.1"
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mediapipe:tasks-vision:0.10.26")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
