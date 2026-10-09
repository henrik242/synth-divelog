# Suunto HelO2 (Vyper2 family)

The packet protocol of the Vyper2 family: Vyper2, Cobra2, Cobra3, Vyper Air and
HelO2. **Verified on a HelO2** over the original Suunto cable (full download of 42
dives). See [suunto-serial.md](suunto-serial.md) for the cable and the family split.

## Line

- **9600 baud, 8N1, half-duplex, no echo.** `DTR` high powers the interface; give it
  ~100 ms to settle and flush the buffers before the first command. `RTS` high to
  transmit, low to receive, flipped around each write.
- **Keep the line quiet for 600 ms before each command.** The device ignores a command
  that arrives less than ~500 ms after the end of its previous reply. Measured on the
  original cable with a raw termios probe: every read with a quiet gap of 500 ms or
  more succeeded, every read sent sooner was ignored (the ~50% success seen without
  the gap is only the attempts that follow a timed-out one). The same holds for 8-byte
  and full `0x78`-byte pages, so it is a gap after the reply, not a command period.
  libdivecomputer sleeps 600 ms before every packet for the same reason.
- **Turnaround:** switch RTS to receive as soon as the command is on the wire
  (bytes x 10 bits / 9600 baud from the start of the write, plus ~2 ms). The reply
  starts ~20 ms after the command ends and a full page takes ~170 ms. Do not add a
  settle on top: jSerialComm's `writeBytes` already returns after the bytes are out,
  and an extra 10 ms put the switch on top of the reply start and garbled its first
  bytes in about one exchange in four.
- **Throughput:** a full ring read is ~270 pages at ~0.75 s each, about 3.5 minutes.
  An incremental download only reads back to the last known dive.
- **Not the D9 family.** The D-series uses the same framing and memory layout ideas
  but a different line protocol (it reads back a command echo and drives RTS the
  other way). That does not work on the HelO2 (tried: no echo arrives).
- A failing read does not mean a bad cable: other software reads the same cable, so
  look at our timing first.

## Packet framing

`[command] [length: BE16] [parameters...] [crc]`, where `length` counts the parameter
bytes and `crc` is the XOR of all preceding bytes. A reply repeats the command and
the request parameters, then the data; its `length` counts parameters plus data
(`0f 00 04` for GetVersion, `05 00 7b` for a `0x78`-byte read).

## Commands

- **ReadMemory:** `05 00 03 [addrHi] [addrLo] [count] [crc]`, `count` = 1..`0x78`
  (120 bytes per read).
- **GetVersion:** `0F 00 00 [crc]`; reply `0F 00 04 [id] [high] [mid] [low] [crc]`.
  Firmware is `high.mid.low`.

## Data

A dive directory plus profile blocks in a ring, parsed from their headers. Richer
than the old family: multiple gas mixes with He/O2 fractions, and deco information
per sample.

## Code

In `core/divecomputer` `suunto/`:

- `SuuntoVyper2Link` - framing, CRC, `ReadMemory` paging, `GetVersion`. A reply must
  match the command, length, echoed parameters and CRC; up to 4 attempts.
- `SuuntoVyper2Protocol` - `SERIAL_PARAMS` and the download: reads the profile ring
  backward from the newest dive a page at a time and stops at the last known dive.
- `SuuntoVyper2Dump` - the directory walk over the ring.
- `SuuntoVyper2Parser` - the HelO2 record and profile layout. Other models in the
  family are rejected until their offsets are known.
