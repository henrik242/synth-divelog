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
                // Not the iOS app's id: that one can also run on Apple Silicon Macs.
                bundleID = "no.synth.divelog.desktop"
                // Hardened-runtime entitlements for signing; signing itself is switched on with
                // -Pcompose.desktop.mac.sign=true (see docs/release.md).
                entitlementsFile.set(project.file("packaging/entitlements.plist"))
                runtimeEntitlementsFile.set(project.file("packaging/entitlements.plist"))
                // The bundled Bluetooth bridges run under the app's Bluetooth permission.
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSBluetoothAlwaysUsageDescription</key>
                        <string>Synth Divelog connects to Bluetooth dive computers to download dives.</string>
                    """.trimIndent()
                }
            }
        }
        // Bundled helper binaries (the macOS Bluetooth bridges); see buildRfcommBridge.
        nativeDistributions.appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))
    }
}

// The macOS Bluetooth helpers, built into the app resources, where MacHelper.locate() finds
// them at runtime:
// - rfcomm-bridge (Swift) opens a paired device's RFCOMM channel and relays it over
//   stdin/stdout, since the /dev/cu.* nodes no longer bring the link up.
// - ble-bridge is core:transport's macOS executable: the CoreBluetooth link iOS uses too,
//   relayed over stdin/stdout, since a JVM cannot reach CoreBluetooth. Apple Silicon only.
// Compose signs the app but not files in its resources, and notarization rejects an unsigned
// helper, so a signed build (-Pcompose.desktop.mac.sign=true) signs them here. `gradlew run`
// uses the debug ble-bridge, which links faster.
val isMac = System.getProperty("os.name").startsWith("Mac")
val helpersDir = layout.buildDirectory.dir("appResources/macos")
val rfcommBridge = helpersDir.map { it.file("rfcomm-bridge") }
val bleBridge = helpersDir.map { it.file("ble-bridge") }

/** core:transport's linked ble-bridge, [buildType] "Debug" or "Release". */
fun linkedBleBridge(buildType: String): FileCollection {
    val files = configurations.create("bleBridge$buildType") {
        isCanBeConsumed = false
        isTransitive = false
    }
    dependencies.add(files.name, dependencies.project(":core:transport", configuration = "bleBridge$buildType"))
    return files
}
val bleBridgeDebug = linkedBleBridge("Debug")
val bleBridgeRelease = linkedBleBridge("Release")
val macSigningIdentity = providers.gradleProperty("compose.desktop.mac.sign")
    .filter { it.toBoolean() }
    .flatMap { providers.gradleProperty("compose.desktop.mac.signing.identity") }
    .orElse("")

/** Run [command] (built when the task runs) to produce the helper [output], then sign it in a signed build. */
fun Exec.macHelper(output: File, command: () -> String) {
    val identity = macSigningIdentity.get()
    val mac = isMac
    onlyIf { mac }
    inputs.property("signingIdentity", identity)
    outputs.file(output)
    val sign = "codesign --force --options runtime --timestamp --sign 'Developer ID Application: $identity' '${output.absolutePath}'"
    doFirst {
        output.parentFile.mkdirs()
        commandLine("sh", "-c", if (identity.isEmpty()) command() else "${command()} && $sign")
    }
}

val buildRfcommBridge by tasks.registering(Exec::class) {
    description = "Compile the macOS Bluetooth serial bridge"
    val source = file("native/rfcomm-bridge.swift")
    val output = rfcommBridge.get().asFile
    inputs.file(source)
    macHelper(output) { "swiftc -O -framework IOBluetooth '${source.absolutePath}' -o '${output.absolutePath}'" }
}
val buildBleBridge by tasks.registering(Exec::class) {
    description = "Bundle the macOS Bluetooth LE bridge"
    val linked = bleBridgeRelease
    val output = bleBridge.get().asFile
    if (isMac) inputs.files(linked) // elsewhere there is nothing to link
    macHelper(output) { "cp '${linked.singleFile.absolutePath}' '${output.absolutePath}'" }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(buildRfcommBridge, buildBleBridge) }
tasks.withType<JavaExec>().configureEach {
    dependsOn(buildRfcommBridge)
    systemProperty("synth.rfcommBridge", rfcommBridge.get().asFile.absolutePath)
    if (isMac) {
        val linked = bleBridgeDebug
        inputs.files(linked)
        jvmArgumentProviders.add { listOf("-Dsynth.bleBridge=${linked.singleFile.absolutePath}") }
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

// Command-line Shearwater download over Bluetooth, to test the link without the app.
// Run: ./gradlew :app:desktop:shearwaterCapture --args="[PETREL|PETREL2|PETREL3|PREDATOR|PEREGRINE] [LE] [name or id] [dive count]"
tasks.register<JavaExec>("shearwaterCapture") {
    group = "application"
    description = "Download the latest Shearwater dives over Bluetooth and print them"
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvmMain.compileTaskProvider)
    classpath = jvmMain.runtimeDependencyFiles + jvmMain.output.allOutputs
    mainClass.set("no.synth.divelog.desktop.ShearwaterCaptureKt")
}
