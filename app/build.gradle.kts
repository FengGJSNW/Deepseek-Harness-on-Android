import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingProperties = Properties()
val signingFile = rootProject.file(".android/signing.properties")

if (signingFile.isFile) signingFile.inputStream().use { signingProperties.load(it) }

android {
    testBuildType = providers.gradleProperty("dshaTestBuildType").getOrElse("debug")
    namespace = "dev.deepseek.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.deepseek.mobile"
        testApplicationId =
            if (providers.gradleProperty("dshaTestBuildType").orNull == "release")
                "dev.deepseek.mobile.v073.privilege.test"
            else "dev.deepseek.mobile.test"
        minSdk = 30
        targetSdk = 35
        versionCode = 30
        versionName = "0.8.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        buildConfig = true
        compose = true
        aidl = true
    }
    ndkVersion = "28.2.13676358"
    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/libdsha_mihomo.so"
    }
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/mihomo"))
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
}

val prepareMihomo by
    tasks.registering(Exec::class) {
        val script = rootProject.file("scripts/prepare-mihomo.mjs")
        val output = layout.buildDirectory.dir("generated/mihomo")
        workingDir(rootProject.projectDir)
        commandLine("node", script.absolutePath, output.get().asFile.absolutePath)
        inputs.file(script)
        outputs.dir(output)
    }

tasks.configureEach {
    if (name.endsWith("JniLibFolders") && name.startsWith("merge")) dependsOn(prepareMihomo)
}

dependencies {
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
