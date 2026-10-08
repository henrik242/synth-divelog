plugins {
    id("synth.kmp-library")
    alias(libs.plugins.sqldelight)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(libs.sqldelight.runtime)
            // Queries as Flows, so screens follow the data.
            implementation(libs.sqldelight.coroutines)
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.driver.android)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.driver.sqlite)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.driver.native)
        }
    }
}

sqldelight {
    databases {
        create("DiveDatabase") {
            packageName.set("no.synth.divelog.core.db.sql")
        }
    }
}
