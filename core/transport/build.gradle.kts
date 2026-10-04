plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvm()

    android {
        namespace = "no.synth.divelog.core.transport"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withHostTestBuilder {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:divecomputer"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            // USB host serial (FTDI/PL2303/CP210x/CDC-ACM/CH34x) for wired downloads.
            implementation(libs.usbserial.android)
        }
        jvmMain.dependencies {
            // Desktop serial ports for the same wired transport behind Transport.
            implementation(libs.jserialcomm)
        }
    }
}
