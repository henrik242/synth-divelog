package no.synth.divelog.desktop

import com.fazecast.jSerialComm.SerialPort

/**
 * A thorough characterization suite for a D9/HelO2 serial cable, aimed at the third-party
 * (echoing, single-wire) interface. It walks baud/line settings, checks whether the cable
 * echoes, and measures the reference-style echo-read turnaround (write, read the echo back
 * which blocks until the command is on the wire, flip RTS, read the reply) against a plain
 * fixed-settle turnaround, in both RTS polarities, with repeat counts for reliability.
 *
 * GetVersion: 0f 00 00 0f -> 8-byte reply (0f 00 04 <4 version> crc).
 * ReadMemory 0x0190 count 8: 05 00 03 01 90 08 9f -> 15-byte reply (05 00 0b 01 90 08 <8> crc).
 */
private const val SEMI = SerialPort.TIMEOUT_READ_SEMI_BLOCKING or SerialPort.TIMEOUT_WRITE_BLOCKING

private fun xor(b: ByteArray, n: Int): Byte {
    var c = 0
    for (i in 0 until n) c = c xor (b[i].toInt() and 0xFF)
    return c.toByte()
}

private fun readMemCmd(address: Int, count: Int): ByteArray {
    val c = byteArrayOf(0x05, 0x00, 0x03, ((address ushr 8) and 0xFF).toByte(), (address and 0xFF).toByte(), (count and 0xFF).toByte())
    return c + xor(c, c.size)
}

private val GET_VERSION = byteArrayOf(0x0F, 0x00, 0x00, 0x0F)
private val READ_0190 = readMemCmd(0x0190, 8)

private fun hx(data: ByteArray): String =
    if (data.isEmpty()) "(none)" else data.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** Accumulate up to [n] bytes within [timeoutMs]; returns however many arrived. */
private fun SerialPort.readUpTo(n: Int, timeoutMs: Long): ByteArray {
    val buf = ByteArray(n)
    var got = 0
    val deadline = System.currentTimeMillis() + timeoutMs
    while (got < n && System.currentTimeMillis() < deadline) {
        val tmp = ByteArray(n - got)
        val r = readBytes(tmp, tmp.size)
        if (r > 0) { tmp.copyInto(buf, got, 0, r); got += r }
    }
    return buf.copyOf(got)
}

private fun validReply(reply: ByteArray, cmd0: Byte, replyLen: Int): Boolean =
    reply.size >= replyLen && reply[0] == cmd0 && reply[replyLen - 1] == xor(reply, replyLen - 1)

fun cableTestSuite(portName: String) {
    val port = SerialPort.getCommPort(portName)
    port.setComPortTimeouts(SEMI, 120, 2000)
    if (!port.openPort()) { println("Could not open $portName"); return }
    try {
        println("=== Cable test suite on $portName ===")
        baudCheck(port)
        port.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        port.setComPortTimeouts(SEMI, 120, 2000)
        port.setDTR()

        lineSweep(port)
        echoCharacterization(port)
        fullDuplexCheck(port)

        // The main event: reference echo-read turnaround vs plain fixed-settle, both polarities.
        for (txHigh in listOf(true, false)) {
            val pol = if (txHigh) "RTS high=TX" else "RTS low=TX"
            turnaroundReps(port, "echo-read  GetVersion  [$pol]", GET_VERSION, 8, txHigh, echoRead = true)
            turnaroundReps(port, "echo-read  Read 0x0190 [$pol]", READ_0190, 15, txHigh, echoRead = true)
            turnaroundReps(port, "settle=8   GetVersion  [$pol]", GET_VERSION, 8, txHigh, echoRead = false)
            turnaroundReps(port, "settle=8   Read 0x0190 [$pol]", READ_0190, 15, txHigh, echoRead = false)
        }
        println("=== done ===")
    } finally {
        runCatching { port.closePort() }
    }
}

/** Try GetVersion at 9600 and 115200 (the two bauds the D9 family autodetects). */
private fun baudCheck(port: SerialPort) {
    println("\n-- baud check (GetVersion, echo-read, RTS high=TX) --")
    for (baud in listOf(9600, 115200)) {
        port.setComPortParameters(baud, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        port.setComPortTimeouts(SEMI, 120, 2000)
        port.setDTR(); port.clearRTS()
        Thread.sleep(80); runCatching { port.flushIOBuffers() }
        val (echo, reply) = oneExchange(port, GET_VERSION, 8, txHigh = true, echoRead = true)
        println("  $baud: echo=${hx(echo)} reply=${hx(reply)}")
    }
}

/** Sweep DTR/RTS/duplex and send GetVersion once each, reporting raw bytes. */
private fun lineSweep(port: SerialPort) {
    println("\n-- line sweep (GetVersion once each) --")
    data class Cfg(val label: String, val dtr: Boolean, val rtsTx: Boolean?, val txHigh: Boolean)
    val cfgs = listOf(
        Cfg("half-duplex RTS high=TX", true, null, true),
        Cfg("half-duplex RTS low=TX", true, null, false),
        Cfg("full RTS high fixed", true, true, true),
        Cfg("full RTS low fixed", true, false, true),
        Cfg("DTR off, RTS high=TX", false, null, true),
    )
    for (cfg in cfgs) {
        if (cfg.dtr) port.setDTR() else port.clearDTR()
        runCatching { port.flushIOBuffers() }
        when (cfg.rtsTx) {
            null -> { // half-duplex: toggle around the write
                if (cfg.txHigh) port.setRTS() else port.clearRTS()
                port.writeBytes(GET_VERSION, GET_VERSION.size)
                Thread.sleep(8)
                if (cfg.txHigh) port.clearRTS() else port.setRTS()
            }
            else -> { // fixed RTS, no toggle
                if (cfg.rtsTx) port.setRTS() else port.clearRTS()
                port.writeBytes(GET_VERSION, GET_VERSION.size)
            }
        }
        val got = port.readUpTo(16, 300)
        println("  ${cfg.label}: ${hx(got)}")
        Thread.sleep(120)
    }
    port.setDTR()
}

/** Does the cable echo the sent bytes while transmitting? */
private fun echoCharacterization(port: SerialPort) {
    println("\n-- echo characterization (RTS high=TX) --")
    runCatching { port.flushIOBuffers() }
    port.setRTS()
    port.writeBytes(GET_VERSION, GET_VERSION.size)
    val duringTx = port.readUpTo(8, 150) // read while still in transmit
    port.clearRTS()
    val afterTx = port.readUpTo(16, 300) // read after switching to receive
    println("  sent ${hx(GET_VERSION)}")
    println("  during TX (RTS high): ${hx(duringTx)}  ${if (duringTx.isNotEmpty() && duringTx.contentEquals(GET_VERSION.copyOf(duringTx.size))) "<- echo of our bytes" else ""}")
    println("  after RTS->RX:        ${hx(afterTx)}")
    Thread.sleep(120)
}

/** Is RX live without toggling RTS (true full-duplex)? */
private fun fullDuplexCheck(port: SerialPort) {
    println("\n-- full-duplex check (no RTS toggle) --")
    for (high in listOf(true, false)) {
        var hits = 0
        var sample = ByteArray(0)
        repeat(10) {
            runCatching { port.flushIOBuffers() }
            if (high) port.setRTS() else port.clearRTS()
            port.writeBytes(GET_VERSION, GET_VERSION.size)
            val got = port.readUpTo(12, 250)
            if (validReply(got, 0x0F, 8)) hits++
            if (sample.isEmpty() && got.isNotEmpty()) sample = got
            Thread.sleep(60)
        }
        println("  RTS ${if (high) "high" else "low"} fixed: $hits/10 valid, sample=${hx(sample)}")
    }
}

private fun oneExchange(port: SerialPort, cmd: ByteArray, replyLen: Int, txHigh: Boolean, echoRead: Boolean): Pair<ByteArray, ByteArray> {
    runCatching { port.flushIOBuffers() }
    if (txHigh) port.setRTS() else port.clearRTS() // transmit
    port.writeBytes(cmd, cmd.size)
    val echo = if (echoRead) {
        port.readUpTo(cmd.size, 250) // blocks until the command loops back (sync)
    } else {
        Thread.sleep(8); ByteArray(0)
    }
    if (txHigh) port.clearRTS() else port.setRTS() // receive
    val reply = port.readUpTo(replyLen + 4, 400)
    return echo to reply
}

private fun turnaroundReps(port: SerialPort, label: String, cmd: ByteArray, replyLen: Int, txHigh: Boolean, echoRead: Boolean) {
    var hits = 0
    var echoOk = 0
    var sample = ByteArray(0)
    val reps = 20
    repeat(reps) {
        val (echo, reply) = oneExchange(port, cmd, replyLen, txHigh, echoRead)
        if (echo.contentEquals(cmd)) echoOk++
        if (validReply(reply, cmd[0], replyLen)) {
            hits++
            if (sample.isEmpty()) sample = reply.copyOf(replyLen)
        }
        Thread.sleep(40)
    }
    val echoNote = if (echoRead) " echoMatched=$echoOk/$reps" else ""
    println("  $label: $hits/$reps valid$echoNote  sample=${hx(sample)}")
}
