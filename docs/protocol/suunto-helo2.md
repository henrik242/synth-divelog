# Suunto HelO2 (D9 family)

Planning notes, **not verified against hardware**. Covers the newer packet protocol
shared by the HelO2, Vyper2, Cobra2/3, Vyper Air and the D-series (D9/D6/D4 and
their successors). See [suunto-serial.md](suunto-serial.md) for the USB layer and
family split.

## Line

- **9600 baud, 8N1.** `DTR` held high powers the interface; give it ~100 ms to settle
  and flush the buffers before the first command.
- **Half-duplex**, the same `RTS` polarity as the old Vyper family: drive `RTS`
  **high to transmit** and **low to receive**, flipping it around each write. Let the
  UART drain after a write before switching to receive so the last byte is not cut off.
- **The reliable turnaround sync is reading the command echo back.** The reference
  driver (and dctool) read the just-sent bytes back - which blocks until the command is
  physically on the wire - then flip to receive. The line does echo on both cables when
  driven this way; both the original and a third-party cable read fine under the
  reference tool. Our own read path does NOT do the echo-read sync, and instead times
  the RTS flip, which is unreliable: the reply is fast and the FTDI latency timer makes
  a fixed settle hit or miss by phase, so we jitter the settle and have `SuuntoD9Link`
  resend/reread (validating header + CRC) until a reply lands. That workaround's hit
  rate is cable/chip dependent - ~40%/attempt on one FTDI (so retries reach 100%),
  lower on another. The proper fix for cable-independent reliability is to implement
  the echo-read sync; a JNA attempt to do so had an unresolved termios config bug.
- Do NOT conclude a cable is "bad" from our read failing: the reference tool reads it,
  so a failure is our turnaround, not the hardware.

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
