// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package no.synth.divelog.core.divecomputer.transport

/** One direction of a byte exchange on the link. */
enum class Direction { WRITE, READ }

/**
 * A single recorded step of a serial exchange. [timeout] marks a point where the
 * device produced nothing and the read timed out; its [data] is empty.
 */
data class TransportEvent(
    val direction: Direction,
    val data: ByteArray,
    val atMillis: Long = 0,
    val timeout: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransportEvent) return false
        return direction == other.direction &&
            data.contentEquals(other.data) &&
            atMillis == other.atMillis &&
            timeout == other.timeout
    }

    override fun hashCode(): Int {
        var result = direction.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + atMillis.hashCode()
        result = 31 * result + timeout.hashCode()
        return result
    }
}

/**
 * A full captured exchange. Serialises to a plain-text, hex-per-line form so
 * fixtures stay reviewable:
 *
 *     W 0102ab        a write of three bytes
 *     R c0...c0       a read
 *     T               a read that timed out
 *
 * An optional `@<millis>` suffix records the time offset.
 */
data class Transcript(val events: List<TransportEvent>) {
    fun toText(): String = buildString {
        for (e in events) {
            when {
                e.timeout -> append("T")
                e.direction == Direction.WRITE -> append("W ").append(e.data.toHex())
                else -> append("R ").append(e.data.toHex())
            }
            if (e.atMillis != 0L) append(" @").append(e.atMillis)
            append('\n')
        }
    }

    companion object {
        fun fromText(text: String): Transcript {
            val events = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { parseLine(it) }
                .toList()
            return Transcript(events)
        }

        private fun parseLine(line: String): TransportEvent {
            val parts = line.split(' ').filter { it.isNotEmpty() }
            val tag = parts[0].uppercase()
            var atMillis = 0L
            var hex = ""
            for (p in parts.drop(1)) {
                if (p.startsWith("@")) atMillis = p.drop(1).toLong() else hex = p
            }
            return when (tag) {
                "W" -> TransportEvent(Direction.WRITE, hex.fromHex(), atMillis)
                "R" -> TransportEvent(Direction.READ, hex.fromHex(), atMillis)
                "T" -> TransportEvent(Direction.READ, ByteArray(0), atMillis, timeout = true)
                else -> error("Unknown transcript line: $line")
            }
        }
    }
}

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
