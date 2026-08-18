import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// The touch-laptop side of the room: a plain desktop JVM app, not another
// Android build. It depends on :shared for the same sync protocol, domain
// logic and DSP the Android app uses, so a laptop can host or join a room
// alongside phones running the same code underneath a different UI.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    // A real JSON implementation on the JVM, matching :shared's own — this
    // is a desktop JVM, not Android, so there is no platform stub to rely on.
    implementation(libs.json)

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "com.hereliesaz.sirmatchalot.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "SirMatchALot"
            packageVersion = "1.0.0"
        }
    }
}
