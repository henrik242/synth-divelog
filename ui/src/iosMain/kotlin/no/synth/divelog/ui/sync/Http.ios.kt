package no.synth.divelog.ui.sync

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.HTTPBody
import platform.Foundation.HTTPMethod
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalForeignApi::class, ExperimentalEncodingApi::class)
internal actual suspend fun httpRequest(
    url: String,
    method: String,
    username: String,
    password: String,
    body: String?,
): String = suspendCancellableCoroutine { cont ->
    val request = NSMutableURLRequest(uRL = NSURL(string = url))
    request.HTTPMethod = method
    if (username.isNotBlank()) {
        val token = Base64.encode("$username:$password".encodeToByteArray())
        request.setValue("Basic $token", forHTTPHeaderField = "Authorization")
    }
    if (body != null) {
        request.setValue("application/xml", forHTTPHeaderField = "Content-Type")
        request.HTTPBody = body.encodeToByteArray().toNSData()
    }
    val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
        when {
            error != null -> cont.resumeWithException(RuntimeException(error.localizedDescription))
            else -> {
                val code = (response as? NSHTTPURLResponse)?.statusCode ?: 0L
                if (code !in 200L..299L) {
                    cont.resumeWithException(RuntimeException("Server returned $code"))
                } else {
                    cont.resume((data as? NSData)?.toByteArray()?.decodeToString() ?: "")
                }
            }
        }
    }
    cont.invokeOnCancellation { task.cancel() }
    task.resume()
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData()
    } else {
        usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { memcpy(it.addressOf(0), bytes, length) }
    }
}
