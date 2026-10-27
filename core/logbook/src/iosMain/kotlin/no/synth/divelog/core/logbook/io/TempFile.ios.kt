package no.synth.divelog.core.logbook.io

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile

@OptIn(ExperimentalForeignApi::class)
internal actual fun withTempFile(block: (path: String) -> Unit): ByteArray {
    val path = NSTemporaryDirectory().trimEnd('/') + "/synth-backup-${NSUUID().UUIDString}.db"
    try {
        block(path)
        return requireNotNull(NSData.dataWithContentsOfFile(path)) { "No file at $path" }.toByteArray()
    } finally {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }
}
