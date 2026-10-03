# Dive computer protocols

Reference notes for the download protocols Synth Divelog speaks, written against
real hardware. Each fact here has either been observed on a device we own or
verified by matching parsed output to the device's own screen; anything still
unconfirmed is marked as such.

These notes describe wire formats as facts. They are not copied from any other
implementation; where a value could not be observed directly it is called out as
a guess to be confirmed against the next capture.

## Layers

| Doc | Scope |
|---|---|
| [transport.md](transport.md) | Bluetooth/serial link, SLIP framing, packet header and the shared upload command set. Common to every Shearwater device. |
| [shearwater-predator.md](shearwater-predator.md) | Predator download: one uncompressed memory dump, ring-buffer extraction, dive-log field offsets. |
| [shearwater-petrel1.md](shearwater-petrel1.md) | Petrel 1 download: dive manifest, per-dive compressed reads, the compression scheme. Reuses the Predator log format. |

## How to read a field offset

Offsets are byte positions from the start of the named block. `BE16` / `BE32`
mean big-endian 16- or 32-bit unsigned integers. A `@+N` suffix is an offset into
the current block.

## Capturing a session

Every download records a transcript (`RecordingTransport`) saved under the app's
`files/captures/`. Pull one with:

```sh
adb exec-out run-as no.synth.divelog cat files/captures/<name>.transcript.txt
```

Transcripts are the ground truth for revising these notes. They can be replayed
offline against the parser with `ReplayTransport`.
