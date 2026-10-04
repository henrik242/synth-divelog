# Suunto serial layer

Planning notes for a future USB-cable download of Suunto dive computers. **None of
this is verified against hardware yet** - it is distilled from reverse-engineering
references and should be confirmed against a real capture before relying on it.
Treated as facts to write original code against, not copied from any implementation.

## Physical link

Both target devices connect through Suunto's "old" USB interface cable (the
rotating connector with the red alignment dot). The cable is a USB-to-RS232 bridge
plus Suunto's interface electronics; the dive computer speaks plain RS232 behind it.

- **Chipset:** usually **FTDI** (appears as a standard serial port; default FTDI
  VID `0x0403` / PID `0x6001`). Some older or third-party cables are **Prolific
  PL2303**. Detect both.
- **Line parameters differ by protocol family** (the cable is dumb; the host sets
  these) - see the per-family notes below.

## Two protocol families (this is the key structural fact)

Suunto's serial devices split into two incompatible protocols. Each is a separate
implementation.

| Family | Line params | Members (relevant) | Doc |
|---|---|---|---|
| Old "Vyper" family | 2400 8O1, half-duplex | Spyder, Stinger, Mosquito, **Vyper (original)**, Vytec, Cobra, Gekko, **Zoop** | [suunto-zoop.md](suunto-zoop.md) |
| Newer "D9" family | 9600 8N1, full-duplex | Vyper2, Cobra2, Cobra3, **Vyper Air**, **HelO2**, D9/D6/D4/D9tx/D6i/D4i | [suunto-helo2.md](suunto-helo2.md) |

Mapping for the two targets:
- **Zoop -> old Vyper family.**
- **HelO2 -> newer D9 family.**
- Note: **Vyper Air is in the HelO2 family, not Zoop's**, despite the shared name.
  Implementing the old-Vyper protocol unlocks Vyper/Gekko/Vytec/Cobra/Stinger/
  Mosquito; implementing the D9 protocol unlocks Vyper Air, Vyper2, Cobra2/3 and
  the whole D-series.

## Android USB-host feasibility

Proven approach: Android USB Host Mode (USB-OTG), no root, no kernel drivers.

- Library candidate: **usb-serial-for-android** (mik3y). Covers FTDI, PL2303,
  CP210x, CDC-ACM, CH34x, and exposes `setRTS()` / `setDTR()` and parity config -
  both are required for the half-duplex old-Vyper scheme.
- Needs a USB-OTG adapter (USB-C host on the test phone), the `android.hardware.usb.host`
  feature, and a runtime USB device permission via `UsbManager` (system dialog, or
  an intent-filter + `device_filter.xml` on VID/PID to auto-grant).
- A `UsbSerialTransport` implementing the existing `Transport` interface slots in
  next to `BluetoothRfcommTransport`, so the protocol code stays platform-neutral.

## Implementation status

- `UsbSerialTransport` (Android, `core/transport` androidMain) wraps
  usb-serial-for-android: it applies baud/parity/stop bits from `SerialParams`,
  holds DTR high, and for a half-duplex line flips RTS around each write and
  discards the echoed bytes. `UsbSerialDevices` enumerates adapters and opens one
  after the USB permission is granted.
- `JSerialCommTransport` (desktop, `core/transport` jvmMain) is the JVM equivalent
  over jSerialComm, same contract.
- `SerialParams` (in `core/divecomputer` transport package) carries the line
  settings so the protocol owns them; `SuuntoFamily` selects the protocol, its
  `SerialParams` and its parser, the way the Bluetooth path picks Petrel vs
  Predator.
- iOS: not applicable (no USB host), skipped.

All of the above compiles; none is exercised against the real dongle yet. See
`SUUNTO-TESTING.md` at the repo root.

## Suggested milestone split

Two sub-tasks, not one, because the protocols differ:
1. **USB transport + old-Vyper (Zoop)** first - it exercises the hardest path
   (half-duplex RTS/DTR, odd parity). Harder transport, simpler data model.
2. **D9 (HelO2)** - simpler transport (9600 8N1 full-duplex), richer parser
   (trimix, multiple gases, deco).
