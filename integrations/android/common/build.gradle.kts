import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

android {
    namespace = "com.follow.clash.common"
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
        getByName("test").kotlin.directories += "../../flclash/android/tests/common"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.gson)
    testImplementation(libs.junit)
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    sourceSets.getByName("main") {
        java.setSrcDirs(
            listOf(
                "../../flclash/android/common/src/main/java",
                "../../flclash/android/common/src/main/kotlin",
            ),
        )
        kotlin.directories +=
            listOf(
                "../../flclash/android/common/src/main/java",
                "../../flclash/android/common/src/main/kotlin",
            )
        res.setSrcDirs(listOf("../../flclash/android/common/src/main/res"))
        manifest.srcFile("../../flclash/android/common/src/main/AndroidManifest.xml")
    }
}
