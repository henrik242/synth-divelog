package no.synth.divelog.core.db

import no.synth.divelog.core.model.Buddy
import no.synth.divelog.core.model.Country
import no.synth.divelog.core.model.DecoAlgorithm
import no.synth.divelog.core.model.DecoModel
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.Dive
import no.synth.divelog.core.model.DiveComputerRecord
import no.synth.divelog.core.model.DiveMode
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.GearItem
import no.synth.divelog.core.model.GearKind
import no.synth.divelog.core.model.GearService
import no.synth.divelog.core.model.Place
import no.synth.divelog.core.model.Sample
import no.synth.divelog.core.model.Site
import no.synth.divelog.core.model.Tag
import no.synth.divelog.core.model.Tank
import no.synth.divelog.core.db.sql.Buddy as BuddyRow
import no.synth.divelog.core.db.sql.Country as CountryRow
import no.synth.divelog.core.db.sql.Device as DeviceRow
import no.synth.divelog.core.db.sql.Dive as DiveRow
import no.synth.divelog.core.db.sql.Event as EventRow
import no.synth.divelog.core.db.sql.FindByDeviceAndFingerprint
import no.synth.divelog.core.db.sql.GasMix as GasMixRow
import no.synth.divelog.core.db.sql.Gear as GearRow
import no.synth.divelog.core.db.sql.GearService as GearServiceRow
import no.synth.divelog.core.db.sql.Place as PlaceRow
import no.synth.divelog.core.db.sql.Sample as SampleRow
import no.synth.divelog.core.db.sql.SelectAllRecordSummaries
import no.synth.divelog.core.db.sql.SelectRecordFromDeviceAmongDives
import no.synth.divelog.core.db.sql.SelectRecordSummariesForDive
import no.synth.divelog.core.db.sql.SelectRecordSummaryById
import no.synth.divelog.core.db.sql.Site as SiteRow
import no.synth.divelog.core.db.sql.Tag as TagRow
import no.synth.divelog.core.db.sql.Tank as TankRow

internal fun CountryRow.toDomain() = Country(id = id, name = name)

internal fun PlaceRow.toDomain() = Place(id = id, countryId = countryId, name = name)

internal fun SiteRow.toDomain() = Site(
    id = id,
    placeId = placeId,
    name = name,
    latitude = latitude,
    longitude = longitude,
    notes = notes,
)

internal fun BuddyRow.toDomain() = Buddy(id = id, name = name)

internal fun TagRow.toDomain() = Tag(id = id, name = name)

internal fun GearRow.toDomain() = GearItem(
    id = id,
    name = name,
    kind = GearKind.fromStored(kind),
    brand = brand,
    model = model,
    serialNumber = serialNumber,
    purchasedEpochDay = purchasedEpochDay?.toInt(),
    purchasePriceCents = purchasePriceCents,
    shop = shop,
    notes = notes,
    retired = retired != 0L,
    deviceId = deviceId,
)

internal fun GearServiceRow.toDomain() = GearService(id = id, gearId = gearId, epochDay = epochDay.toInt(), notes = notes)

internal fun DeviceRow.toDomain() = Device(
    id = id,
    vendor = vendor,
    model = model,
    serial = serial,
    firmware = firmware,
    nickname = nickname,
    bluetoothAddress = bluetoothAddress,
)

internal fun GasMixRow.toDomain() = GasMix(
    id = id,
    o2Permille = o2Permille.toInt(),
    hePermille = hePermille.toInt(),
)

internal fun TankRow.toDomain() = Tank(
    id = id,
    diveId = diveId,
    index = tankIndex.toInt(),
    volumeMl = volumeMl?.toInt(),
    workingPressureMbar = workingPressureMbar?.toInt(),
    startPressureMbar = startPressureMbar?.toInt(),
    endPressureMbar = endPressureMbar?.toInt(),
    gasMixId = gasMixId,
)

internal fun DiveRow.toDomain() = Dive(
    id = id,
    number = number?.toInt(),
    startEpochSeconds = startEpochSeconds,
    utcOffsetSeconds = utcOffsetSeconds.toInt(),
    durationSeconds = durationSeconds.toInt(),
    maxDepthMm = maxDepthMm?.toInt(),
    meanDepthMm = meanDepthMm?.toInt(),
    waterTempMk = waterTempMk?.toInt(),
    airTempMk = airTempMk?.toInt(),
    notes = notes,
    rating = rating?.toInt(),
    visibility = visibility?.toInt(),
    siteId = siteId,
    primaryComputerRecordId = primaryComputerRecordId,
    visibilityRating = visibilityRating?.toInt(),
)

private fun record(
    id: Long,
    diveId: Long,
    deviceId: Long?,
    startEpochSeconds: Long,
    durationSeconds: Long,
    maxDepthMm: Long?,
    rawFormatId: String,
    fingerprint: String,
    diveMode: String?,
    decoAlgorithm: String?,
    gfLowPercent: Long?,
    gfHighPercent: Long?,
    decoConservatism: Long?,
) = DiveComputerRecord(
    id = id,
    diveId = diveId,
    deviceId = deviceId,
    startEpochSeconds = startEpochSeconds,
    durationSeconds = durationSeconds.toInt(),
    maxDepthMm = maxDepthMm?.toInt(),
    rawFormatId = rawFormatId,
    fingerprint = fingerprint,
    diveMode = DiveMode.fromStored(diveMode),
    decoModel = DecoAlgorithm.fromStored(decoAlgorithm)?.let {
        DecoModel(it, gfLowPercent?.toInt(), gfHighPercent?.toInt(), decoConservatism?.toInt())
    },
)

internal fun SelectRecordSummaryById.toDomain() =
    record(
        id, diveId, deviceId, startEpochSeconds, durationSeconds, maxDepthMm, rawFormatId, fingerprint,
        diveMode, decoAlgorithm, gfLowPercent, gfHighPercent, decoConservatism,
    )

internal fun SelectRecordSummariesForDive.toDomain() =
    record(
        id, diveId, deviceId, startEpochSeconds, durationSeconds, maxDepthMm, rawFormatId, fingerprint,
        diveMode, decoAlgorithm, gfLowPercent, gfHighPercent, decoConservatism,
    )

internal fun SelectAllRecordSummaries.toDomain() =
    record(
        id, diveId, deviceId, startEpochSeconds, durationSeconds, maxDepthMm, rawFormatId, fingerprint,
        diveMode, decoAlgorithm, gfLowPercent, gfHighPercent, decoConservatism,
    )

internal fun SelectRecordFromDeviceAmongDives.toDomain() =
    record(
        id, diveId, deviceId, startEpochSeconds, durationSeconds, maxDepthMm, rawFormatId, fingerprint,
        diveMode, decoAlgorithm, gfLowPercent, gfHighPercent, decoConservatism,
    )

internal fun FindByDeviceAndFingerprint.toDomain() =
    record(
        id, diveId, deviceId, startEpochSeconds, durationSeconds, maxDepthMm, rawFormatId, fingerprint,
        diveMode, decoAlgorithm, gfLowPercent, gfHighPercent, decoConservatism,
    )

internal fun SampleRow.toDomain(tankPressures: Map<Int, Int>) = Sample(
    timeOffsetSeconds = timeOffsetSeconds.toInt(),
    depthMm = depthMm?.toInt(),
    temperatureMk = temperatureMk?.toInt(),
    ppO2Mbar = ppO2Mbar?.toInt(),
    ndlSeconds = ndlSeconds?.toInt(),
    ceilingMm = ceilingMm?.toInt(),
    stopDepthMm = stopDepthMm?.toInt(),
    stopTimeSeconds = stopTimeSeconds?.toInt(),
    ttsSeconds = ttsSeconds?.toInt(),
    cnsPermille = cnsPermille?.toInt(),
    activeGasIndex = activeGasIndex?.toInt(),
    tankPressuresMbar = tankPressures,
)

internal fun EventRow.toDomain() = Event(
    timeOffsetSeconds = timeOffsetSeconds.toInt(),
    type = EventType.fromStored(type),
    value = value_,
)
