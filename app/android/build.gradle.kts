import java.time.LocalDate
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

val gitCommitCount: Provider<String> by rootProject.extra
val gitShortSha: Provider<String> by rootProject.extra

// Release signing comes from the environment (CI) or local.properties. A build without it
// produces an unsigned release bundle, which is fine for anything but a store upload.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(key: String): String? =
    (System.getenv(key) ?: localProperties.getProperty(key))?.takeIf { it.isNotBlank() }

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
        val releaseStore = signingValue("SIGNING_STORE_FILE")?.let { rootProject.file(it) }
        if (releaseStore?.exists() == true) {
            create("release") {
                storeFile = releaseStore
                storePassword = signingValue("SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("SIGNING_KEY_ALIAS")
                keyPassword = signingValue("SIGNING_KEY_PASSWORD")
            }
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
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":core:db"))
    implementation(project(":core:logbook"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    implementation(compose.runtime)
    implementation(compose.ui)

    implementation(libs.kotlinx.coroutines.android)
}
