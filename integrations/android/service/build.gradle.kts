import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

android {
    namespace = "com.follow.clash.service"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        // Unit tests live under android/tests/ instead of each module's src/test.
        getByName("test").kotlin.directories += "../../flclash/android/tests/service"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":flclash-core"))
    implementation(project(":flclash-common"))
    implementation(libs.gson)
    implementation(libs.androidx.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    sourceSets.getByName("main") {
        java.setSrcDirs(
            listOf(
                "../../flclash/android/service/src/main/java",
                "../../flclash/android/service/src/main/kotlin",
            ),
        )
        kotlin.directories +=
            listOf(
                "../../flclash/android/service/src/main/java",
                "../../flclash/android/service/src/main/kotlin",
            )
        res.setSrcDirs(listOf("../../flclash/android/service/src/main/res"))
        manifest.srcFile("../../flclash/android/service/src/main/AndroidManifest.xml")
    }
}
