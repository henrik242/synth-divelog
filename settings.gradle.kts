rootProject.name = "synth-divelog"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // usb-serial-for-android (com.github.mik3y) is published on JitPack.
        maven {
            setUrl("https://jitpack.io")
            mavenContent { includeGroupAndSubgroups("com.github") }
        }
    }
}

plugins {
    // Speeds up configuration of toolchains in CI and locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // Dependency-update reports for every project: ./gradlew dependencyUpdates
    id("io.github.ben-manes.versions.settings") version "0.64.0"
}

include(":core:model")
include(":core:db")
include(":core:divecomputer")
include(":core:transport")
include(":core:formats")
include(":ui")
include(":app:android")
include(":app:desktop")
