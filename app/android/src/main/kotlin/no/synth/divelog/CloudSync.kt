package no.synth.divelog

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Minimal sync of a single dive-log file to and from a user-provided HTTP(S) URL
 * (for example a WebDAV or other file store), with optional basic auth. This is a
 * plain file GET/PUT; it does not speak the Subsurface cloud's git protocol.
 */
object CloudSync {
    /** Download the file body from [url]. */
    fun pull(url: String, username: String, password: String): String {
        val conn = open(url, username, password, "GET")
        return try {
            requireOk(conn)
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Upload [body] to [url] with PUT. */
    fun push(url: String, username: String, password: String, body: String) {
        val conn = open(url, username, password, "PUT")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/xml")
        try {
            conn.outputStream.use { it.write(body.encodeToByteArray()) }
            requireOk(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, username: String, password: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        if (username.isNotBlank()) {
            val token = Base64.getEncoder().encodeToString("$username:$password".encodeToByteArray())
            conn.setRequestProperty("Authorization", "Basic $token")
        }
        return conn
    }

    private fun requireOk(conn: HttpURLConnection) {
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("Server returned $code ${conn.responseMessage ?: ""}".trim())
    }
}
