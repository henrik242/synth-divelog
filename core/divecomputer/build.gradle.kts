plugins {
    id("synth.kmp-library")
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets.commonMain.dependencies {
        implementation(project(":core:model"))
    }
}
