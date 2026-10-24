# Shearwater Peregrine

The Peregrine speaks **Bluetooth LE only**. Above the radio it is a Petrel-family
computer: the same SLIP framing, packet header and upload commands as
[transport.md](transport.md), and the Petrel 1's manifest and compressed per-dive
reads ([shearwater-petrel1.md](shearwater-petrel1.md)).

Status (2026-10-24): **verified end to end** on Android: a real Peregrine downloaded six
open-circuit dives, reported its serial, parsed correctly (checked against the dives
after the sample-field fixes below) and showed no Bluetooth error after the shutdown.
The details follow libdivecomputer (`shearwater_common.c`, `shearwater_petrel.c`,
`shearwater_predator_parser.c`) and Subsurface's BLE handling. Not yet confirmed from a
capture: the service UUID, the agreed MTU, whether it bonds, and which log format it
reports. Save a capture (`files/captures/`) as a replay test to pin them down.

## Bluetooth LE link (Android)

- **Finding it** (`BleDiveComputers`): an unfiltered scan keeps running while the picker
  has the Peregrine selected, and the list updates by itself. A device counts if its
  name contains "Peregrine", or it advertises the Shearwater service without a name.
  Bonded Peregrines are listed too. Needs `BLUETOOTH_SCAN` (declared
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
   disconnect error; with it the Peregrine showed none. The Petrel 1 now sends it too
   (unverified there).

Each reply's length byte is checked against the frame (as libdivecomputer does), the
only guard against a lost or merged notification. The model reported is always
"Peregrine"; the hardware identifier (`8050`) is not read.

## Parsing

`PredatorParser` reads both formats. For the record format it takes the **sample
interval** from opening record 5 (`0x15`, BE16 milliseconds at `+23`) when opening
record 4 (`0x14`) gives log version 9 or later at `+16`; otherwise, or for a part-second
interval, the 10 s default applies. This also applies to Shearwater Cloud imports.

Sample fields, corrected against the first Peregrine download and libdivecomputer; they
apply to the Predator and Petrel too:

- **NDL / stop:** byte `+9` is the stop time in minutes when the stop depth (BE16 at
  `+2`, whole metres or feet) is set, else the NDL. The word at `+4` is time to surface,
  not the stop time (it read as a 1:00 stop on every no-deco sample).
- **ppO2:** only on a rebreather sample (status byte `+11` without the open-circuit
  bit `0x10`) whose ppO2 is not external (`0x02`): the sensor average at `+6`, in
  hundredths of a bar. The raw sensor bytes (`+12`, `+14`, `+15`) need calibration;
  read as ppO2 they gave a constant ~0.54 bar on open circuit.

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

## Not yet done

- Desktop (macOS CoreBluetooth helper) and iOS transports.
- A replay test from a real capture (`ReplayTransport` takes the capture's
  `maxWriteSize`, so a default-MTU capture replays with the same packets).
- GF, deco model, dive mode and time to surface: they need fields in the model, the
  database and the file formats first.
- The UTC offset in opening record 5 (`+26` minutes, `+30` DST hours): applying it would
  make Peregrine dives real UTC while other computers' stay wall clock read as UTC, so
  a dive two computers logged would no longer line up.
