// Bridges a Bluetooth Classic serial-port (SPP) channel to stdin/stdout, for the desktop
// app on macOS, where opening a paired device's /dev/cu.* node no longer brings the link up.
//
//   rfcomm-bridge list               paired devices with a serial-port service: "<address>\t<name>"
//   rfcomm-bridge connect <address>  open the device's SPP channel and relay bytes:
//                                    stdin -> device, device -> stdout
//
// Status goes to stderr as one line: "CONNECTED <channel>" once the channel is open, or
// "ERROR <message>" before exiting non-zero. The bridge exits when stdin closes or the
// device drops the channel.

import Foundation
import IOBluetooth

/** The standard Serial Port Profile service class. */
let serialPortUUID: IOBluetoothSDPUUID = IOBluetoothSDPUUID(uuid16: 0x1101)!

func status(_ line: String) {
    FileHandle.standardError.write((line + "\n").data(using: .utf8) ?? Data())
}

func fail(_ message: String) -> Never {
    status("ERROR " + message)
    exit(1)
}

/** Colon-separated upper-case form, the way the rest of the app writes addresses. */
func normalized(_ address: String) -> String {
    address.replacingOccurrences(of: "-", with: ":").uppercased()
}

func serialChannel(_ device: IOBluetoothDevice) -> BluetoothRFCOMMChannelID? {
    var channel: BluetoothRFCOMMChannelID = 0
    if let record = device.getServiceRecord(for: serialPortUUID),
       record.getRFCOMMChannelID(&channel) == kIOReturnSuccess {
        return channel
    }
    return nil
}

func list() {
    let devices = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? []
    for device in devices where serialChannel(device) != nil {
        print("\(normalized(device.addressString ?? ""))\t\(device.name ?? "")")
    }
}

final class Relay: NSObject, IOBluetoothRFCOMMChannelDelegate {
    func rfcommChannelData(_ channel: IOBluetoothRFCOMMChannel!, data dataPointer: UnsafeMutableRawPointer!, length dataLength: Int) {
        FileHandle.standardOutput.write(Data(bytes: dataPointer, count: dataLength))
    }

    func rfcommChannelClosed(_ channel: IOBluetoothRFCOMMChannel!) {
        status("CLOSED")
        exit(0)
    }
}

func connect(_ address: String) -> Never {
    guard let device = IOBluetoothDevice(addressString: address.replacingOccurrences(of: ":", with: "-")) else {
        fail("unknown device \(address)")
    }
    if device.openConnection() != kIOReturnSuccess {
        fail("could not reach \(address); is it waiting for a connection?")
    }
    var channelId = serialChannel(device)
    if channelId == nil {
        // No cached service record: ask the device, waiting on the run loop for the answer.
        device.performSDPQuery(nil, uuids: [serialPortUUID])
        let deadline = Date().addingTimeInterval(10)
        while channelId == nil && Date() < deadline {
            RunLoop.main.run(until: Date().addingTimeInterval(0.2))
            channelId = serialChannel(device)
        }
    }
    guard let channelId else { fail("\(address) has no serial-port service") }

    let relay = Relay()
    var channel: IOBluetoothRFCOMMChannel?
    let result = device.openRFCOMMChannelSync(&channel, withChannelID: channelId, delegate: relay)
    guard result == kIOReturnSuccess, let channel else {
        device.closeConnection()
        fail("could not open serial channel \(channelId) (0x\(String(UInt32(bitPattern: result), radix: 16)))")
    }
    status("CONNECTED \(channelId)")

    // stdin is read on a background thread; IOBluetooth is used from the main run loop only.
    Thread.detachNewThread {
        while true {
            let data = FileHandle.standardInput.availableData
            DispatchQueue.main.async {
                if data.isEmpty {
                    channel.close()
                    device.closeConnection()
                    exit(0)
                }
                var bytes = [UInt8](data)
                let mtu = max(1, Int(channel.getMTU()))
                var offset = 0
                while offset < bytes.count {
                    let n = min(mtu, bytes.count - offset)
                    let r = bytes.withUnsafeMutableBytes { buffer in
                        channel.writeSync(buffer.baseAddress?.advanced(by: offset), length: UInt16(n))
                    }
                    if r != kIOReturnSuccess { fail("write failed (0x\(String(UInt32(bitPattern: r), radix: 16)))") }
                    offset += n
                }
            }
            if data.isEmpty { return }
        }
    }
    RunLoop.main.run()
    exit(0)
}

let args = CommandLine.arguments
switch (args.count > 1 ? args[1] : "") {
case "list":
    list()
case "connect" where args.count > 2:
    connect(args[2])
default:
    status("usage: rfcomm-bridge list | rfcomm-bridge connect <address>")
    exit(2)
}
