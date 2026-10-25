# Shearwater since the Petrel 2

Status (2026-10-25): the **Peregrine** is verified on Android
([shearwater-peregrine.md](shearwater-peregrine.md)); every other model here is **untested
on a device**. libdivecomputer reads them all with one protocol (`shearwater_petrel.c`), so
here they are one computer type, `DiveComputerKind.SHEARWATER` ("Shearwater Perdix /
Teric / Peregrine / Petrel 2+"), and the download is the Peregrine's: identifiers `8010`,
`8011`, `8060` and `8021`, the manifest and compressed per-dive reads, the shutdown
(`ShearwaterPetrelFamilyProtocol`).

| Model | Number (`8060`) | Links (libdivecomputer) |
|---|---|---|
| Petrel 2 | 3 | Bluetooth Classic, LE |
| NERD | 4 | Bluetooth Classic |
| Perdix | 5 | Bluetooth Classic, LE |
| Perdix AI | 6 | LE |
| NERD 2 | 7 | LE |
| Teric | 8 | LE |
| Peregrine | 9 | LE |
| Petrel 3 | 10 | LE |
| Perdix 2 | 11 | LE |
| Tern, Tern TX | 12 | LE |
| Peregrine TX | 13 | LE |
| Perdix 3 | 14 | LE |

- **Model:** the device's model number names it (a Teric as "Teric"); a number not in the
  table stops the download before any dive is read. The Petrel 1 is 3 too, but has its own
  type and protocol ([shearwater-petrel1.md](shearwater-petrel1.md)).
- **Link:** over Bluetooth LE the SLIP frames go through `ShearwaterBlePackets`; over
  Bluetooth Classic they are the plain byte stream the Petrel 1 uses
  ([transport.md](transport.md)). The protocol tells them apart by the open link: only a
  packet link knows its write size (`Transport.maxWriteSize`). A recorded LE download keeps
  that size in its transcript (`P <size>`), so it replays as a packet link.
- **Ports:** every platform with Bluetooth Classic (Android, macOS through `rfcomm-bridge`,
  paired serial ports elsewhere) lists paired devices; every platform with Bluetooth LE
  (Android, macOS through `ble-bridge`, iOS) finds the computer in a scan, and the picker
  scans only there. A scanned port is offered when its advertised name holds one of the
  words in `DiveComputerKind.bleNames` (or it has none), and a port named like one is
  preselected over other paired devices.
- **Names:** libdivecomputer's Bluetooth name list (`dc_filter_shearwater` in
  `descriptor.c`, matched exactly) is "Petrel", "Petrel 3", "NERD", "NERD 2", "Perdix",
  "Perdix 2", "Perdix 3", "Teric", "Peregrine", "Peregrine TX", "Tern" and "Predator"
  (Classic only, its own type). A Petrel 2 advertises "Petrel" over LE. Here a name matches
  when a whole word of it is "Petrel", "Perdix", "NERD", "Teric", "Peregrine" or "Tern", so a
  name with a suffix still does and "Lantern" does not.
- **Identity:** the serial it reports, so the same computer is one device whichever link
  or platform downloaded it (`shearwater:<serial>`).
- **Log format:** from `8021`, as on the Peregrine; both the block and the record layout
  parse with `PredatorParser`, including the air-integration pressures (Perdix AI, Teric,
  Perdix 2 and 3, Tern TX) and freedive logs (Teric, Tern).

Unconfirmed until a device is tried:

- Whether each model offers Shearwater's GATT service (`fe25c237-...`). A failed open lists
  the services the device has.
- Whether early Petrel 2 and Perdix units have Bluetooth LE at all; one that is Classic
  only never shows up on iOS.
- Whether a computer paired over Classic is also reachable over LE on Android (it may be
  listed over both links, and LE may ask to pair again).
- The NERD: a head-up display paired with a Petrel, which may keep its logs differently.

Test on a device: `./gradlew :app:desktop:shearwaterCapture` (Bluetooth LE) or
`--args="CLASSIC"` (a paired Petrel 2, Perdix or NERD), or a download in the app with
capture recording on Android (see [README.md](README.md)), then a replay test like
`PeregrineCaptureRegressionTest`.
