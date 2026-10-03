// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package no.synth.divelog.core.divecomputer.transport

/**
 * Wraps a real [Transport] and records every write and read as a [Transcript].
 * The capture screen uses this so a real download can be saved and later
 * replayed as a test fixture. The delegate is untouched; recording only
 * observes.
 */
class RecordingTransport(
    private val delegate: Transport,
    private val clock: () -> Long = { 0L },
) : Transport {
    private val events = mutableListOf<TransportEvent>()
    private val startMillis by lazy { clock() }

    fun transcript(): Transcript = Transcript(events.toList())

    override fun open() = delegate.open()

    override fun write(data: ByteArray) {
        delegate.write(data)
        events.add(TransportEvent(Direction.WRITE, data.copyOf(), elapsed()))
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        try {
            val n = delegate.read(buffer, offset, length, timeoutMs)
            events.add(TransportEvent(Direction.READ, buffer.copyOfRange(offset, offset + n), elapsed()))
            return n
        } catch (e: TransportTimeoutException) {
            events.add(TransportEvent(Direction.READ, ByteArray(0), elapsed(), timeout = true))
            throw e
        }
    }

    override fun close() = delegate.close()

    private fun elapsed(): Long = clock() - startMillis
}
