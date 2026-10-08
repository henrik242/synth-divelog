plugins {
    `kotlin-dsl`
}

dependencies {
    // The plugins the conventions apply; the root build loads them at the same versions.
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
}
