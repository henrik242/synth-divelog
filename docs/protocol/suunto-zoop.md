# Suunto Zoop (old Vyper family)

Planning notes, **not verified against hardware**. Covers the old-Vyper serial
protocol shared by the Zoop, original Vyper, Vytec, Cobra, Gekko, Stinger and
Mosquito. See [suunto-serial.md](suunto-serial.md) for the USB layer and family
split.

## Line and direction control

- **2400 baud, 8 data bits, odd parity, 1 stop bit (2400 8O1).**
- **Half-duplex.** `RTS` toggles line direction: set `RTS` to transmit, clear it to
  receive. `DTR` must stay set the whole session - it powers the interface.
- Timing (approximate, to confirm): ~200 ms settle before clearing `RTS`, ~400 ms
  after clearing before reading, ~500 ms receive timeout.
- Because it is half-duplex, **bytes sent are echoed back on RX** and must be read
  and discarded before the real reply.

## Packet framing

- Every packet ends with a 1-byte **CRC = XOR of all preceding bytes** (simple
  single-byte XOR checksum).

## Read memory

- Command byte **`0x05`**: send `05 [addrHi] [addrLo] [count] [crc]`, `count` =
  1..32.
- Reply: `05 [addrHi] [addrLo] [count] [count data bytes] [crc]`.
- Max 32 bytes per read, so a full dump is many paged reads.

## Memory and dive layout

- Profiles live in a **ring buffer** spanning roughly `0x71`..`0x1FFF`; the write
  pointer wraps from `0x2000` back to `0x71`.
- A header area holds the current write position; dives are a continuous stream
  delimited by per-dive headers, walked from the write pointer.
- Each dive begins with format info (e.g. an OLF/OTU percentage with bit 7 as the
  OLF/OTU selector, end-of-dive pressure in bar/2, temperature), then depth/time
  samples at the device's sample interval. Zoop is depth + temperature only
  (Cobra/Vyper Air add tank pressure).

## Effort / risk

Moderate - the data format is tiny and documented, but the half-duplex timing and
`RTS`/`DTR`/odd-parity dance is the classic source of flakiness. Risk is in the
transport/timing layer, not the parsing.
