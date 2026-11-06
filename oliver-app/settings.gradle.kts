pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central first (Central often rate-limits CI/cloud IPs)
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        mavenCentral()
    }
}
rootProject.name = "OliverPet"
include(":app")
