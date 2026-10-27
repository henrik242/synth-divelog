package no.synth.divelog.core.logbook.io

import no.synth.kmpzip.gzip.GzipInputStream
import no.synth.kmpzip.gzip.GzipOutputStream
import no.synth.kmpzip.io.ByteArrayOutputStream
import no.synth.kmpzip.io.readBytes

/** [data] compressed in the gzip format. */
internal fun gzip(data: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GzipOutputStream(out).use { it.write(data) }
    return out.toByteArray()
}

/** Throws [no.synth.kmpzip.gzip.GzipFormatException] for data that is not gzip, or is cut short. */
internal fun gunzip(data: ByteArray): ByteArray = GzipInputStream(data).use { it.readBytes() }
