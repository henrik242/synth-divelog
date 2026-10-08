// A shared Kotlin Multiplatform library: JVM (desktop and common tests) and Android, with
// a jvmAndAndroidMain source set for JDK code both use. A module adds its iOS targets.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
fun sdk(name: String) = libs.findVersion(name).get().requiredVersion.toInt()

kotlin {
    applyDefaultHierarchyTemplate {
        common {
            group("jvmAndAndroid") {
                withJvm()
                // The Android KMP library target is not matched by withAndroidTarget().
                withCompilations { it.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm }
            }
        }
    }

    jvm()

    android {
        // ":core:model" -> "no.synth.divelog.core.model"
        namespace = "no.synth.divelog" + project.path.replace(':', '.')
        compileSdk = sdk("android-compileSdk")
        minSdk = sdk("android-minSdk")

        withHostTestBuilder {}
    }

    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
    }
}
