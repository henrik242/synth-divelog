package no.synth.divelog.ui.sync

/**
 * Client for the dive cloud, which stores a logbook as a git repository over
 * HTTPS. The repository and branch are derived from the account email; the
 * account password is the git credential. [pull] clones or updates the repo and
 * returns its files keyed by path; [push] writes the files back, commits and
 * pushes. [workDir] is a local directory where the working copy is kept.
 *
 * Desktop and Android back this with a real git client. iOS has none and reports
 * that cloud sync is unavailable there, consistent with the serial download being
 * Android-only.
 */
expect class CloudGit(workDir: String) {
    suspend fun pull(email: String, password: String): Map<String, String>
    suspend fun push(email: String, password: String, files: Map<String, String>)
}

/** The git remote URL and branch for an account. Both are keyed by the email. */
internal data class CloudRepo(val url: String, val branch: String)

internal fun cloudRepo(email: String): CloudRepo {
    val id = sanitizeEmail(email)
    return CloudRepo(url = "$CLOUD_BASE/git/$id", branch = id)
}

/** Keep only the characters the cloud allows in the repo and branch name. */
internal fun sanitizeEmail(email: String): String =
    email.filter { it.isLetterOrDigit() || it in "@._+-" }

private const val CLOUD_BASE = "https://ssrf-cloud-eu.subsurface-divelog.org"

/** Message used by platforms without a git client. */
const val CLOUD_UNAVAILABLE = "Subsurface cloud sync is not available on iOS yet"
