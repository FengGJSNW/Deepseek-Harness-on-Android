plugins {
    id("com.android.library")
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "com.follow.clash"
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main") {
        java.srcDir("../../flclash/android/app/src/main/kotlin")
        kotlin.directories += "../../flclash/android/app/src/main/kotlin"
        res.srcDir("../../flclash/android/app/src/main/res")
        manifest.srcFile("src/main/AndroidManifest.xml")
    }
    sourceSets.getByName("test").kotlin.directories += "../../flclash/android/tests/app"
    buildFeatures { buildConfig = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":flclash-service"))
    api(project(":flclash-common"))
    api(project(":flclash-core"))
    api("dev.deepseek.mobile.flclash:flutter_release:1.0")
    implementation(libs.core.splashscreen)
    implementation(libs.gson)
    implementation(libs.smali.dexlib2) { exclude(group = "com.google.guava", module = "guava") }
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
