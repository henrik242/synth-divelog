import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(project(":core:model"))
            implementation(project(":core:db"))
            implementation(project(":core:formats"))
            implementation(project(":ui"))
            implementation(libs.sqldelight.driver.sqlite)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

compose.desktop {
    application {
        mainClass = "no.synth.divelog.desktop.MainKt"
        // Native packaging (jpackage) needs a full JDK, which the JBR used by the
        // Android tooling lacks. Use a provisioned JDK 21 toolchain for it.
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get().metadata.installationPath.asFile.absolutePath
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "SynthDivelog"
            packageVersion = "1.0.0"
        }
    }
}
