package no.synth.divelog.core.divecomputer.suunto

/**
 * The checksum both Suunto serial families use: a single byte that is the XOR of
 * every preceding byte of the packet. Shared by the old Vyper family and the
 * Vyper2 family.
 */
internal object SuuntoCrc {
    fun xor(data: ByteArray, from: Int = 0, to: Int = data.size): Byte {
        var crc = 0
        for (i in from until to) crc = crc xor (data[i].toInt() and 0xFF)
        return crc.toByte()
    }

    /** Return [data] with its XOR checksum byte appended. */
    fun appended(data: ByteArray): ByteArray = data + xor(data)
}
