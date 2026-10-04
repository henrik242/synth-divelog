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
    val path = if (portName.startsWith("/")) portName else "/dev/$portName"
    val fd = c.open(path, O_RDWR or O_NOCTTY or O_NONBLOCK)
    if (fd < 0) { println("open($path) failed"); return }
    try {
        val t = Termios()
        c.tcgetattr(fd, t)
        c.cfmakeraw(t)
        c.cfsetspeed(t, NativeLong(9600))
        t.c_cflag = NativeLong((t.c_cflag.toLong() or CS8 or CLOCAL or CREAD) and PARENB.inv() and CSTOPB.inv() and CRTSCTS.inv())
        t.c_cc[VMIN] = 0
        t.c_cc[VTIME] = 5 // 0.5s read timeout
        c.tcsetattr(fd, TCSANOW, t)
        c.fcntl(fd, F_SETFL, 0) // clear O_NONBLOCK so reads honour VMIN/VTIME

        val lat = Memory(8)
        lat.setLong(0, 1L) // 1 microsecond: minimum latency
        val latRc = c.ioctl(fd, NativeLong(IOSSDATALAT), lat)
        println("IOSSDATALAT set rc=$latRc (0 = ok)")

        setModem(c, fd, TIOCM_DTR, true) // power
        setModem(c, fd, TIOCM_RTS, false) // start in receive
        Thread.sleep(100)
        c.tcflush(fd, TCIOFLUSH)

        val cmd0190 = run {
            val b = byteArrayOf(0x05, 0x00, 0x03, 0x01, 0x90.toByte(), 0x08)
            var crc = 0
            for (x in b) crc = crc xor (x.toInt() and 0xFF)
            b + crc.toByte()
        }
        val getVersion = byteArrayOf(0x0F, 0x00, 0x00, 0x0F)

        fun exchange(cmd: ByteArray): ByteArray {
            c.tcflush(fd, TCIOFLUSH)
            setModem(c, fd, TIOCM_RTS, true) // transmit
            c.write(fd, cmd, NativeLong(cmd.size.toLong()))
            c.tcdrain(fd) // block until the command has physically drained
            setModem(c, fd, TIOCM_RTS, false) // receive
            val buf = ByteArray(32)
            val n = c.read(fd, buf, NativeLong(32)).toInt()
            return if (n > 0) buf.copyOf(n) else ByteArray(0)
        }

        val v = exchange(getVersion)
        println("Version check: ${if (v.isEmpty()) "(no reply)" else hex(v)}")
        Thread.sleep(150)

        var ok = 0
        repeat(30) {
            val r = exchange(cmd0190)
            if (r.isNotEmpty() && r[0] == 0x05.toByte()) ok++
            Thread.sleep(40)
        }
        println("tcdrain turnaround on 0x0190: $ok/30")
    } finally {
        runCatching { c.close(fd) }
    }
}
