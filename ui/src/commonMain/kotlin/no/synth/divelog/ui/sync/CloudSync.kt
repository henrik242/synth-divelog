package no.synth.divelog.ui.sync

/**
 * Minimal sync of a single dive-log file to and from a user-provided HTTP(S) URL
 * (for example a WebDAV or other file store), with optional basic auth. A plain
 * file GET/PUT; it does not speak any git protocol.
 */
object CloudSync {
    /** Download the file body from [url]. */
    suspend fun pull(url: String, username: String, password: String): String =
        httpRequest(url, "GET", username, password, null)

    /** Upload [body] to [url] with PUT. */
    suspend fun push(url: String, username: String, password: String, body: String) {
        httpRequest(url, "PUT", username, password, body)
    }
}

/** Platform HTTP. Returns the response body; throws on a non-2xx status. */
internal expect suspend fun httpRequest(
    url: String,
    method: String,
    username: String,
    password: String,
    body: String?,
): String
