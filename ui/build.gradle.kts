plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    // MapLibre Compose desktop is built for Java 25 bytecode, so the JVM
    // compilation must run on a JDK 25 toolchain (and the desktop app runs on 25).
    jvmToolchain(25)

    jvm {
        // Emit Java 17 bytecode anyway; the toolchain only sets the compiler JDK.
        // The Android target keeps the AGP default jvmTarget, which the dexer
        // accepts, so the toolchain bump does not disturb the Android build.
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "SynthDivelogUI"
            isStatic = false
            // The native SQLite driver links against the system library.
            linkerOpts("-lsqlite3")
        }
    }

    android {
        namespace = "no.synth.divelog.ui"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withHostTestBuilder {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation("org.jetbrains.compose.ui:ui-backhandler:${libs.versions.composeMultiplatform.get()}")
            implementation(compose.materialIconsExtended)
            implementation(project(":core:model"))
            implementation(project(":core:db"))
            implementation(project(":core:formats"))
            implementation(project(":core:divecomputer"))
            implementation(libs.jetbrains.lifecycle.viewmodel)
            implementation(libs.jetbrains.lifecycle.viewmodel.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            // Unified Compose map for the dive-site picker and the read-only site map.
            implementation(libs.maplibre.compose)
        }
        jvmMain.dependencies {
            implementation(libs.jgit)
            // Desktop serial ports for the shared wired download.
            implementation(project(":core:transport"))
            // Metal renderer for the desktop map (Apple Silicon).
            runtimeOnly(libs.maplibre.compose.runtime.metal.macos.arm64)
        }
        androidMain.dependencies {
            implementation(libs.jgit)
            // USB-serial adapters and the USB permission flow for the shared wired download.
            implementation(project(":core:transport"))
            // OpenGL renderer for the Android map.
            runtimeOnly(libs.maplibre.compose.runtime.opengl.android)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
