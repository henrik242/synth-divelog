plugins {
    id("synth.kmp-library")
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    // The desktop app's Bluetooth LE helper (core:transport) is a macOS executable.
    macosArm64()
}
