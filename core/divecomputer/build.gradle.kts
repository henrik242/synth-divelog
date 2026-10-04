plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvm()

    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "no.synth.divelog.core.divecomputer"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withHostTestBuilder {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
