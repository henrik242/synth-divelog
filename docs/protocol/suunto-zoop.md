# Suunto Vyper / Zoop (old Vyper family)

The old-Vyper serial protocol shared by the Vyper, Vytec, Cobra, Gekko, Zoop,
Stinger, Mosquito, D3 and Spyder. **Verified on a Vyper (model `0x0A`)**: a full
ring download of 47 dives (2000-2006) over the Suunto USB cable on macOS. Layout
follows libdivecomputer (`suunto_vyper.c`, `suunto_vyper_parser.c`,
`suunto_common.c`). See [suunto-serial.md](suunto-serial.md) for the cable and
family split.

## Transfer mode

Unlike the HelO2, the computer does not answer just because the cable is attached.
Put it in **TR-PC** mode first (on the Vyper: Mode -> Logbook -> TR-PC). Outside
TR-PC it ignores every command.

## Line and direction control

- **2400 baud, 8 data bits, odd parity, 1 stop bit (2400 8O1).**
- **Half-duplex.** `RTS` set to transmit, cleared to receive. `DTR` stays set the
  whole session; it powers the interface.
- Timing as libdivecomputer: 500 ms quiet before each command, RTS held 200 ms after
  the write, then cleared; the reply follows within ~300 ms.
- The Suunto cable does **not echo**. With RTS held low while sending, the cable
  loops the command back and the computer never sees it.

## Packet framing

- Every packet ends with a 1-byte **CRC = XOR of all preceding bytes**.

## Read memory

- Command byte **`0x05`**: send `05 [addrHi] [addrLo] [count] [crc]`, `count` =
  1..32.
- Reply: `05 [addrHi] [addrLo] [count] [count data bytes] [crc]`.

## Memory layout

| Address | Size | Field |
|---|---|---|
| `0x24` | u8 | Model (`0x0A` Vyper, `0x0B` Vytec, `0x0C` Cobra, `0x0D` Gekko, `0x16` Zoop, `0x03` Stinger, `0x04` Mosquito, `0x05` D3, `0x01` Spyder) |
| `0x25` | u8 | Firmware |
| `0x26` | 4 | Serial, four base-100 digits |
| `0x51` | BE16 | End-of-profile pointer: address of the `0x82` marker after the newest dive |

The Spyder uses other offsets (pointer `0x1C`, ring from `0x4C`); not handled.

Profiles live in a ring buffer `0x71`..`0x2000`, oldest dive first. Each dive is a
14-byte header, the samples, a `0x80` end-of-dive marker and four trailer bytes. A
dive starts where the byte five positions back is the previous dive's `0x80`, so
the dives are found by walking back from the pointer.

## Dive record

| Offset | Field |
|---|---|
| `+3` | sample interval, seconds (20 on the Vyper) |
| `+4` | mode flags: `0x40` gauge, low nibble personal adjustment |
| `+5` | tank pressure at start, bar / 2 |
| `+6` | O2 percent (0 = air) |
| `+8` | surface temperature, signed Celsius |
| `+9..+13` | start: year (`< 90` -> 20xx, else 19xx), month, day, hour, minute; local time |
| `+14..` | samples until `0x80` |
| marker `+1` | minimum temperature, signed Celsius |
| marker `+3` | tank pressure at end, bar / 2 |

A sample byte outside `0x79..0x87` is a signed depth delta in feet (positive is
deeper), one per interval. Bytes in that range are events: `0x7A` slow down, `0x7B`
violation, `0x7C` bookmark, `0x7D` surface, `0x7E` deco, `0x7F` ceiling, `0x81`
safety stop, `0x87` gas change followed by the O2 percent.

## Code

In `core/divecomputer` `suunto/`: `SuuntoVyperMemory` (paged read),
`SuuntoVyperDump` (ring walk), `SuuntoVyperParser`, `SuuntoVyperProtocol`
(`SERIAL_PARAMS`). Tests: `SuuntoVyperTest`, `SuuntoVyperProtocolTest`.
