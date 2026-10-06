# Suunto download: real-device test guide

How to validate the Suunto USB download against the real cable.

**Status:** the **HelO2 (D9 family)** download is verified end to end on hardware
(full download over the original Suunto cable), driven from the app's "Add dives"
flow and the desktop `suuntoCapture` tool. The **Zoop / old-Vyper family** is
implemented and unit-tested but **not yet verified against hardware** - this guide
is mainly the checklist for that first Vyper capture.

## What is verified vs what needs hardware

Verified by unit tests (`./gradlew :core:divecomputer:jvmTest`):

- XOR checksum (`SuuntoCrc`).
- Old-Vyper paged read `0x05` framing, both directions - via a fake device and via
  `ReplayTransport` (`SuuntoVyperProtocolTest`, `SuuntoVyperTest`).
- Ring-buffer linearisation and split on the `0x80`/`0x82` markers, including the
  wrap (`SuuntoVyperTest`).
- Depth/temperature parsing from delta-feet samples against a hand-built memory
  image (`SuuntoVyperTest`).
- D9/HelO2 packet framing, `ReadMemory` (paging) and `GetVersion`; the dive
  directory walk and the HelO2 record/profile parser.
- Family selection -> serial params + protocol + parser (`SuuntoFamilyTest`).

Still needs a Zoop/Vyper on the cable:

- The Vyper header offsets (model `0x24`, write pointer `0x51`), the per-dive head
  layout, the sample interval, and the per-dive **date/time** (so
  `startEpochSeconds` is 0 for now and identity uses a content fingerprint).
- The Vyper transport timing (2400 8O1 half-duplex, RTS/DTR, echo discard).

## Serial parameters (verified for HelO2; spec for Vyper)

| Family | Baud | Frame | Duplex | Lines |
|---|---|---|---|---|
| Vyper / Zoop | 2400 | 8O1 | half | DTR high; RTS high to transmit, low to receive; cable **echoes** sent bytes (discarded) |
| HelO2 / D9 | 9600 | 8N1 | half | DTR high; RTS high to transmit, low to receive; **no echo**; reply window ~6-10 ms after the write |

The HelO2 turnaround has no echo to sync on, so the RTS-to-receive switch must land
in a narrow window; `SuuntoD9Link` jitters the settle and retries. See
[docs/protocol/suunto-helo2.md](docs/protocol/suunto-helo2.md).

## Hardware

- A **Suunto USB interface cable** (rotating connector). Chipset is usually FTDI
  (VID `0x0403` / PID `0x6001`); some are Prolific PL2303 (VID `0x067B`). Both are
  handled by usb-serial-for-android's default prober.
- Android needs a **USB-OTG / USB-C host adapter**; the test Samsung S25 has a
  USB-C host port (reach it over adb per `memory/test-device.md`).
- Put the computer in PC/transfer mode if the model requires it, and seat it firmly
  (these cables are contact-fussy).
- On **macOS** use the `cu.*` port, not `tty.*` (the dial-in node blocks on open).

The download itself is wired into the shared **Add dives** flow (pick the computer
and the port); no manual wiring needed.

## Desktop capture tool (easiest first capture)

The Suunto cable plugs straight into a Mac/PC USB port (no OTG), and jSerialComm has
a mature serial stack, so the desktop is the least flaky place for a first capture.

```sh
# List the ports (run with no cable to see the names):
./gradlew :app:desktop:suuntoCapture

# Auto-pick a usbserial-* port, old-Vyper family:
./gradlew :app:desktop:suuntoCapture --args="VYPER"

# Or name the port explicitly, HelO2/D9 family:
./gradlew :app:desktop:suuntoCapture --args="cu.usbserial-XXXX D9"
```

It opens the port through a `RecordingTransport`, runs the download, prints the
decoded dives, and saves the raw transcript to
`~/.synth-divelog/suunto-capture-<ts>.transcript.txt` - even on failure, so a flaky
attempt still leaves something to debug. `SuuntoCapture.kt` (in `app/desktop`) is
the reference for the raw API and has extra diagnostic modes.

## First Vyper capture checklist

1. Does the cable enumerate, and is the VID/PID what we expect?
2. Does a single `0x05` read of address `0x24` return a plausible model byte?
3. Does the write pointer at `0x51` fall inside `0x71`..`0x2000`?
4. Does the full ring dump round-trip (no CRC errors, no timeouts)? If flaky, adjust
   the RTS/DTR settle timings first.
5. Do the extracted dive count and the newest dive's max depth / duration match the
   device screen? If depths are off by a constant factor, re-check feet-vs-metric
   and the delta sign. If the oldest dive is missing leading head bytes, revisit the
   unwritten-gap trim in `SuuntoVyperDump`.
6. Capture the per-dive date/time bytes so `SuuntoVyperParser` can set a real
   `startEpochSeconds`.

Replay a transcript offline with `ReplayTransport` (strictWrites = false), like
`PredatorCaptureRegressionTest`, and promote it to a committed regression fixture
once the decoded dives match the device's own log screens.
