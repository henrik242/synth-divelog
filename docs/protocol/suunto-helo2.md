# Suunto HelO2 (D9 family)

Planning notes, **not verified against hardware**. Covers the newer packet protocol
shared by the HelO2, Vyper2, Cobra2/3, Vyper Air and the D-series (D9/D6/D4 and
their successors). See [suunto-serial.md](suunto-serial.md) for the USB layer and
family split.

## Line

- **9600 baud, 8N1.** `DTR` held high powers the interface; give it ~100 ms to settle
  and flush the buffers before the first command.
- **Half-duplex**, the same `RTS` polarity as the old Vyper family: drive `RTS`
  **high to transmit** and **low to receive**, flipping it around each write. Unlike
  the old single-wire cable the proper interface does **not echo** the sent bytes, so
  there is nothing to discard. Let the UART drain (~50 ms) after a write before
  switching to receive so the last byte is not cut off.
- Verified on hardware with a line-settings probe (GetVersion `0f 00 00 0f`). The
  original Suunto cable replied `0f 00 04 ...` only under this half-duplex,
  RTS-high-to-transmit config; full-duplex (RTS held either way) and the inverted
  polarity all stayed silent. The reference driver describes the transmit state as
  `set_rts(0)`, which maps to the opposite physical level through this FTDI cable, so
  the measured polarity is what counts. A third-party single-wire cable echoed our
  own bytes instead of relaying the reply, so it is not usable as-is.

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
