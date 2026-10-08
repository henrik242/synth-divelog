import java.time.LocalDate

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
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

/** Writes `BuildInfo`: the version string shown in Settings, "<commit count>.<sha> <date>". */
abstract class GenerateBuildInfoTask : DefaultTask() {
    @get:Input abstract val commitCount: Property<String>
    @get:Input abstract val shortSha: Property<String>
    @get:Input abstract val buildDate: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val dir = outputDir.get().asFile.resolve("no/synth/divelog/ui")
        dir.mkdirs()
        dir.resolve("BuildInfo.kt").writeText(
            """
            |package no.synth.divelog.ui
            |
            |object BuildInfo {
            |    const val GIT_COMMIT_COUNT = "${commitCount.get()}"
            |    const val GIT_SHORT_SHA = "${shortSha.get()}"
            |    const val BUILD_DATE = "${buildDate.get()}"
            |    const val VERSION_INFO = "${commitCount.get()}.${shortSha.get()} ${buildDate.get()}"
            |}
            |
            """.trimMargin(),
        )
    }
}

val generateBuildInfo = tasks.register<GenerateBuildInfoTask>("generateBuildInfo") {
    commitCount.set(gitCommitCount)
    shortSha.set(gitShortSha)
    buildDate.set(LocalDate.now().toString())
    outputDir.set(layout.buildDirectory.dir("generated/buildinfo"))
}

kotlin {
    // MapLibre Compose desktop is built for Java 25 bytecode, so the JVM
    // compilation must run on a JDK 25 toolchain (and the desktop app runs on 25).
    jvmToolchain(25)

    // SettingsStore and CloudGit are expect/actual classes, still Beta in Kotlin.
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

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
        commonMain {
            kotlin.srcDir(generateBuildInfo.map { it.outputDir })
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.androidx.navigationevent.compose)
            implementation(compose.materialIconsExtended)
            implementation(project(":core:model"))
            implementation(project(":core:db"))
            implementation(project(":core:formats"))
            implementation(project(":core:divecomputer"))
            implementation(project(":core:gas"))
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
