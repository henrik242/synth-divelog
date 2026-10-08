package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.ProtocolException
import no.synth.divelog.core.divecomputer.toHex
import no.synth.divelog.core.divecomputer.transport.FrameChannel
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport

/**
 * The command layer for Shearwater's diagnostic-style protocol. Each message is
 * a small command payload wrapped in a fixed header and sent as one SLIP frame;
 * the device replies with a mirrored header and an echoed command byte. This
 * implements the older one-byte-length framing used by the Predator and Petrel 1.
 */
class ShearwaterLink(
    transport: Transport,
    private val timeoutMs: Long = 3_000,
) {
    private val channel = FrameChannel(transport, timeoutMs)

    /**
     * False until the device has answered once. A desktop Bluetooth serial port only
     * brings the link up on the first write, which can take several seconds; a reply
     * that arrives after the timeout would then be read as the answer to the retry,
     * leaving every later reply one step behind.
     */
    private var answered = false

    /** Send a command payload and return the response payload (command byte first). */
    fun exchange(payload: ByteArray): ByteArray {
        channel.writeFrame(wrap(payload))
        val frame = channel.readFrame(if (answered) timeoutMs else maxOf(timeoutMs, CONNECT_TIMEOUT_MS))
        answered = true
        return unwrap(frame, requestCommand = payload.firstOrNull())
    }

    private fun wrap(payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_LEN + payload.size)
        out[0] = REQUEST_HI
        out[1] = REQUEST_LO
        out[2] = ((payload.size + 1) and 0xFF).toByte()
        out[3] = 0x00
        payload.copyInto(out, HEADER_LEN)
        return out
    }

    private fun unwrap(frame: ByteArray, requestCommand: Byte?): ByteArray {
        if (frame.size < HEADER_LEN) {
            throw ProtocolException("Short response frame (${frame.size} bytes)")
        }
        if (frame[0] != RESPONSE_HI || frame[1] != RESPONSE_LO) {
            throw ProtocolException("Unexpected response header ${frame.copyOf(2).toHex()}")
        }
        val payload = frame.copyOfRange(HEADER_LEN, frame.size)
        if (payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == NAK) {
            val forCmd = payload.getOrNull(1)
            val code = payload.getOrNull(2)
            throw ProtocolException(
                "Device rejected command ${requestCommand?.toUByte()} (for=${forCmd?.toUByte()}, code=${code?.toUByte()})",
            )
        }
        return payload
    }

    companion object {
        const val HEADER_LEN = 4
        const val CONNECT_TIMEOUT_MS = 10_000L

        /**
         * Classic Bluetooth SPP is a clean full-duplex byte stream, so no half-duplex RTS
         * toggling and no echo discard; the baud is nominal over RFCOMM.
         */
        val SERIAL_PARAMS = SerialParams(baudRate = 115200)
        private const val REQUEST_HI = 0xFF.toByte()
        private const val REQUEST_LO = 0x01.toByte()
        private const val RESPONSE_HI = 0x01.toByte()
        private const val RESPONSE_LO = 0xFF.toByte()
        private const val NAK = 0x7F
    }
}
