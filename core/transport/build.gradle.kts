plugins {
    id("synth.kmp-library")
}

// jvmAndAndroidMain holds the JDK streams, threads and clock both use. appleMain is the
// CoreBluetooth link: iOS uses it directly, and the desktop app on macOS runs it as the
// ble-bridge helper executable, since a JVM cannot reach CoreBluetooth.
kotlin {
    iosArm64()
    iosSimulatorArm64()
    macosArm64 {
        binaries.executable {
            baseName = "ble-bridge"
            entryPoint = "no.synth.divelog.core.transport.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:divecomputer"))
            // Blocking waits on GATT callbacks (NotificationQueue, CoreBluetoothTransport).
            implementation(libs.kotlinx.coroutines.core)
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
