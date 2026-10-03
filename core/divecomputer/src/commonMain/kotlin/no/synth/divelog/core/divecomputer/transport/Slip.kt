// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package no.synth.divelog.core.divecomputer.transport

/**
 * SLIP framing (RFC 1055). A frame is delimited by [END] bytes; any [END] or
 * [ESC] inside the payload is escaped. Used to delimit messages on the serial
 * link to the dive computer.
 */
object Slip {
    const val END: Int = 0xC0
    const val ESC: Int = 0xDB
    const val ESC_END: Int = 0xDC
    const val ESC_ESC: Int = 0xDD

    /** Escape a payload and wrap it in [END] delimiters. */
    fun encode(payload: ByteArray): ByteArray {
        val out = ArrayList<Byte>(payload.size + 2)
        out.add(END.toByte())
        for (b in payload) {
            when (b.toInt() and 0xFF) {
                END -> {
                    out.add(ESC.toByte())
                    out.add(ESC_END.toByte())
                }
                ESC -> {
                    out.add(ESC.toByte())
                    out.add(ESC_ESC.toByte())
                }
                else -> out.add(b)
            }
        }
        out.add(END.toByte())
        return out.toByteArray()
    }

    /** Un-escape the bytes found between two [END] delimiters. */
    fun unescape(escaped: ByteArray): ByteArray {
        val out = ArrayList<Byte>(escaped.size)
        var i = 0
        while (i < escaped.size) {
            val value = escaped[i].toInt() and 0xFF
            if (value == ESC && i + 1 < escaped.size) {
                i++
                when (escaped[i].toInt() and 0xFF) {
                    ESC_END -> out.add(END.toByte())
                    ESC_ESC -> out.add(ESC.toByte())
                    else -> out.add(escaped[i]) // tolerate an unknown escape
                }
            } else {
                out.add(escaped[i])
            }
            i++
        }
        return out.toByteArray()
    }
}

/** Reads and writes whole SLIP frames over a [Transport]. */
class FrameChannel(
    private val transport: Transport,
    private val defaultTimeoutMs: Long = 3_000,
    readBufferSize: Int = 4_096,
) {
    private val readBuffer = ByteArray(readBufferSize)
    private var bufferPos = 0
    private var bufferLen = 0

    fun writeFrame(payload: ByteArray) {
        transport.write(Slip.encode(payload))
    }

    /** Read one SLIP frame, returning the un-escaped payload. */
    fun readFrame(timeoutMs: Long = defaultTimeoutMs): ByteArray {
        val escaped = ArrayList<Byte>()
        var started = false
        while (true) {
            val value = nextByte(timeoutMs)
            if (value == Slip.END) {
                if (!started) {
                    // Leading or back-to-back delimiter; keep waiting for content.
                    continue
                }
                return Slip.unescape(escaped.toByteArray())
            }
            started = true
            escaped.add(value.toByte())
        }
    }

    private fun nextByte(timeoutMs: Long): Int {
        if (bufferPos >= bufferLen) {
            bufferLen = transport.read(readBuffer, 0, readBuffer.size, timeoutMs)
            bufferPos = 0
            if (bufferLen <= 0) throw TransportException("Unexpected end of stream")
        }
        return readBuffer[bufferPos++].toInt() and 0xFF
    }
}
