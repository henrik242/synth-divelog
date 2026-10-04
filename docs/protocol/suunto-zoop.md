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

Header fields (absolute addresses, confirmed against the family protocol notes,
not yet against a capture):

| Address | Size | Field |
|---|---|---|
| `0x24` | u8 | Computer type (`0x16` Zoop, `0x0C` Vyper, `0x0D` Cobra, `0x0B` Gekko, `0x0A` Vytec, `0x03` Stinger, `0x04` Mosquito) |
| `0x51` | BE16 | Profile write pointer: absolute address of the `0x82` end-of-dataset byte |

- Profiles live in a **ring buffer** spanning `0x71`..`0x2000`; the write pointer
  wraps from `0x2000` back to `0x71`. Unwritten bytes read as `0x00`.
- Dives are a continuous stream. Each dive is terminated by a `0x80`
  end-of-dive marker; a single `0x82` end-of-dataset marker sits after the most
  recent dive, at the write pointer.
- A dive record is a 3-byte head then one signed delta-depth byte per sample
  interval, **stored end-of-dive first** (reverse time):

  | Offset | Field |
  |---|---|
  | `+0` | `OLF`/mode byte (bit 7 selects OTU over CNS) |
  | `+1` | tank pressure at end of dive, bar / 2 (0 on hoseless Zoop) |
  | `+2` | water temperature at end of dive, signed Celsius |
  | `+3..` | signed delta-depth bytes, feet: `delta = previousDepth - currentDepth` |

  So a descent is negative (e.g. 30 ft down is `-30` = `0xE2`) and an ascent
  positive. The parser reads the deltas back-to-front to rebuild the profile from
  the surface. Zoop records depth + temperature only (Cobra/Vyper Air add tank
  pressure). The sample interval lives in the device header (Zoop default 20 s).

## Implementation status

Implemented in `core/divecomputer` `suunto/`:

- `SuuntoVyperMemory` - the `0x05` paged read with XOR CRC, over `Transport`.
- `SuuntoVyperDump` - ring linearisation from the write pointer and split into
  per-dive blobs on the `0x80`/`0x82` markers.
- `SuuntoVyperParser` - delta-depth reconstruction, max/mean depth, duration,
  temperature.
- `SuuntoVyperProtocol` - `DiveComputerProtocol`; carries the `SERIAL_PARAMS`.

**Verified by unit tests** (`SuuntoVyperTest`, `SuuntoVyperProtocolTest`): the CRC,
the paged read framing in both directions (via a fake device and `ReplayTransport`),
ring extraction incl. the wrap, and the depth/temperature parser against a
hand-built memory image.

**Not yet verified against hardware:** the exact head layout, the per-dive
date/time (so `startEpochSeconds` is left 0 and identity uses a content
fingerprint), and the sample interval. Known edge case: an oldest dive whose head
begins with `0x00` can lose those leading bytes to the unwritten-gap trim; confirm
and refine against a real capture. See `SUUNTO-TESTING.md`.

## Effort / risk

Moderate - the data format is tiny and documented, but the half-duplex timing and
`RTS`/`DTR`/odd-parity dance is the classic source of flakiness. Risk is in the
transport/timing layer, not the parsing.
