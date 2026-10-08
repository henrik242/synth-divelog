# Suunto HelO2 (Vyper2 family)

The newer packet protocol of the Vyper2 family: Vyper2, Cobra2, Cobra3, Vyper Air
and HelO2. **Verified on a HelO2** over the original Suunto cable (full download of
42 dives). See [suunto-serial.md](suunto-serial.md) for the USB layer and family
split.

## Line

- **9600 baud, 8N1.** `DTR` held high powers the interface; give it ~100 ms to settle
  and flush the buffers before the first command.
- **Half-duplex**, the same `RTS` polarity as the old Vyper family: drive `RTS`
  **high to transmit** and **low to receive**, flipping it around each write. Let the
  UART drain after a write before switching to receive so the last byte is not cut off.
- **Keep the line quiet for 600 ms before each command.** The device ignores a command
  that arrives less than ~500 ms after the end of its previous reply. Measured on the
  original cable with a raw termios probe: every read succeeded with a quiet gap of
  500 ms or more, and every read sent sooner was ignored (the ~50% success seen without the
  gap is only the attempts that follow a timed-out one). This is the same for 8-byte and
  full 0x78-byte pages, so it is a gap after the reply, not a command period. The
  reference driver sleeps 600 ms before every packet for the same reason.
- **Turnaround:** RTS high, write, switch RTS to receive as soon as the command is on
  the wire (bytes x 10 bits / 9600 baud from the start of the write, plus ~2 ms), read.
  The reply starts ~20 ms after the command ends and a full page takes ~170 ms. Do not
  add a settle on top: jSerialComm's `writeBytes` already returns after the bytes are
  out, and an extra 10 ms put the switch on top of the reply start and garbled its first
  bytes in about one exchange in four. No echo
  is involved. The earlier unreliability came from sending commands back to back, not
  from the turnaround timing.
- **Throughput:** a full ring read is ~270 pages at ~0.75 s each, about 3.5 minutes. An
  incremental download only reads back to the last known dive.
- **Not the D9 family.** The D-series (D9/D6/D4 and successors) uses the same framing
  and memory layout ideas but a different line protocol (it reads back a command echo
  and drives RTS the other way). That does not work on the HelO2 (tried: no echo
  arrives), and the D-series is not supported here.
- Do NOT conclude a cable is "bad" from our read failing: the reference tool reads it,
  so a failure is our turnaround, not the hardware.

## Packet framing

- `[command] [length] [parameters...] [crc]`, where `length` is the number of
  parameter bytes. Every packet carries a CRC. A reply repeats the command and the
  request parameters, then the data; its `length` counts parameters plus data
  (`0f 00 04` for GetVersion, `05 00 7b` for a 0x78-byte read).

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

Implemented in `core/divecomputer` `suunto/`:

- `SuuntoVyper2Link` - packet framing, XOR CRC, `ReadMemory` with paging up to `0x78`
  bytes, and `GetVersion`. A reply must match the command, length field, echoed
  parameters and CRC; up to 4 attempts.
- `SuuntoVyper2Protocol` - `SERIAL_PARAMS` (incl. the 600 ms quiet gap) and the
  download: reads the profile ring backward from the newest dive a page at a time and
  stops at the last known dive, so an incremental download reads only the new dives.
- `SuuntoVyper2Dump` - the directory walk over the ring (`dives`, `extract`).
- `SuuntoVyper2Parser` - the HelO2 record and profile layout. Other models in the
  family are rejected until their offsets are known.
