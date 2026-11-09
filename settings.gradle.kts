pluginManagement {
    repositories {
        google()
        // Maven Central's canonical hosts return HTTP 403 on this network.
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2")
        maven("https://jitpack.io")
    }
}

rootProject.name = "DeepSeekMobile"

include(":app")

include(":control-lab")
