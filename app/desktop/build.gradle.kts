import org.gradle.jvm.toolchain.JvmVendorSpec
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(project(":core:model"))
            implementation(project(":core:db"))
            implementation(project(":core:formats"))
            implementation(project(":ui"))
            implementation(libs.sqldelight.driver.sqlite)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

compose.desktop {
    application {
        mainClass = "no.synth.divelog.desktop.MainKt"
        // Native packaging (jpackage) needs a full JDK, which the JBR used by the
        // Android tooling lacks. The bundled runtime must also be new enough to run
        // the compiled bytecode (Java 25), so use a non-JBR JDK 25 (BellSoft/Liberica
        // ships jpackage) for packaging.
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(25))
            vendor.set(JvmVendorSpec.BELLSOFT)
        }.get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "SynthDivelog"
            packageVersion = "1.0.0"
            // The SQLite JDBC driver needs java.sql, which the minimized runtime
            // image would otherwise strip.
            modules("java.sql")
            macOS {
                iconFile.set(project.file("packaging/AppIcon.icns"))
            }
        }
    }
}
