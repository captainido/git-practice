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
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "3g"
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            it.systemProperty("oliver.frames", rootProject.file("build/session").absolutePath)
            // Optional: -ProboDir=/path with pre-downloaded android-all jars (offline Robolectric)
            (project.findProperty("roboDir") as String?)?.let { dir ->
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", dir)
            }
        }
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
