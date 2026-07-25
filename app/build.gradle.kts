import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val versionProps = Properties()
val versionPropsFile = rootProject.file("version.properties")
if (versionPropsFile.exists()) {
    versionProps.load(FileInputStream(versionPropsFile))
}

val major = versionProps.getProperty("major")?.toIntOrNull() ?: 0
val minor = versionProps.getProperty("minor")?.toIntOrNull() ?: 1
val patch = versionProps.getProperty("patch")?.toIntOrNull() ?: 20

// versionCode source. CI passes `-PversionBuild=$(git rev-list --count HEAD)` so every Play upload
// gets a strictly-increasing code (commit count only ever grows). When the override is absent (local
// builds, Android Studio) we keep the previous behavior: auto-increment a counter in
// version.properties on each build task.
val versionBuildOverride = project.findProperty("versionBuild")?.toString()?.toIntOrNull()
var buildNumber = versionBuildOverride ?: (versionProps.getProperty("build")?.toIntOrNull() ?: 0)

val isBuildTask = gradle.startParameter.taskNames.any { taskName ->
    val name = taskName.substringAfterLast(':').lowercase()
    name.startsWith("assemble") || name.startsWith("bundle") ||
        name.startsWith("install") || name.startsWith("package") || name == "build"
}

// Only auto-increment/persist locally; when an explicit -PversionBuild override is supplied (CI),
// use it verbatim and leave version.properties untouched.
if (versionBuildOverride == null && isBuildTask) {
    buildNumber++
    versionProps.setProperty("build", buildNumber.toString())
    versionPropsFile.writer().use { versionProps.store(it, null) }
}

android {
    namespace = "com.hereliesaz.sirmatchalot"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hereliesaz.sirmatchalot"
        minSdk = 24
        targetSdk = 37
        versionCode = (major * 10000 + minor * 100 + patch) * 100000 + buildNumber
        versionName = "$major.$minor.$patch.$buildNumber"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing is supplied by CI, which reconstructs a keystore from
    // secrets and exports KEYSTORE_FILE. When that env var is absent — a local
    // build, or a fork without secrets — the config is left unregistered so the
    // release variant builds unsigned instead of failing validateSigningRelease.
    val releaseKeystore: String? = System.getenv("KEYSTORE_FILE")

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    buildTypes {
        debug {
            // Uses the default debug signing config.
        }
        release {
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        jvmToolchain(21)
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation3.ui)

    // Persistence
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Playback and networking. The mixing engine in `audio/` renders through
    // AudioTrack directly; media3 is used for container/codec support when
    // decoding a file to PCM, not as the playback path.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.okhttp.core)

    // Track analysis. Pending removal with `ai/SongAnalyzer.kt` once the DSP
    // analysis pipeline replaces it (see docs/ARCHITECTURE.md, phase 3).
    implementation(libs.google.ai.client)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented tests
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
