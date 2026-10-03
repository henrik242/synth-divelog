// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package no.synth.divelog.core.divecomputer.transport

/**
 * A byte-oriented link to a dive computer. Deliberately general: today it is
 * Bluetooth Classic serial, but BLE or a USB serial line could implement it too.
 * Reads and writes are blocking; callers run them off the main thread.
 */
interface Transport {
    fun open()

    fun write(data: ByteArray)

    /**
     * Reads at least one byte into [buffer] at [offset], up to [length] bytes,
     * and returns the count. Throws [TransportTimeoutException] if nothing
     * arrives within [timeoutMs].
     */
    fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int

    fun close()
}

open class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

class TransportTimeoutException(message: String = "Timed out waiting for data") :
    TransportException(message)

class TransportClosedException(message: String = "Transport is closed") :
    TransportException(message)
