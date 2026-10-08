package no.synth.divelog.core.logbook.sync

/**
 * Client for the dive cloud, which stores a logbook as a git repository over
 * HTTPS. The repository and branch are derived from the account email; the
 * account password is the git credential. [pull] clones or updates the repo and
 * returns its files keyed by path; [push] writes the files back, commits and
 * pushes.
 *
 * Android and desktop share the JGit-backed `CloudGit`. iOS has no git client and
 * passes no [CloudSync], which hides the cloud actions.
 */
interface CloudSync {
    /** [onProgress] gets the transfer step ("Receiving objects") and its fraction, if known. */
    suspend fun pull(
        email: String,
        password: String,
        onProgress: (task: String, fraction: Float?) -> Unit,
    ): Map<String, String>
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
