import java.util.Properties

plugins {
    id("com.android.application")
}

val signingProperties = Properties()
val signingFile = rootProject.file(".android/signing.properties")

if (signingFile.isFile) signingFile.inputStream().use { signingProperties.load(it) }

android {
    namespace = "dev.deepseek.mobile.lab"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.deepseek.mobile.lab"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    signingConfigs {
        val localDebugKey = rootProject.file(".android/debug.keystore")
        if (localDebugKey.isFile) getByName("debug") { storeFile = localDebugKey }
    }
    if (signingFile.isFile) {
        signingConfigs.create("localRelease") {
            storeFile = rootProject.file(".android/release.keystore")
            storePassword = signingProperties.getProperty("storePassword")
            keyAlias = "deepseek-mobile"
            keyPassword = signingProperties.getProperty("keyPassword")
        }
        buildTypes.getByName("release") { signingConfig = signingConfigs.getByName("localRelease") }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
