import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

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

// The same version.properties :app reads from, so the desktop package
// carries the app's real version instead of a number nobody updates.
// Only major.minor.patch — the installer formats below (MSI in particular)
// require a strict X.Y.Z and reject the trailing build number :app's own
// versionName carries.
val versionProps = Properties()
val versionPropsFile = rootProject.file("version.properties")
if (versionPropsFile.exists()) {
    versionProps.load(FileInputStream(versionPropsFile))
}
val desktopPackageVersion = listOf("major", "minor", "patch")
    .joinToString(".") { versionProps.getProperty(it)?.toIntOrNull()?.toString() ?: "0" }

compose.desktop {
    application {
        mainClass = "com.hereliesaz.sirmatchalot.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "SirMatchALot"
            packageVersion = desktopPackageVersion

            // One source image, three platform icon formats generated from it
            // (see desktopApp/src/main/resources) — java.awt.FileDialog and
            // Compose's own dialogs already use the OS's native chrome, so
            // this is the last thing that would otherwise still look
            // unbranded next to a launcher-icon'd app.
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
            }
            macOS {
                iconFile.set(project.file("src/main/resources/icon.icns"))
            }
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
        }
    }
}
