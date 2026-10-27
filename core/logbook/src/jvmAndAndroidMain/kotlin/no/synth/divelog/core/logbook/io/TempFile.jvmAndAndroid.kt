package no.synth.divelog.core.logbook.io

import java.io.File

internal actual fun withTempFile(block: (path: String) -> Unit): ByteArray {
    val file = File.createTempFile("synth-backup", ".db")
    try {
        block(file.path)
        return file.readBytes()
    } finally {
        file.delete()
    }
}
