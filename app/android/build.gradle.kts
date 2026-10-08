import java.time.LocalDate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// CI checks out a pull request as GitHub's merge commit, which is on no branch; BUILD_GIT_SHA
// names the pushed commit instead. Unset locally, where HEAD is right.
val describedGitRef = providers.environmentVariable("BUILD_GIT_SHA")
    .orNull?.trim()?.takeIf { it.isNotEmpty() } ?: "HEAD"

val gitCommitCount = providers.exec {
    commandLine("git", "rev-list", "--count", describedGitRef)
}.standardOutput.asText.map { it.trim().ifEmpty { "0" } }.orElse("0")

val gitShortSha = providers.exec {
    commandLine("git", "rev-parse", "--short", describedGitRef)
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }.orElse("unknown")

android {
    namespace = "no.synth.divelog"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "no.synth.divelog"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = gitCommitCount.get().toInt()
        versionName = "${gitCommitCount.get()}.${gitShortSha.get()} ${LocalDate.now()}"
    }

    signingConfigs {
        // A committed debug keystore so every build (local and the ephemeral CI runners)
        // signs with the same certificate. Without it AGP generates a fresh debug key per
        // machine/run and installs over an earlier build fail with
        // INSTALL_FAILED_UPDATE_INCOMPATIBLE. Debug-only; a Play Store release uses its own key.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // Separate app id so the debug build installs alongside a release build.
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":core:model"))
    implementation(project(":core:db"))
    implementation(project(":core:divecomputer"))
    implementation(project(":core:transport"))
    implementation(project(":core:formats"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)

    implementation(libs.jetbrains.lifecycle.viewmodel)
    implementation(libs.jetbrains.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
}
