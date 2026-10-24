plugins {
    id("synth.kmp-library")
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    macosArm64() // for core:transport's macOS Bluetooth LE helper

    sourceSets.commonMain.dependencies {
        implementation(project(":core:model"))
    }
}
