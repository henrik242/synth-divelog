plugins {
    id("synth.kmp-library")
}

// The android host tests run the common tests against the android actuals, and android's
// SQLiteDatabase does not exist on the host JVM. The Shearwater Cloud writer is covered by
// the same tests on the JVM, and its android actual runs on a device.
val shearwaterCloudWriterTest = "no.synth.divelog.core.logbook.io.ShearwaterCloudWriterTest"
check(file("src/commonTest/kotlin/" + shearwaterCloudWriterTest.replace('.', '/') + ".kt").exists()) {
    "$shearwaterCloudWriterTest moved; update the android host test exclusion"
}
tasks.withType<org.gradle.api.tasks.testing.Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
    filter { excludeTestsMatching(shearwaterCloudWriterTest) }
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
            // Dive-computer links: serial, Bluetooth Classic and Bluetooth LE.
            implementation(project(":core:transport"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            // Shearwater Cloud exports carry JSON blobs.
            implementation(libs.kotlinx.serialization.json)
            // Gzip for backups and Shearwater Cloud blobs.
            implementation(libs.kmp.zip)
        }
        getByName("jvmAndAndroidMain").dependencies {
            // The cloud sync client.
            implementation(libs.jgit)
        }
        // A raw driver for tests that count queries.
        jvmTest.dependencies {
            implementation(libs.sqldelight.driver.sqlite)
            // Virtual time for the automatic backup's waits.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
