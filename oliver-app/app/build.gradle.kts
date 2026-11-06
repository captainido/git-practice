plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.oliver.pet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.oliver.pet"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            // Personal sideloaded app: keystore is committed on purpose so future
            // builds can be installed as updates over the existing install.
            storeFile = rootProject.file("keystore/oliver.jks")
            storePassword = "oliverpet"
            keyAlias = "oliver"
            keyPassword = "oliverpet"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
