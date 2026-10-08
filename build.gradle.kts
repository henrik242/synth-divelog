plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.sqldelight) apply false
}

// The version every app build shows: "<commit count>.<short sha>". CI checks out a pull
// request as GitHub's merge commit, which is on no branch; BUILD_GIT_SHA names the pushed
// commit instead. Unset locally, where HEAD is right. Read with `by rootProject.extra`.
val describedGitRef = providers.environmentVariable("BUILD_GIT_SHA")
    .orNull?.trim()?.takeIf { it.isNotEmpty() } ?: "HEAD"

val gitCommitCount by extra(
    providers.exec { commandLine("git", "rev-list", "--count", describedGitRef) }
        .standardOutput.asText.map { it.trim().ifEmpty { "0" } }.orElse("0"),
)

val gitShortSha by extra(
    providers.exec { commandLine("git", "rev-parse", "--short", describedGitRef) }
        .standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }.orElse("unknown"),
)
