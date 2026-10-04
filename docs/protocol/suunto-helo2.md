# Suunto HelO2 (D9 family)

Planning notes, **not verified against hardware**. Covers the newer packet protocol
shared by the HelO2, Vyper2, Cobra2/3, Vyper Air and the D-series (D9/D6/D4 and
their successors). See [suunto-serial.md](suunto-serial.md) for the USB layer and
family split.

## Line

- **9600 baud, 8N1.** `DTR` held high powers the interface; give it ~100 ms to settle
  and flush the buffers before the first command.
- **Half-duplex with inverted `RTS`**, the opposite polarity of the old Vyper family:
  drive `RTS` **low to transmit** and **high to receive**, flipping it around each
  write. Unlike the old single-wire cable there is **no echo** of the sent bytes to
  discard (the `RTS` direction control handles it). Let the UART drain (~50 ms) after
  a write before switching to receive so the last byte is not cut off.
- Verified against hardware only after fixing this: the first tests held `RTS` high
  (receive state) during writes, so the command never went out and the device stayed
  completely silent to GetVersion on two different cables, in every full-duplex and
  old-polarity combination. Driving `RTS` low to transmit was the fix.

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
