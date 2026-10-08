package no.synth.divelog.core.divecomputer

// Byte helpers shared by the protocols and parsers.

internal fun u16be(d: ByteArray, o: Int): Int =
    ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)

internal fun u16le(d: ByteArray, o: Int): Int =
    (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)

internal fun u24be(d: ByteArray, o: Int): Int =
    ((d[o].toInt() and 0xFF) shl 16) or ((d[o + 1].toInt() and 0xFF) shl 8) or (d[o + 2].toInt() and 0xFF)

internal fun u32be(d: ByteArray, o: Int): Long {
    var v = 0L
    for (i in 0 until 4) v = (v shl 8) or (d[o + i].toLong() and 0xFF)
    return v
}

/** Two lowercase hex digits. */
internal fun hex(b: Byte): String = (b.toInt() and 0xFF).toString(16).padStart(2, '0')

internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(digits[v ushr 4])
        sb.append(digits[v and 0x0F])
    }
    return sb.toString()
}

internal fun String.fromHex(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length: $this" }
    val out = ByteArray(length / 2)
    var i = 0
    while (i < length) {
        val hi = this[i].digitToInt(16)
        val lo = this[i + 1].digitToInt(16)
        out[i / 2] = ((hi shl 4) or lo).toByte()
        i += 2
    }
    return out
}

/** 32-bit FNV-1a of [data] as eight hex digits: a stable per-dive id for devices without one. */
internal fun fnv1a(data: ByteArray): String {
    var hash = 0x811C9DC5u
    for (b in data) {
        hash = hash xor (b.toUInt() and 0xFFu)
        hash *= 0x01000193u
    }
    return hash.toString(16).padStart(8, '0')
}
