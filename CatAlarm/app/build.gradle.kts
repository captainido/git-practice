plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.captainido.catalarm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.captainido.catalarm"
        minSdk = 27
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
    }

    // Toy app: the keystore lives in the repo so every CI build can update the installed app.
    signingConfigs {
        create("release") {
            storeFile = file("catalarm.keystore")
            storePassword = "catalarm"
            keyAlias = "catalarm"
            keyPassword = "catalarm"
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
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
}
