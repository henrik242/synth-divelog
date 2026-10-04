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
 * transport must flip direction with RTS and discard the bytes the line echoes
 * back while sending (the old Suunto Vyper family). A full-duplex line (the D9
 * family) leaves it false.
 */
data class SerialParams(
    val baudRate: Int,
    val dataBits: Int = 8,
    val parity: Parity = Parity.NONE,
    val stopBits: Int = 1,
    val halfDuplex: Boolean = false,
    /** DTR held high for the whole session (powers the old Suunto interface). */
    val dtr: Boolean = true,
    /** Milliseconds to let the UART drain before clearing RTS to receive. */
    val txSettleMs: Long = 0,
    /** Milliseconds to wait after clearing RTS before the reply is expected. */
    val rxSettleMs: Long = 0,
)
