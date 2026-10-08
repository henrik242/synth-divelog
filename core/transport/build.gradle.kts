plugins {
    id("synth.kmp-library")
}

// JVM and Android only: jvmAndAndroidMain holds the JDK streams, threads and clock both use.
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:divecomputer"))
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
