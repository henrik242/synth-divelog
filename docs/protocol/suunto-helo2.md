# Suunto HelO2 (D9 family)

Planning notes, **not verified against hardware**. Covers the newer packet protocol
shared by the HelO2, Vyper2, Cobra2/3, Vyper Air and the D-series (D9/D6/D4 and
their successors). See [suunto-serial.md](suunto-serial.md) for the USB layer and
family split.

## Line

- **9600 baud, 8N1, full-duplex.** No `RTS`/`DTR` direction toggling - a
  straightforward request/response packet exchange.

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

## Effort / risk

Lower transport risk than the Zoop family (clean full-duplex packets, bigger
reads), but more parser work for the richer data model.
