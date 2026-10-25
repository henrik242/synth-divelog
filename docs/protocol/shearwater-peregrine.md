# Shearwater Peregrine

The Peregrine speaks **Bluetooth LE only**. Above the radio it is a Petrel-family
computer: the same SLIP framing, packet header and upload commands as
[transport.md](transport.md), and the Petrel 1's manifest and compressed per-dive
reads ([shearwater-petrel1.md](shearwater-petrel1.md)).

Status (2026-10-24): **verified end to end** on Android: a real Peregrine downloaded six
open-circuit dives, reported its serial, parsed correctly (checked against the dives
after the sample-field fixes below) and showed no Bluetooth error after the shutdown.
The details follow libdivecomputer (`shearwater_common.c`, `shearwater_petrel.c`,
`shearwater_predator_parser.c`) and Subsurface's BLE handling.
iOS and the macOS desktop share one CoreBluetooth link, which has not met a device yet.

A capture of that download (firmware "V89 Classic") is replayed by
`PeregrineCaptureRegressionTest` (`peregrine-capture.transcript.txt`, trimmed to the
session setup and the newest dive). It confirms the record log format (base
`0x80000000`), that every request goes out as one packet (18 bytes at most), that
notifications run up to 77 bytes (so the agreed MTU was 80 or more), and the shutdown.
A capture does not show the GATT layer, so the service UUID and whether the device
bonds remain unconfirmed; the download worked, so the UUID or the fallback matched.

## Bluetooth LE link

The parts every platform shares are in `core:transport` commonMain: which scan results
count (`ShearwaterBle.isKnown`: a name holding a Shearwater model word, or the Shearwater
service advertised without a name; see [shearwater-family.md](shearwater-family.md)), the service order for finding the serial characteristic
(`ShearwaterBle.serialServices`), and `PacketQueue`, which hands notifications to the
blocking reads and keeps their boundaries. Each platform's scanner is a `BleScanner`,
which the download picker's ports come from (`blePorts`, `core:logbook`). An unfiltered
scan keeps running while the picker has the Peregrine selected, and the list updates by
itself. The computer is known
by the serial it reports, not its address, since CoreBluetooth gives each device a UUID of
its own instead of the Bluetooth address.

### Android

- **Finding it** (`BleDiveComputers`): bonded Peregrines are listed too. Needs `BLUETOOTH_SCAN` (declared
  `neverForLocation`); the picker says when Bluetooth is off or the permission is
  missing. Opening any port stops the scan.
- **Service** `fe25c237-0ece-443c-b0aa-e02033e7029d` (from Subsurface). The transport
  (`BleGattTransport`) uses its characteristic that both notifies and takes writes,
  falling back to the first non-SIG service that has one.
- **Connect:** retried once on status 133. MTU 247 is requested; if refused, the default
  23 (20 bytes per write) is used. Notifications are enabled through the CCCD (0x2902);
  if the device needs bonding, Android starts it there, and each setup step allows 30 s.
  Writes use write-without-response when the characteristic supports it, and the
  connection asks for high priority.
- One `write` is one GATT write and one `read` returns one notification; packet
  boundaries matter to the layer above.

### iOS and macOS (unverified on a device)

`CoreBluetoothTransport` and `CoreBluetoothScanner` (`appleMain`) do the same over
CoreBluetooth. The scanner runs on the main queue and the transport's callbacks on a queue
of their own. `open` waits for Bluetooth to be on, takes the peripheral from the system
cache by its UUID (else scans up to 20 s for it), connects, discovers the services and the
serial characteristic, and turns on notifications. The system pairs on the way if the
device asks. The MTU is CoreBluetooth's to agree; `maxWriteSize` is
`maximumWriteValueLength`. Writes go without response when the characteristic allows it,
waiting while CoreBluetooth's buffer is full, else with a response. `close` gives the last
writes (the shutdown) 300 ms to go out, since a write without response has no
acknowledgement, then waits up to 2 s for the disconnect. Before that, the macOS helper
exited at once, and the Peregrine stayed on "sent dive" with a countdown after a download.

- **iOS** (`IosSerialPorts`) offers the Peregrine only. `NSBluetoothAlwaysUsageDescription`
  is in `Info.plist`, and the first scan asks for permission. Auto-lock is off while a
  download runs, since a suspended app stalls the link; there is no background mode.
- **Both:** CoreBluetooth is only called on the transport's own queue. A Bluetooth reset
  ends the link, and the transport shows no power alert of its own (the scanner's does).
- **macOS:** a JVM cannot reach CoreBluetooth, so the desktop app bundles `ble-bridge`,
  `core:transport`'s macOS executable (`BleBridge.kt`), and drives it over stdin/stdout:
  `ble-bridge scan` prints the Peregrines it finds (`MacBleScanner`), and
  `ble-bridge connect <uuid>` relays the link as frames of a BE16 length and the bytes,
  one per write or notification, so packet boundaries survive the pipe (`MacBleTransport`).
  The lines and frames are `BridgeProtocol`'s, and `MacBleTransportTest` runs the JVM end
  against shell scripts. Apple Silicon only (`macosArm64`). `core:transport` publishes the
  linked helper as `bleBridgeDebug` (for `gradlew run`, which links faster) and
  `bleBridgeRelease` (bundled in the app).
  `./gradlew :app:desktop:shearwaterCapture` tests it without the app.

## Packet layer

`ShearwaterBlePackets` sits between `ShearwaterLink`'s SLIP stream and the GATT link.
Out, the SLIP-encoded frame is cut into packets of at most 32 bytes (as libdivecomputer
does), or the link's write size if smaller, each led by `[count] [index]` from 0. Every
request fits one 32-byte packet; at the default MTU an init with escaped address bytes
takes two, which is unverified on the device. In, each notification's two-byte lead is
dropped and the SLIP bytes run on across notifications.

## Session

1. **Identifiers** (read data by identifier, `22 [id: BE16]` -> `62 [id: BE16] <data>`):
   - `8010` serial: eight ASCII hex digits (NUL or space padding dropped), stored upper
     case, the form the Shearwater Cloud import uses.
   - `8011` firmware: ASCII, e.g. `V92`.
   - `8060` model: one byte, libdivecomputer's model number (`ID_MODEL` in
     `shearwater_common.h`): 3 Petrel 2, 9 Peregrine, 10 Petrel 3, 13 Peregrine TX, and the
     other Shearwaters it knows ([shearwater-family.md](shearwater-family.md)). It names the
     computer reported (a Peregrine TX as such); an unknown number stops the download. The
     Peregrine capture predates this read; its exchange (and the `P` write size) was added
     by hand.
   - `8021` log upload: 9 bytes, BE32 dive base address at `+1`. It picks the format:

     | Base | Format | Read from | Format id |
     |---|---|---|---|
     | `80000000` | Typed 32-byte records (PNF) | `0x80000000` | `shearwater-pnf` |
     | `C0000000` | Predator-like blocks, 32-byte samples | `0xC0000000` | `shearwater-petrel-log` |
     | `90000000` | Early records without a final record | `0xC0000000` (as blocks) | `shearwater-petrel-log` |
     | `DD000000` | Predator | `0xC0000000` (as blocks) | `shearwater-petrel-log` |

     Any other base stops the download.
2. **Manifest** at `0xE0000000`, then each new dive compressed from base + manifest
   address, oldest first (`ShearwaterLogbook`, shared with the Petrel 1). Pages are read
   until a terminator record or the known dive; a page of deleted records alone does
   not end it.
3. **Shutdown:** `2E 90 20 00` (write data by identifier `9020`), sent however the
   session ends. The device sends no reply. Without it the device reports a Bluetooth
   disconnect error; with it neither the Peregrine nor a Petrel 1 (which sends it too)
   showed one.

Each reply's length byte is checked against the frame (as libdivecomputer does), the
only guard against a lost or merged notification. The hardware identifier (`8050`) is not
read.

## Parsing

`PredatorParser` reads both formats. For the record format it takes the **sample
interval** from opening record 5 (`0x15`, BE16 milliseconds at `+23`) when opening
record 4 (`0x14`) gives log version 9 or later at `+16`; otherwise, or for a part-second
interval, the 10 s default applies. This also applies to Shearwater Cloud imports.

Sample fields, corrected against the first Peregrine download and libdivecomputer; they
apply to the Predator and Petrel too:

- **NDL / stop:** byte `+9` is the stop time in minutes when the stop depth (BE16 at
  `+2`, whole metres or feet) is set, else the NDL. The word at `+4` is time to surface,
  not the stop time (it read as a 1:00 stop on every no-deco sample). What does not
  apply is 0, not null (no stop: depth and time 0; in deco: NDL 0), since the file
  formats leave a null out and carry the previous value over it.
- **ppO2:** only on a rebreather sample (status byte `+11` without the open-circuit
  bit `0x10`): byte `+6`, in hundredths of a bar, the ppO2 the computer used. On
  internal sensors it is their average. With the external-ppO2 bit (`0x02`), as on a
  Petrel 1 run at a fixed setpoint with no sensors calibrated (calibration mask 0), it
  is the setpoint (0.70 or 1.30 in the capture); libdivecomputer reports nothing there.
  On open circuit `+6` holds the gas fraction times ambient pressure, which a logbook
  works out itself, so it is left out. The raw sensor bytes (`+12`, `+14`, `+15`) need
  calibration; read as ppO2 they gave a constant ~0.54 bar on open circuit.

Further fields, from libdivecomputer and unit-tested only (no real log with them yet).
Offsets are from the sample fields, which sit one byte after a record's type byte:

- **CNS:** byte `+22` in percent, on 32-byte samples (Petrel 1 and records).
- **Air integration** (Peregrine TX and others), from log version 7: transmitters 1 and
  2 at `+27` and `+19` (or the HP CCR pair when opening record 4 `+28` is 4), BE16 in 2
  psi steps under a battery nibble; 0xFFF0 and up are off or no-signal codes, and 0 is
  an unused slot. Extended records (`0xE1`) add transmitters 3 and 4 from version 13
  and the HP CCR pair from 14. Tanks are numbered from 0 in transmitter order.
- **Temperature:** a negative byte is corrected as libdivecomputer does (+102, capped
  at 0).
- **Freedive** records (`0x02`) pack four 8-byte samples: absolute pressure in mbar at
  `+1`, made a depth with the surface pressure (opening record 1 `+16`) and water
  density (opening record 3 `+3`), and temperature in tenths of a degree at `+3`.
- **Layout:** a Petrel-family log is read as blocks when it starts with `0xFFFF`, else
  as records, whatever its format id says.
- **Time to surface:** the BE16 at `+4`, in minutes.
- **Deco model:** byte 0 GF, 1 VPM-B, 2 VPM-B/GFS, 3 DCIEM, with the VPM conservatism
  after it (block `+67`, opening record 2 `+18`); gradient factors low and high at block
  `+4` or the opening record (`0x10`) `+4`.
- **Dive mode:** from log version 8 a header byte (block `+112`, opening record 4 `+1`):
  0 and 5 closed circuit, 1 and 6 open circuit, 2 and 3 gauge, 4 semi-closed, 7
  freedive. Older logs take it from the samples' status flags (no open-circuit bit is a
  rebreather, the `0x08` bit semi-closed), and freedive records mean freedive.

How these reach Subsurface XML and git exports is in `SubsurfaceShared` (`core/formats`).

## Not yet done

- A download on iOS or macOS on a real Peregrine.
- Desktop BLE on Windows and Linux.
- The UTC offset in opening record 5 (`+26` minutes, `+30` DST hours): applying it would
  make Peregrine dives real UTC while other computers' stay wall clock read as UTC, so
  a dive two computers logged would no longer line up.
