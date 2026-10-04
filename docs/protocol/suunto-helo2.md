# Suunto HelO2 (D9 family)

Planning notes, **not verified against hardware**. Covers the newer packet protocol
shared by the HelO2, Vyper2, Cobra2/3, Vyper Air and the D-series (D9/D6/D4 and
their successors). See [suunto-serial.md](suunto-serial.md) for the USB layer and
family split.

## Line

- **9600 baud, 8N1, full-duplex.** No `RTS`/`DTR` direction toggling - a
  straightforward request/response packet exchange.
- The cable draws power from **both `DTR` and `RTS`**: hold both high, then give the
  interface ~100 ms to power up and flush the buffers before the first command. With
  `RTS` left low the device stays completely silent (no reply to GetVersion). This
  bit out on the first hardware test, where every read timed out until `RTS` was
  asserted.

## Packet framing

- `[command] [length] [parameters...] [crc]`, where `length` is the number of
  parameter bytes. Every packet carries a CRC.

## Read memory

- ReadMemory: `05 00 03 [addrHi] [addrLo] [count] [crc]`, `count` = 1..`0x78` (up to
  120 bytes per read - larger than the old family).
- Paged reads over the memory; a dive directory plus profile blocks are parsed from
  headers.

## Data model

Richer than the old family (a trimix/CCR-capable device): multiple gas mixes with
He/O2 fractions, and deco information per sample.

## GetVersion

- `0F 00 00 crc`; reply `0F 00 00 [id] [high] [mid] [low] crc`. Firmware is
  `high.mid.low`.

## Implementation status

Partly implemented in `core/divecomputer` `suunto/`:

- `SuuntoD9Link` - packet framing (`[cmd][lenHi][lenLo][params][crc]`), XOR CRC,
  `ReadMemory` with paging up to `0x78` bytes, and `GetVersion`.
- `SuuntoD9Protocol` - `DiveComputerProtocol` with the `SERIAL_PARAMS` and device
  info from the version command. `download` throws for now: the dive directory and
  the richer trimix/deco profile parser are **not written yet**.

**Verified by unit tests** (`SuuntoD9LinkTest`): request framing and reply decode
for `ReadMemory` (incl. paging) and `GetVersion`, via `ReplayTransport`.

**Not yet done:** the dive directory walk, the profile/sample layout and the parser.
This is scaffolding for the HelO2 work; it needs a capture to go further.

## Effort / risk

Lower transport risk than the Zoop family (clean full-duplex packets, bigger
reads), but more parser work for the richer data model.
