# Suunto download: real-device test guide

How to test the Suunto USB download against the real cable. The **HelO2 (Vyper2
family)** download is verified end to end on hardware. The **Zoop / old-Vyper
family** is implemented and unit-tested but not yet verified; this guide is mainly
the checklist for that first capture. Line settings and timing are in
[suunto-serial.md](suunto-serial.md).

## Covered by unit tests

`./gradlew :core:divecomputer:jvmTest :core:transport:jvmTest` runs:

- Old Vyper (`SuuntoVyperTest`, `SuuntoVyperProtocolTest`): XOR checksum, the `0x05`
  paged read in both directions (fake device and `ReplayTransport`), ring
  linearisation and split on `0x80`/`0x82` including the wrap, and the
  delta-feet parser against a hand-built memory image.
- Vyper2/HelO2 (`SuuntoVyper2*Test`): packet framing, `ReadMemory` paging,
  `GetVersion`, the directory walk, the incremental backward download and the HelO2
  parser.
- `DiveComputerKindTest`: each kind's serial settings, protocol and parser.
- `SerialLineTransportTest`: the shared line discipline (DTR, RTS turnaround, quiet
  gap, flush, echo discard) used by both desktop and Android.

Still needs a Zoop/Vyper on the cable: the header offsets (model `0x24`, write
pointer `0x51`), the per-dive head layout, the sample interval, the per-dive
date/time, and the 2400 8O1 transport timing.

## Hardware

- A **Suunto USB interface cable** (rotating connector). FTDI and Prolific PL2303
  chipsets are both handled.
- Android needs a **USB-OTG / USB-C host adapter** and grants USB access through a
  system dialog.
- Put the computer in PC/transfer mode if the model needs it, and seat it firmly
  (these cables are contact-fussy).
- On **macOS** use the `cu.*` port, not `tty.*` (the dial-in node blocks on open).

In the app, use **Add dives**, pick the computer and the port.

## Desktop capture tool

The desktop is the least flaky place for a first capture: the cable plugs straight
in and jSerialComm has a mature serial stack.

```sh
# List the ports (run with no cable to see the names):
./gradlew :app:desktop:suuntoCapture

# Auto-pick a usbserial port, old-Vyper family, download and print the dives:
./gradlew :app:desktop:suuntoCapture --args="VYPER"

# Name the port; Vyper2 family dumps the whole memory to a .bin:
./gradlew :app:desktop:suuntoCapture --args="cu.usbserial-XXXX VYPER2"

# The shared download exactly as the apps run it, with timing and parsed dives:
./gradlew :app:desktop:suuntoCapture --args="VYPER2 proto cu.usbserial-XXXX"
```

Without `proto` the tool records the exchange and saves it to
`~/.synth-divelog/suunto-capture-<ts>.transcript.txt`, even on failure, so a flaky
attempt still leaves something to debug. Source: `app/desktop` `SuuntoCapture.kt`.

## First Vyper capture checklist

1. Does the cable enumerate, with the expected VID/PID?
2. Does a single `0x05` read of address `0x24` return a plausible model byte?
3. Does the write pointer at `0x51` fall inside `0x71`..`0x2000`?
4. Does the full ring dump complete without CRC errors or timeouts? If flaky, adjust
   the RTS settle timings (`txSettleMs`, `rxSettleMs` in
   `SuuntoVyperProtocol.SERIAL_PARAMS`) first.
5. Do the dive count and the newest dive's max depth and duration match the device
   screen? Depths off by a constant factor: re-check feet vs metric and the delta
   sign. Oldest dive missing leading head bytes: revisit the unwritten-gap trim in
   `SuuntoVyperDump`.
6. Capture the per-dive date/time bytes so `SuuntoVyperParser` can set a real
   `startEpochSeconds`.

Replay a transcript offline with `ReplayTransport` (`strictWrites = false`), like
`PredatorCaptureRegressionTest`, and commit it as a regression fixture once the
decoded dives match the device's own log screens.
