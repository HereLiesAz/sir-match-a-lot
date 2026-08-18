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

// Room emits its expected schema per version into app/schemas. That JSON is the
// authority a hand-written migration has to match: Room verifies an identity
// hash when it opens the database, so a migration producing a subtly different
// table crashes upgrading users at launch rather than failing the build.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // dsp/, domain/, gesture/, sync/, session/, and the pure DSP half of
    // audio/ (everything except AudioOutput/AudioDecoder, which are
    // AudioTrack/MediaCodec-only) live in :shared now — see its build file
    // for why, and docs/ARCHITECTURE.md for the module boundary.
    implementation(project(":shared"))

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

    // No networking dependency here: OkHttp is `:shared`'s, for the sync
    // client. There is deliberately no player library either — the engine in
    // `audio/` renders through AudioTrack, and `AudioDecoder` reads files with
    // the platform's own MediaExtractor/MediaCodec. media3 was dropped along
    // with the last ExoPlayer, because keeping a playback library nothing plays
    // through invites a second, competing audio path back in.

    // No Firebase, and nothing else that reports on the user.
    //
    // firebase-analytics was linked here and called from nowhere — there is not
    // one Firebase reference in `app/src`. That is not the same as inert: CI
    // wrote google-services.json from a secret before building the Play
    // artifact, which applied the plugin, which gave FirebaseInitProvider what
    // it needs, and Analytics then auto-collects sessions, screen views and an
    // app-instance ID with no code at all. So the published build collected
    // exactly what README.md, docs/PRIVACY.md and — the one that matters —
    // docs/DATA_SAFETY.md all declare it does not.
    //
    // Removed rather than declared, because the whole product says the same
    // thing everywhere else and no code wanted it. If analytics is ever
    // genuinely wanted, the Play Console data-safety form has to say so first.
    implementation(libs.androidx.fragment)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // A real SQLite engine on the JVM. Migration SQL is only validated when it
    // is compiled by SQLite; without this, a syntax error in a migration ships
    // and first appears on an upgrading user's device at launch.
    testImplementation(libs.sqlite.jdbc)
    // A real JSON implementation on the JVM. `isReturnDefaultValues` makes the
    // android.jar stubs inert rather than throwing, so without this every
    // `JSONObject` in a unit test silently does nothing and every assertion
    // about a message's contents passes without testing anything. The room
    // protocol is entirely JSON, so it would have been untestable.
    testImplementation(libs.json)

    // Instrumented tests
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
}
