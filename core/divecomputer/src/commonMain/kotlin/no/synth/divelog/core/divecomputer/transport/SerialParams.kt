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
 * half-duplex but with opposite RTS polarity (see [rtsTransmitHigh]) and only the
 * old Vyper family echoes the sent bytes back (see [discardsEcho]).
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
     * receive is the opposite level. The old Vyper family drives RTS high to transmit
     * (true); the newer D9 family is inverted and drives RTS low to transmit (false).
     */
    val rtsTransmitHigh: Boolean = true,
    /**
     * On a [halfDuplex] line, whether the cable echoes the bytes we send so the
     * transport must read and drop them. True for the old single-wire Vyper cable;
     * false for the D9 family, whose RTS direction control leaves no echo.
     */
    val discardsEcho: Boolean = true,
    /** Milliseconds to let the interface power up after setting the lines, before use. */
    val powerUpMs: Long = 0,
    /** Milliseconds to let the UART drain before switching RTS to receive. */
    val txSettleMs: Long = 0,
    /** Milliseconds to wait after switching RTS to receive before the reply is expected. */
    val rxSettleMs: Long = 0,
)
