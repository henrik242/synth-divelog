package no.synth.divelog.ui.io

/** iOS has no file import yet, so no reader either. */
actual fun readShearwaterCloudExport(bytes: ByteArray): ShearwaterCloudExport =
    throw UnsupportedOperationException("Shearwater Cloud import is not available on iOS yet")
