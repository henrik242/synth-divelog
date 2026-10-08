import org.gradle.jvm.toolchain.JvmVendorSpec
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm()

    // MapLibre Compose desktop renders through the Java FFM API, which needs JDK 25.
    jvmToolchain(25)

    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(project(":core:model"))
            implementation(project(":core:db"))
            implementation(project(":core:formats"))
            implementation(project(":core:divecomputer"))
            implementation(project(":core:transport"))
            implementation(project(":ui"))
            implementation(libs.sqldelight.driver.sqlite)
            implementation(libs.jserialcomm)
            implementation(libs.kotlinx.coroutines.core)
            // Provides Dispatchers.Main on desktop (the AWT event thread), which the
            // MapLibre Compose map needs to deliver its engine callbacks.
            implementation(libs.kotlinx.coroutines.swing)
            // The desktop map's presentation host is installed around the window here.
            implementation(libs.maplibre.compose)
        }
    }
}

compose.desktop {
    application {
        mainClass = "no.synth.divelog.desktop.MainKt"
        // MapLibre Compose calls native rendering code through the FFM API.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
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
            // The packaged app also renders the map through the FFM API.
            jvmArgs += "--enable-native-access=ALL-UNNAMED"
            // The SQLite JDBC driver needs java.sql, which the minimized runtime
            // image would otherwise strip.
            modules("java.sql")
            macOS {
                iconFile.set(project.file("packaging/AppIcon.icns"))
            }
        }
    }
}

// Command-line Suunto capture tool, for bringing up the USB dongle before there is
// a desktop download UI. Run: ./gradlew :app:desktop:suuntoCapture --args="[VYPER|VYPER2] [proto] [port]"
tasks.register<JavaExec>("suuntoCapture") {
    group = "application"
    description = "Capture a Suunto dive download over the USB serial dongle"
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvmMain.compileTaskProvider)
    classpath = jvmMain.runtimeDependencyFiles + jvmMain.output.allOutputs
    mainClass.set("no.synth.divelog.desktop.SuuntoCaptureKt")
    standardInput = System.`in`
}
