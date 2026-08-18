import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

// The pure-Kotlin brain of the app (dsp/, domain/, gesture/) plus the LAN
// sync protocol (sync/, session/), shared between the Android app and a
// future desktop build so two devices on either platform can host or join
// the same room. Everything else — playback (AudioTrack/MediaCodec), the
// database (Room), and the Compose UI — stays platform-specific; see
// docs/ARCHITECTURE.md for why those don't belong here.
kotlin {
    androidLibrary {
        namespace = "com.hereliesaz.sirmatchalot.shared"
        compileSdk = 37
        minSdk = 24

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    sourceSets {
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        // Both real targets here compile to the JVM (Android, desktop) — there
        // is no third, non-JVM target planned, so this intermediate set is
        // simply "everything shared", not a strict common/JVM split. It's
        // what lets dsp/domain/sync/session use java.net, javax.crypto,
        // java.util.zip, String.format, UUID etc. directly rather than
        // expect/actual-wrapping every JDK call.
        val jvmCommonMain by creating {
            dependsOn(commonMain)
            dependencies {
                // Track (data/Track.kt) is a Room @Entity — domain/ needs its
                // shape directly, and Room's own artifacts are multiplatform
                // as of the version already pinned for :app. Only the plain
                // @Entity data class lives here; @Dao/@Database need Android's
                // SQLite driver and KSP codegen, so TrackDao/AppDatabase stay
                // in :app, importing Track from here like any shared type.
                implementation(libs.androidx.room.runtime)
                // The sync client's HTTP/WebSocket transport.
                implementation(libs.okhttp.core)
                // session/SessionDocument.kt's @Serializable model.
                implementation(libs.kotlinx.serialization.json)
                // analysis/AnalysisProgressBus.kt's StateFlow.
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val jvmCommonTest by creating {
            dependsOn(commonTest)
            dependencies {
                implementation(libs.junit)
                // A real JSON implementation on the JVM — android.jar's org.json
                // is a stub that throws outside an actual device/emulator, same
                // reason :app's own unit tests need this.
                implementation(libs.json)
            }
        }

        val androidMain by getting {
            dependsOn(jvmCommonMain)
        }

        val desktopMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                // Android provides org.json on-device; a desktop JVM needs the
                // real implementation, same one already used for :app's unit
                // tests.
                implementation(libs.json)
            }
        }
        val desktopTest by getting {
            dependsOn(jvmCommonTest)
        }
    }
}
