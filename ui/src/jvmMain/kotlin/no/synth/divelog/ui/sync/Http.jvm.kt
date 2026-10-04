package no.synth.divelog.ui.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

internal actual suspend fun httpRequest(
    url: String,
    method: String,
    username: String,
    password: String,
    body: String?,
): String = withContext(Dispatchers.IO) {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = method
    conn.connectTimeout = 20_000
    conn.readTimeout = 60_000
    if (username.isNotBlank()) {
        val token = Base64.getEncoder().encodeToString("$username:$password".encodeToByteArray())
        conn.setRequestProperty("Authorization", "Basic $token")
    }
    try {
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/xml")
            conn.outputStream.use { it.write(body.encodeToByteArray()) }
        }
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("Server returned $code ${conn.responseMessage ?: ""}".trim())
        conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}
