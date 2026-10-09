# Dive computer protocols

Reference notes for the download protocols Synth Divelog speaks. Each fact was
observed on a device we own or checked by matching parsed output to the device's
own screen; anything unconfirmed is marked as such. libdivecomputer and Subsurface
served as references where a note says so.

| Doc | Scope | Hardware status |
|---|---|---|
| [transport.md](transport.md) | Shearwater Bluetooth link, SLIP framing, packet header, upload command set. | Verified |
| [shearwater-predator.md](shearwater-predator.md) | Predator: one memory dump, ring-buffer extraction, dive-log field offsets. | Verified |
| [shearwater-petrel1.md](shearwater-petrel1.md) | Petrel 1: dive manifest, compressed per-dive reads. Reuses the Predator log format. | Verified |
| [suunto-serial.md](suunto-serial.md) | Suunto USB cable, chipsets, the two protocol families, the serial transports. | Verified (HelO2) |
| [suunto-helo2.md](suunto-helo2.md) | HelO2 / Vyper2 family: framing, timing, incremental download. | Verified |
| [suunto-zoop.md](suunto-zoop.md) | Zoop / old-Vyper family. | **Unverified** |
| [suunto-testing.md](suunto-testing.md) | Testing a Suunto download on the real cable; first-Vyper checklist. | - |

## How to read a field offset

`+N` is a byte offset from the start of the named block. `BE16` / `BE32` are
big-endian 16- or 32-bit unsigned integers, `u8` / `i8` an unsigned / signed byte.

## Capturing a session

On Android every download records its wire exchange (`RecordingTransport`) to
`files/captures/`. Pull one from the debug build with:

```sh
adb exec-out run-as no.synth.divelog.debug cat files/captures/<name>.transcript.txt
```

On desktop, `./gradlew :app:desktop:suuntoCapture` saves a transcript of a Suunto
download (see [suunto-testing.md](suunto-testing.md)), and
`./gradlew :app:desktop:shearwaterCapture` runs a Shearwater download over Bluetooth
on macOS and prints the dives.

Transcripts are the ground truth for revising these notes. `ReplayTransport` replays
one offline against the protocol and parser, as in `PredatorCaptureRegressionTest`.
