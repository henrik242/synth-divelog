package no.synth.divelog.core.divecomputer.transport

/** Parity setting for an RS232 serial line. */
enum class Parity { NONE, ODD, EVEN }

/**
 * Line settings a wired serial [Transport] must apply. The dive-computer protocol
 * owns these (they are part of the wire format), so a protocol hands them to the
 * platform transport (USB-serial on Android, jSerialComm on desktop) at connect
 * time. The [Transport] interface stays byte-oriented and ignorant of baud rate.
 *
 * [halfDuplex] marks a line where transmit and receive share one pair, so the
 * transport flips direction with RTS around each write. Both Suunto families are
 * half-duplex with RTS high to transmit; only the old Vyper family echoes the sent
 * bytes back (see [discardsEcho]).
 */
data class SerialParams(
    val baudRate: Int,
    val dataBits: Int = 8,
    val parity: Parity = Parity.NONE,
    val stopBits: Int = 1,
    val halfDuplex: Boolean = false,
    /** DTR held high for the whole session (powers the Suunto interface). */
    val dtr: Boolean = true,
    /**
     * On a [halfDuplex] line, the RTS level that selects the transmit direction;
     * receive is the opposite level. Both Suunto families measured high-to-transmit on
     * hardware (true); the flag stays in case a cable needs the inverted polarity.
     */
    val rtsTransmitHigh: Boolean = true,
    /**
     * On a [halfDuplex] line, whether the cable echoes the bytes we send so the
     * transport must read and drop them. True for the old single-wire Vyper cable;
     * false for the Vyper2 family, whose cable does not echo.
     */
    val discardsEcho: Boolean = true,
    /**
     * Minimum quiet time on the line before each write, counted from the last byte received
     * or the last write. Some devices ignore a command that follows their previous reply too
     * closely.
     */
    val txIdleMs: Long = 0,
    /** Milliseconds to let the interface power up after setting the lines, before use. */
    val powerUpMs: Long = 0,
    /**
     * Extra milliseconds before switching RTS to receive. The transport already waits
     * until the written bytes have had time to leave the UART ([wireTimeMs] from the start
     * of the write, plus 2 ms), so this only adds to that.
     */
    val txSettleMs: Long = 0,
    /** Milliseconds to wait after switching RTS to receive before the reply is expected. */
    val rxSettleMs: Long = 0,
) {
    /** Milliseconds [byteCount] bytes take on the wire at these settings, rounded up. */
    fun wireTimeMs(byteCount: Int): Long {
        val bitsPerByte = 1 + dataBits + (if (parity == Parity.NONE) 0 else 1) + stopBits
        return (byteCount.toLong() * bitsPerByte * 1000 + baudRate - 1) / baudRate
    }
}
