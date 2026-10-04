# Suunto download: real-device test guide (M9)

How to validate the Suunto USB download against the real dongle. Everything here
is written from the protocol spec and covered by synthetic unit tests; **none of it
has touched hardware yet.** This guide is the checklist for the first real capture.

## What is verified vs what needs the dongle

Verified by unit tests (`./gradlew :core:divecomputer:jvmTest`):

- XOR checksum (`SuuntoCrc`).
- Old-Vyper paged read `0x05` framing, both directions - via a fake device and via
  `ReplayTransport` (`SuuntoVyperProtocolTest`, `SuuntoVyperTest`).
- Ring-buffer linearisation and split on the `0x80`/`0x82` markers, including the
  wrap (`SuuntoVyperTest`).
- Depth/temperature parsing from delta-feet samples against a hand-built memory
  image (`SuuntoVyperTest`).
- D9/HelO2 packet framing, `ReadMemory` (with paging) and `GetVersion`
  (`SuuntoD9LinkTest`).
- Family selection -> serial params + protocol + parser (`SuuntoFamilyTest`).

Needs the dongle (unverified):

- The whole transport/timing layer: baud, odd parity, the half-duplex RTS/DTR
  dance and echo discard. This is the classic flaky part.
- The Vyper header offsets (model `0x24`, write pointer `0x51`), the exact per-dive
  head layout, the sample interval, and the per-dive **date/time** (so
  `startEpochSeconds` is 0 for now and identity uses a content fingerprint).
- The D9 dive directory and profile format (not implemented).

## Hardware

- A **Suunto "old" USB interface cable** (rotating connector, red alignment dot).
  Chipset is usually FTDI (VID `0x0403` / PID `0x6001`); some are Prolific PL2303
  (VID `0x067B`). Both are handled by usb-serial-for-android's default prober.
- A **USB-OTG / USB-C host adapter** for the phone (the test Samsung S25 exposes a
  USB-C host port; reach it over adb per `memory/test-device.md`).
- A **Suunto Zoop** first (old Vyper family, 2400 8O1 half-duplex), then a
  **HelO2** (D9 family, 9600 8N1) once the D9 parser exists.
- Put the computer in PC/transfer mode if the model requires it, and seat it
  firmly in the cradle (these cables are contact-fussy).

## Android app wiring

The pieces are in place; the download screen needs to call them (the UI is owned by
the main session, so this is the integration recipe, not committed UI code):

1. Manifest: declare USB host.

   ```xml
   <uses-feature android:name="android.hardware.usb.host" />
   ```

2. Enumerate adapters:

   ```kotlin
   val candidates = UsbSerialDevices.available(context)   // UsbSerialCandidate list
   val cable = candidates.firstOrNull { it.looksLikeSuuntoCable } ?: candidates.first()
   ```

3. Get the USB permission (system dialog). Register a receiver for a private action,
   then:

   ```kotlin
   if (!UsbSerialDevices.hasPermission(context, cable)) {
       UsbSerialDevices.requestPermission(context, cable, "no.synth.divelog.USB_PERMISSION")
       // wait for the broadcast; check UsbManager.EXTRA_PERMISSION_GRANTED
   }
   ```

   To auto-grant on attach instead, add an `intent-filter` for
   `android.hardware.usb.action.USB_DEVICE_ATTACHED` with a `device_filter.xml`
   listing the VID/PID.

4. Pick the family, open the transport with its serial params, download, parse:

   ```kotlin
   val family = SuuntoFamily.VYPER   // Zoop; SuuntoFamily.D9 for HelO2 (parser pending)
   val transport = UsbSerialDevices.transportFor(context, cable, family.serialParams)
   val recording = RecordingTransport(transport) { System.currentTimeMillis() }
   recording.open()
   val protocol = family.protocol(recording)
   val raw = protocol.download(knownFingerprint = null)
   val parser = family.parser()!!   // null for D9 until implemented
   val dives = raw.map { parser.parse(it) }
   // Always save recording.transcript().toText() - it is the ground truth.
   recording.close()
   ```

   This mirrors the Bluetooth path (`DownloadViewModel`), which picks
   `ShearwaterPetrelProtocol` vs `ShearwaterPredatorProtocol`; here `SuuntoFamily`
   picks Vyper vs D9. Reuse `RecordingTransport` so a failed attempt still yields a
   transcript to debug with.

## Expected serial parameters

| Family | Baud | Frame | Duplex | Lines |
|---|---|---|---|---|
| Vyper / Zoop | 2400 | 8O1 | half | DTR high; RTS set to send, clear to receive; echo discarded |
| HelO2 / D9 | 9600 | 8N1 | full | DTR high; no RTS toggling |

Vyper timing (from the spec, tune against the capture): ~200 ms to let the UART
drain before clearing RTS, ~400 ms after clearing before reading, ~500 ms receive
timeout. These are `SuuntoVyperProtocol.SERIAL_PARAMS.txSettleMs` / `rxSettleMs`.

## Capturing and feeding back

Every download should run through `RecordingTransport`; save the transcript the way
the Bluetooth path does (under `files/captures/`). Pull it with:

```sh
adb exec-out run-as no.synth.divelog cat files/captures/<name>.transcript.txt
```

Replay it offline against the protocol with `ReplayTransport` (strictWrites = false),
exactly like `PredatorCaptureRegressionTest`, and turn it into a committed
regression fixture once the decoded dives match the device's own log screens
(dive number, max depth, average depth, duration, date/time).

## First-capture checklist

1. Does the cable enumerate, and is the VID/PID what we expect?
2. Does a single `0x05` read of address `0x24` return a plausible model byte?
3. Does the write pointer at `0x51` fall inside `0x71`..`0x2000`?
4. Does the full ring dump round-trip (no CRC errors, no timeouts)? If it is flaky,
   adjust the RTS/DTR settle timings first.
5. Do the extracted dive count and the newest dive's max depth / duration match the
   device screen? If depths are off by a constant factor, re-check feet-vs-metric
   and the delta sign. If the oldest dive is missing leading head bytes, revisit the
   unwritten-gap trim in `SuuntoVyperDump`.
6. Capture the per-dive date/time bytes so `SuuntoVyperParser` can set a real
   `startEpochSeconds`.

## Desktop

`JSerialCommTransport` is the desktop equivalent. List ports with
`JSerialCommTransport.availablePortNames()` and open one with
`JSerialCommTransport.byName(name, SuuntoFamily.VYPER.serialParams)`, then use the
same protocol/parser calls. Useful for capturing from a laptop with a known-good
serial stack.
