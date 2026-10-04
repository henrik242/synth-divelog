package no.synth.divelog.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * Direct POSIX serial access via JNA (macOS/BSD), used to test whether tcdrain gives a
 * deterministic half-duplex turnaround for the Suunto D9: write the command, tcdrain to
 * block until it has physically drained to the wire, then flip RTS to receive and read.
 * This is what jSerialComm cannot do (no tcdrain, no fd). Also forces the FTDI low-latency
 * mode via IOSSDATALAT. If this reads 0x0190 reliably, the real transport moves to JNA.
 */
internal interface PosixC : Library {
    fun open(path: String, flags: Int): Int
    fun close(fd: Int): Int
    fun read(fd: Int, buf: ByteArray, count: NativeLong): NativeLong
    fun write(fd: Int, buf: ByteArray, count: NativeLong): NativeLong
    fun tcdrain(fd: Int): Int
    fun tcflush(fd: Int, queueSelector: Int): Int
    fun tcgetattr(fd: Int, termios: Termios): Int
    fun tcsetattr(fd: Int, action: Int, termios: Termios): Int
    fun cfmakeraw(termios: Termios)
    fun cfsetspeed(termios: Termios, speed: NativeLong): Int
    fun fcntl(fd: Int, cmd: Int, arg: Int): Int
    fun ioctl(fd: Int, request: NativeLong, arg: Pointer): Int

    companion object {
        val INSTANCE: PosixC = Native.load("c", PosixC::class.java)
    }
}

@Structure.FieldOrder("c_iflag", "c_oflag", "c_cflag", "c_lflag", "c_cc", "c_ispeed", "c_ospeed")
internal class Termios : Structure() {
    @JvmField var c_iflag: NativeLong = NativeLong(0)
    @JvmField var c_oflag: NativeLong = NativeLong(0)
    @JvmField var c_cflag: NativeLong = NativeLong(0)
    @JvmField var c_lflag: NativeLong = NativeLong(0)
    @JvmField var c_cc: ByteArray = ByteArray(20) // NCCS = 20 on macOS
    @JvmField var c_ispeed: NativeLong = NativeLong(0)
    @JvmField var c_ospeed: NativeLong = NativeLong(0)
}

// macOS/BSD constants.
private const val O_RDWR = 0x0002
private const val O_NOCTTY = 0x20000
private const val O_NONBLOCK = 0x0004
private const val F_SETFL = 4
private const val TCSANOW = 0
private const val TCIOFLUSH = 3
private const val CS8 = 0x0300L
private const val CLOCAL = 0x8000L
private const val CREAD = 0x0800L
private const val PARENB = 0x1000L
private const val CSTOPB = 0x0400L
private const val CRTSCTS = 0x00030000L
private const val VMIN = 16
private const val VTIME = 17
private const val TIOCM_DTR = 0x0002
private const val TIOCM_RTS = 0x0004
private const val TIOCMBIS = 0x8004746cL // _IOW('t',108,int): set bits
private const val TIOCMBIC = 0x8004746bL // _IOW('t',107,int): clear bits
private const val IOSSDATALAT = 0x80085400L // _IOW('T',0,unsigned long): data latency (usec)

private fun hex(data: ByteArray): String = data.joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun setModem(c: PosixC, fd: Int, bit: Int, on: Boolean) {
    val arg = Memory(4)
    arg.setInt(0, bit)
    c.ioctl(fd, NativeLong(if (on) TIOCMBIS else TIOCMBIC), arg)
}

/**
 * Open [portName] directly, force low latency, and measure the tcdrain turnaround hit rate
 * reading 0x0190 count 8 (a clean reply starts with 0x05).
 */
fun posixJnaTest(portName: String) {
    val c = PosixC.INSTANCE
    // The reference tools open the tty.* node, not cu.*; map it so this matches.
    val node = if (portName.startsWith("cu.")) "tty." + portName.substring(3) else portName
    val path = if (node.startsWith("/")) node else "/dev/$node"
    println("Opening $path")
    val fd = c.open(path, O_RDWR or O_NOCTTY or O_NONBLOCK)
    if (fd < 0) { println("open($path) failed"); return }
    try {
        val t = Termios()
        c.tcgetattr(fd, t)
        c.cfmakeraw(t)
        c.cfsetspeed(t, NativeLong(9600))
        t.c_cflag = NativeLong((t.c_cflag.toLong() or CS8 or CLOCAL or CREAD) and PARENB.inv() and CSTOPB.inv() and CRTSCTS.inv())
        t.c_cc[VMIN] = 0
        t.c_cc[VTIME] = 1 // 0.1s per blocking read; readN loops to the deadline
        c.tcsetattr(fd, TCSANOW, t)
        c.fcntl(fd, F_SETFL, 0) // clear O_NONBLOCK so reads honour VMIN/VTIME

        // libdc polarity: assert DTR, idle in receive with RTS asserted (set_rts 1).
        setModem(c, fd, TIOCM_DTR, true)
        setModem(c, fd, TIOCM_RTS, true)
        Thread.sleep(100)
        c.tcflush(fd, TCIOFLUSH)

        fun readN(n: Int, deadlineMs: Long): ByteArray {
            val buf = ByteArray(n)
            var got = 0
            val end = System.currentTimeMillis() + deadlineMs
            while (got < n && System.currentTimeMillis() < end) {
                val tmp = ByteArray(n - got)
                val r = c.read(fd, tmp, NativeLong((n - got).toLong())).toInt()
                if (r > 0) { tmp.copyInto(buf, got, 0, r); got += r } else if (r < 0) break
            }
            return buf.copyOf(got)
        }

        // Half-duplex exchange: drive RTS to [sendAssert] to send, read the echo back
        // (blocks until the command is on the wire = the turnaround sync), flip RTS to
        // receive, read the answer.
        fun exchange(cmd: ByteArray, replyLen: Int, sendAssert: Boolean): Pair<ByteArray, ByteArray> {
            c.tcflush(fd, TCIOFLUSH)
            setModem(c, fd, TIOCM_RTS, sendAssert) // send
            c.write(fd, cmd, NativeLong(cmd.size.toLong()))
            val echo = readN(cmd.size, 300)
            setModem(c, fd, TIOCM_RTS, !sendAssert) // receive
            val reply = readN(replyLen, 400)
            return echo to reply
        }

        val cmd0190 = run {
            val b = byteArrayOf(0x05, 0x00, 0x03, 0x01, 0x90.toByte(), 0x08)
            var crc = 0
            for (x in b) crc = crc xor (x.toInt() and 0xFF)
            b + crc.toByte()
        }
        val getVersion = byteArrayOf(0x0F, 0x00, 0x00, 0x0F)

        // Try both RTS polarities: the reference deasserts to send, but our earlier
        // jSerialComm evidence said the opposite, so measure both.
        for (sendAssert in listOf(false, true)) {
            val pol = if (sendAssert) "RTS asserted=send" else "RTS deasserted=send (reference)"
            setModem(c, fd, TIOCM_RTS, !sendAssert) // idle in receive
            Thread.sleep(100); c.tcflush(fd, TCIOFLUSH)
            val (ve, vr) = exchange(getVersion, 8, sendAssert)
            var echoOk = 0
            var replyOk = 0
            var sample = ByteArray(0)
            repeat(30) {
                val (echo, reply) = exchange(cmd0190, 15, sendAssert)
                if (echo.contentEquals(cmd0190)) echoOk++
                if (reply.size >= 15 && reply[0] == 0x05.toByte()) {
                    var crc = 0
                    for (i in 0 until 14) crc = crc xor (reply[i].toInt() and 0xFF)
                    if (reply[14] == crc.toByte()) { replyOk++; if (sample.isEmpty()) sample = reply.copyOf(15) }
                }
                Thread.sleep(40)
            }
            println("[$pol] version: echo=${hex(ve)} reply=${hex(vr)}")
            println("  0x0190: echoMatched=$echoOk/30 replyValid=$replyOk/30 sample=${hex(sample)}")
        }
    } finally {
        runCatching { c.close(fd) }
    }
}
