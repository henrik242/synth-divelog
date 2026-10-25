package no.synth.divelog.core.transport

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionShowPowerAlertKey
import platform.CoreBluetooth.CBManagerState
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.Foundation.NSData
import platform.Foundation.create
import platform.darwin.dispatch_queue_t
import platform.posix.memcpy

/** Why Bluetooth LE cannot be used in [state], worded for the user; null when it can (or may yet). */
internal fun bluetoothProblem(state: CBManagerState): String? = when (state) {
    CBManagerStatePoweredOff -> "Turn on Bluetooth"
    CBManagerStateUnauthorized -> "Allow Bluetooth for Synth Divelog in Settings to find the computer"
    CBManagerStateUnsupported -> "This device has no Bluetooth LE"
    else -> null
}

/** A central on [queue] without the system "Turn on Bluetooth" alert: the picker says so itself ([bluetoothProblem]). */
internal fun centralManager(delegate: CBCentralManagerDelegateProtocol, queue: dispatch_queue_t): CBCentralManager =
    CBCentralManager(delegate, queue, mapOf<Any?, Any>(CBCentralManagerOptionShowPowerAlertKey to false))

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
