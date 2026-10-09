# Suunto serial layer

The USB cable, the two Suunto serial protocol families and the transports that
drive them. The HelO2 download is verified on the real cable; the old-Vyper family
is not yet.

## Physical link

Both families connect through Suunto's "old" USB interface cable (the rotating
connector with the red alignment dot): a USB-to-RS232 bridge plus Suunto's interface
electronics, with plain RS232 behind it.

- **Chipset:** usually **FTDI** (VID `0x0403` / PID `0x6001`); some older or
  third-party cables are **Prolific PL2303** (VID `0x067B`).
- **Line settings differ by family.** The cable is dumb; the host sets them.
- `DTR` held high powers the interface. Both families are half-duplex with `RTS`
  high to transmit and low to receive.

## Two protocol families

Suunto's serial devices split into two incompatible protocols:

| Family | Line | Members | Doc |
|---|---|---|---|
| Old "Vyper" | 2400 8O1, cable echoes sent bytes | Zoop, Vyper (original), Vytec, Cobra, Gekko, Stinger, Mosquito, Spyder | [suunto-zoop.md](suunto-zoop.md) |
| "Vyper2" | 9600 8N1, no echo, 600 ms quiet gap per command | HelO2, Vyper Air, Vyper2, Cobra2, Cobra3 | [suunto-helo2.md](suunto-helo2.md) |

- **Vyper Air is in the Vyper2 family**, despite the name.
- The D-series (D9/D6/D4 and successors) is a third, related protocol and is not
  supported.

## Transports

- `SerialParams` (`core/divecomputer` `transport/`) carries a family's line settings;
  each protocol owns its `SERIAL_PARAMS`, and `DiveComputerKind` pairs them with the
  protocol and parser.
- `SerialLineTransport` (`core/transport` commonMain) applies the line discipline on
  every platform: DTR for the session, a power-up wait where set, and per write the quiet gap
  (`txIdleMs`), an input flush, RTS to transmit, a wait until the bytes have left the
  UART, RTS to receive, and the echo discard where the cable echoes.
- It runs over jSerialComm on desktop (`JSerialCommTransport`) and over
  usb-serial-for-android on Android (`UsbSerialTransport`). Android needs a USB-OTG
  adapter and the user's USB device permission (`UsbSerialDevices`), and its reads
  fill a whole USB packet buffer because the library can drop data on smaller reads.
- iOS has no USB host, so no Suunto download there.

Testing on hardware: [suunto-testing.md](suunto-testing.md).
