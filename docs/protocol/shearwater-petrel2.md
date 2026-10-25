# Shearwater Petrel 2 and Petrel 3

Status (2026-10-25): **untested on a device**. libdivecomputer lists the Petrel 2 with
Bluetooth Classic (SPP) and Bluetooth LE, and reads it the way it reads the Peregrine
(`shearwater_petrel.c`), so the download is the one in
[shearwater-peregrine.md](shearwater-peregrine.md): identifiers `8010`, `8011` and `8021`,
the manifest and compressed per-dive reads, the shutdown. `ShearwaterPetrelFamilyProtocol.petrel2`
does it; a replay of the Peregrine capture through `DiveComputerKind.SHEARWATER_PETREL_2`
checks the LE path (`PeregrineCaptureRegressionTest`).

- **Link:** over Bluetooth LE the SLIP frames go through `ShearwaterBlePackets`, as on the
  Peregrine; over Bluetooth Classic they are the plain byte stream the Petrel 1 uses
  ([transport.md](transport.md)). The protocol tells them apart by the open link: only a
  packet link knows its write size (`Transport.maxWriteSize`). A recorded LE download keeps
  that size in its transcript (`P <size>`), so it replays as a packet link.
- **Ports:** every platform with Bluetooth Classic (Android, macOS through `rfcomm-bridge`,
  paired serial ports elsewhere) lists it as a paired device; every platform with Bluetooth
  LE (Android, macOS through `ble-bridge`, iOS) finds it in a scan, and the picker scans
  only there. A scan lists every Shearwater it knows (`ShearwaterBle.isKnown`), and the
  picker offers a scanned port to the Petrel 2 when its advertised name contains "Petrel"
  (or it has none), preselecting a port named like it over other paired devices.
- **Identity:** the serial it reports, so the same computer is one device whichever link
  or platform downloaded it (`shearwater:petrel2:<serial>`).
- **Log format:** from `8021`, as on the Peregrine; both the block and the record layout
  parse with `PredatorParser`.

Unconfirmed until a device is tried:

- The name it advertises over LE, and whether it offers Shearwater's GATT service
  (`fe25c237-...`). A failed open lists the services the device has.
- Whether early units have Bluetooth LE at all; one that is Classic only never shows up on
  iOS.
- Whether a Petrel 2 paired over Classic is also reachable over LE on Android (it may be
  listed over both links, and LE may ask to pair again).
- Whether names really tell the two apart; a model named only "Petrel" goes to the Petrel 2.
  Reading the hardware identifier (`8050`) to name the model is left until a capture shows
  its values.

## Petrel 3

libdivecomputer lists the Petrel 3 with Bluetooth LE only and reads it like the Peregrine,
so it is the Peregrine's setup with another name: `ShearwaterPetrelFamilyProtocol.petrel3`,
always over `ShearwaterBlePackets`, keyed by its serial (`shearwater:petrel3:<serial>`), and
offered wherever there is Bluetooth LE (Android, macOS through `ble-bridge`, iOS). A scanned
port goes to the Petrel 3 when its advertised name contains "Petrel 3": the most specific
model name wins (`DiveComputerKind.forAdvertisedName`), so "Petrel" alone stays the Petrel 2's.
The advertised name is unconfirmed. `PeregrineCaptureRegressionTest` replays the Peregrine
capture through its kind. Test on a device: `shearwaterCapture --args="PETREL3"`.

Test on a device: `./gradlew :app:desktop:shearwaterCapture --args="PETREL2"` (Bluetooth
Classic) or `--args="PETREL2 LE"`, or a download in the app with capture recording on
Android (see [README.md](README.md)), then a replay test like `PeregrineCaptureRegressionTest`.
