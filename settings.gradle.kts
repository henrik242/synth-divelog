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
    }
}

plugins {
    // Speeds up configuration of toolchains in CI and locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

include(":core:model")
include(":core:db")
include(":core:divecomputer")
include(":core:transport")
include(":core:formats")
include(":ui")
include(":app:android")
include(":app:desktop")
