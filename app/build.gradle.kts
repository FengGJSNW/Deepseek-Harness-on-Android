import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Runtime images are optional ZIP downloads; every APK uses the same image-free build.

val signingProperties = Properties()
val signingFile = rootProject.file(".android/signing.properties")

if (signingFile.isFile) signingFile.inputStream().use { signingProperties.load(it) }

// Resolve the test Android runtimes through Gradle's normal repositories/cache, not a second
// downloader inside the test JVM. Separate configurations retain both Android API versions.
val robolectricSdk35 = configurations.create("robolectricSdk35") { isTransitive = false }
val robolectricSdk36 = configurations.create("robolectricSdk36") { isTransitive = false }
val prepareRobolectricSdks =
    tasks.register<Sync>("prepareRobolectricSdks") {
        from(robolectricSdk35, robolectricSdk36)
        into(layout.buildDirectory.dir("robolectric-sdks"))
    }

android {
    testBuildType = providers.gradleProperty("dshaTestBuildType").getOrElse("debug")
    namespace = "dev.deepseek.mobile"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.deepseek.mobile"
        testApplicationId =
            if (providers.gradleProperty("dshaTestBuildType").orNull == "release")
                "dev.deepseek.mobile.v073.privilege.test"
            else "dev.deepseek.mobile.test"
        minSdk = 30
        targetSdk = 35
        versionCode = 36
        versionName = "0.8.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }
    buildTypes {
        getByName("debug") { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
        getByName("release") {
            val releaseAbis =
                providers
                    .gradleProperty("dshaReleaseAbis")
                    .getOrElse("arm64-v8a")
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
            require(
                releaseAbis.isNotEmpty() && releaseAbis.all { it in setOf("arm64-v8a", "x86_64") },
            ) {
                "dshaReleaseAbis must contain arm64-v8a and/or x86_64"
            }
            ndk { abiFilters += releaseAbis }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        compose = true
        aidl = true
    }
    ndkVersion = "28.2.13676358"
    packaging {
        jniLibs.useLegacyPackaging = true
        dex.useLegacyPackaging = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
    lint { abortOnError = true }
    testOptions.unitTests.apply {
        isIncludeAndroidResources = true
        all {
            it.dependsOn(prepareRobolectricSdks)
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty(
                "robolectric.dependency.dir",
                layout.buildDirectory.dir("robolectric-sdks").get().asFile.absolutePath,
            )
        }
    }
}

dependencies {
    implementation(project(":flclash-native"))
    implementation("dev.deepseek.mobile.flclash:flutter_release:1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    add(
        robolectricSdk35.name,
        "org.robolectric:android-all-instrumented:15-robolectric-13954326-i7",
    )
    add(
        robolectricSdk36.name,
        "org.robolectric:android-all-instrumented:16-robolectric-13921718-i7",
    )
    implementation("org.snakeyaml:snakeyaml-engine:2.9")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.javascriptengine:javascriptengine:1.0.0")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.24.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
