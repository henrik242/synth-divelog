import org.gradle.jvm.toolchain.JvmVendorSpec
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

val gitCommitCount: Provider<String> by rootProject.extra

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
            implementation(project(":core:logbook"))
            implementation(project(":ui"))
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
            // jpackage wants MAJOR.MINOR.BUILD with MAJOR > 0; same as the iOS short version.
            packageVersion = "1.0.${gitCommitCount.get()}"
            // The packaged app also renders the map through the FFM API.
            jvmArgs += "--enable-native-access=ALL-UNNAMED"
            // The SQLite JDBC driver needs java.sql, which the minimized runtime
            // image would otherwise strip.
            modules("java.sql")
            macOS {
                iconFile.set(project.file("packaging/AppIcon.icns"))
                // The bundled rfcomm-bridge talks to paired Bluetooth dive computers.
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSBluetoothAlwaysUsageDescription</key>
                        <string>Synth Divelog connects to paired Bluetooth dive computers to download dives.</string>
                    """.trimIndent()
                }
            }
        }
        // Bundled helper binaries (the macOS Bluetooth serial bridge); see buildRfcommBridge.
        nativeDistributions.appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))
    }
}

// The macOS Bluetooth serial bridge: opens a paired device's RFCOMM channel and relays it
// over stdin/stdout, since the /dev/cu.* nodes no longer bring the link up. Built into the
// app resources, where MacRfcommTransport.locateBridge() finds it at runtime.
val rfcommBridge = layout.buildDirectory.file("appResources/macos/rfcomm-bridge")
val buildRfcommBridge by tasks.registering(Exec::class) {
    description = "Compile the macOS Bluetooth serial bridge"
    val source = file("native/rfcomm-bridge.swift")
    val output = rfcommBridge.get().asFile
    onlyIf { System.getProperty("os.name").startsWith("Mac") }
    inputs.file(source)
    outputs.file(output)
    doFirst { output.parentFile.mkdirs() }
    commandLine("swiftc", "-O", "-framework", "IOBluetooth", source.absolutePath, "-o", output.absolutePath)
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(buildRfcommBridge) }
tasks.withType<JavaExec>().configureEach {
    dependsOn(buildRfcommBridge)
    systemProperty("synth.rfcommBridge", rfcommBridge.get().asFile.absolutePath)
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

// Command-line Shearwater download over Bluetooth, to test the link without the app.
// Run: ./gradlew :app:desktop:shearwaterCapture --args="[PETREL|PREDATOR] [name or address] [dive count]"
tasks.register<JavaExec>("shearwaterCapture") {
    group = "application"
    description = "Download the latest Shearwater dives over Bluetooth and print them"
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvmMain.compileTaskProvider)
    classpath = jvmMain.runtimeDependencyFiles + jvmMain.output.allOutputs
    mainClass.set("no.synth.divelog.desktop.ShearwaterCaptureKt")
}
