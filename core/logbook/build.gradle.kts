plugins {
    id("synth.kmp-library")
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        // The native SQLite driver links against the system library.
        it.binaries.all { linkerOpts("-lsqlite3") }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:db"))
            implementation(project(":core:formats"))
            implementation(project(":core:divecomputer"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            // Shearwater Cloud exports carry JSON blobs.
            implementation(libs.kotlinx.serialization.json)
        }
        getByName("jvmAndAndroidMain").dependencies {
            // The cloud sync client.
            implementation(libs.jgit)
            // Serial ports for the wired download.
            implementation(project(":core:transport"))
        }
        iosMain.dependencies {
            // Reads and writes Shearwater Cloud database files.
            implementation(libs.sqliter)
        }
        // A raw driver for tests that count queries.
        jvmTest.dependencies {
            implementation(libs.sqldelight.driver.sqlite)
        }
    }
}
