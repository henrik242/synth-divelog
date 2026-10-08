package no.synth.divelog.core.db

import no.synth.divelog.core.db.sql.DiveDatabase
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.core.model.Sample

/** A fresh in-memory database per call. */
fun testDatabase(): DiveDatabase = createDatabase()

/** Convenience builder for an incoming download in tests. */
fun incoming(
    deviceId: Long? = null,
    start: Long = 1_700_000_000,
    duration: Int = 3_600,
    maxDepthMm: Int? = 30_000,
    fingerprint: String = "fp-$start",
    rawData: ByteArray = byteArrayOf(1, 2, 3, 4),
    rawFormatId: String = "test-format",
    samples: List<Sample> = emptyList(),
    events: List<Event> = emptyList(),
): IncomingDive = IncomingDive(
    deviceId = deviceId,
    startEpochSeconds = start,
    utcOffsetSeconds = 3_600,
    durationSeconds = duration,
    maxDepthMm = maxDepthMm,
    meanDepthMm = 15_000,
    waterTempMk = 283_150,
    airTempMk = 293_150,
    rawData = rawData,
    rawFormatId = rawFormatId,
    fingerprint = fingerprint,
    samples = samples,
    events = events,
)

fun sampleAt(time: Int, depthMm: Int, tankPressures: Map<Int, Int> = emptyMap()): Sample =
    Sample(timeOffsetSeconds = time, depthMm = depthMm, tankPressuresMbar = tankPressures)

fun eventAt(time: Int, type: EventType, value: Long? = null): Event =
    Event(timeOffsetSeconds = time, type = type, value = value)
