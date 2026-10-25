# Shearwater Petrel 2

Status (2026-10-25): **untested on a device**. The Petrel 2 speaks both Bluetooth Classic
(SPP) and Bluetooth LE. Above the radio it is the Peregrine: libdivecomputer's
`shearwater_petrel.c` reads both the same way, so the download is the one in
[shearwater-peregrine.md](shearwater-peregrine.md) (identifiers `8010`, `8011` and `8021`,
the manifest and compressed per-dive reads, the shutdown), done by
`ShearwaterPetrelFamilyProtocol.petrel2`.

- **Link:** over Bluetooth LE the SLIP frames go through `ShearwaterBlePackets`, as on the
  Peregrine; over Bluetooth Classic they are the plain byte stream the Petrel 1 uses
  ([transport.md](transport.md)). The protocol tells them apart by the open link: only a
  packet link knows its write size (`Transport.maxWriteSize`).
- **Ports:** every platform with Bluetooth Classic (Android, macOS through `rfcomm-bridge`,
  paired serial ports elsewhere) lists it as a paired device; every platform with Bluetooth
  LE (Android, macOS through `ble-bridge`, iOS) finds it in a scan. A scan lists every
  Shearwater it knows (`ShearwaterBle.isKnown`), and the picker offers a scanned port to
  the Petrel 2 when its advertised name contains "Petrel" (or it has none). The advertised
  name is unconfirmed. A Petrel 2 paired in Android settings shows up over both links.
- **Identity:** the serial it reports, so the same computer is one device whichever link
  or platform downloaded it (`bt:shearwater-petrel2:<serial>`).
- **Log format:** from `8021`, as on the Peregrine; both the block and the record layout
  parse with `PredatorParser`.

Test on a device: `./gradlew :app:desktop:shearwaterCapture --args="PETREL2"` (Bluetooth
Classic) or `--args="PETREL2 LE"`, or a download in the app with capture recording on
Android (see [README.md](README.md)), then a replay test like `PeregrineCaptureRegressionTest`.
