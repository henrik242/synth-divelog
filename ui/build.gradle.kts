import java.time.LocalDate

plugins {
    id("synth.kmp-library")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

val gitCommitCount: Provider<String> by rootProject.extra
val gitShortSha: Provider<String> by rootProject.extra

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
            implementation(project(":core:divecomputer"))
            implementation(project(":core:gas"))
            implementation(project(":core:logbook"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            // Unified Compose map for the dive-site picker and the read-only site map.
            implementation(libs.maplibre.compose)
        }
        jvmMain.dependencies {
            // Metal renderer for the desktop map (Apple Silicon).
            runtimeOnly(libs.maplibre.compose.runtime.metal.macos.arm64)
        }
        androidMain.dependencies {
            // OpenGL renderer for the Android map.
            runtimeOnly(libs.maplibre.compose.runtime.opengl.android)
        }
    }
}
