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
        maven { url = uri(".tmp/flclash-module/build/host/outputs/repo") }
        maven("https://storage.googleapis.com/download.flutter.io")
    }
    versionCatalogs {
        create("libs") { from(files("integrations/flclash/android/gradle/libs.versions.toml")) }
    }
}

rootProject.name = "DeepSeekMobile"

include(":app")

include(":control-lab")
include(":flclash-native", ":flclash-common", ":flclash-service", ":flclash-core")
listOf("native", "common", "service", "core").forEach {
    project(":flclash-$it").projectDir = file("integrations/android/$it")
}
