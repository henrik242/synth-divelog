package no.synth.divelog.core.transport

/** System clock and thread sleep for a [SerialLine] on the JVM and Android. */
abstract class JvmSerialLine : SerialLine {
    override fun nowMs(): Long = System.currentTimeMillis()

    override fun sleep(ms: Long) = try {
        Thread.sleep(ms)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}
