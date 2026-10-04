package no.synth.divelog.ui.sync

/**
 * iOS has no readily available git client, so cloud sync is not wired here yet.
 * Both operations fail cleanly with a message the UI can show, the same way the
 * serial download is Android-only.
 */
actual class CloudGit actual constructor(workDir: String) {
    actual suspend fun pull(email: String, password: String): Map<String, String> =
        throw UnsupportedOperationException(CLOUD_UNAVAILABLE)

    actual suspend fun push(email: String, password: String, files: Map<String, String>): Unit =
        throw UnsupportedOperationException(CLOUD_UNAVAILABLE)
}
