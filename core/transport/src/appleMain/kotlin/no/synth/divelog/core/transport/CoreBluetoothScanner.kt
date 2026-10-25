package no.synth.divelog.core.transport

import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSNumber
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time
import kotlin.concurrent.Volatile

/**
 * The CoreBluetooth [BleScanner], for iOS and the macOS ble-bridge; a device's id is its
 * CoreBluetooth UUID. The scan runs on the main queue and the calls may come from any
 * thread. Bluetooth is first touched by [scan], which on iOS asks for permission.
 */
class CoreBluetoothScanner : BleScanner {
    private val delegate = Delegate()
    private var central: CBCentralManager? = null // main queue only, like the rest below
    private val seen = LinkedHashMap<String, ScannedDevice>()
    private var wanted = false
    private var generation = 0

    @Volatile private var snapshot: List<ScannedDevice> = emptyList()
    @Volatile private var problem: String? = null

    override fun found(): List<ScannedDevice> = snapshot

    /** Whether a scan is wanted (running, or waiting for Bluetooth); main queue only. */
    val scanning: Boolean get() = wanted

    override fun problem(): String? = problem

    /** Starts once Bluetooth is on, if it is not yet. */
    override fun scan() = onMain {
        if (wanted) return@onMain
        wanted = true
        val run = ++generation
        val c = central ?: CBCentralManager(delegate, dispatch_get_main_queue()).also { central = it }
        start(c)
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, ShearwaterBle.SCAN_MS * 1_000_000), dispatch_get_main_queue()) {
            if (run == generation) stopScan()
        }
    }

    override fun stop() = onMain { stopScan() }

    private fun stopScan() {
        generation++
        wanted = false
        central?.takeIf { it.state == CBManagerStatePoweredOn }?.stopScan()
    }

    private fun start(c: CBCentralManager) {
        // Unfiltered: a filter on the service would miss a computer that only advertises its name.
        if (wanted && c.state == CBManagerStatePoweredOn) c.scanForPeripheralsWithServices(null, null)
    }

    private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue(), block)

    private inner class Delegate : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            problem = bluetoothProblem(central.state)
            start(central)
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val name = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?: didDiscoverPeripheral.name
            val services = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<*>
            val advertisesService = services.orEmpty().any {
                (it as? CBUUID)?.UUIDString.equals(ShearwaterBle.SERVICE_UUID, ignoreCase = true)
            }
            if (!ShearwaterBle.isPeregrine(name, advertisesService)) return
            val device = ScannedDevice(didDiscoverPeripheral.identifier.UUIDString, name)
            if (seen[device.id] == device) return
            seen[device.id] = device
            snapshot = byName(seen.values)
        }
    }
}
