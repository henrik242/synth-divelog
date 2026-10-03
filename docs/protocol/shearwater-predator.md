# Shearwater Predator

The Predator serves its **entire log memory as one uncompressed dump**. There is
no per-dive manifest; individual dives are extracted from a ring buffer inside the
dump. See [transport.md](transport.md) for the link, framing and command set.

Status: verified against a real Predator, 2026-10. All ten dives on a test device
(numbers 863-872) matched the parser exactly on depth, average depth, duration and
start time.

## Download

One upload transfer covers the whole memory:

- **Base address** `0xDD000000`
- **Size** `0x20080` (131200 bytes), uncompressed (`comp = 00`)
- **Block size** 128 bytes (`0x80`)

Read all blocks into one buffer, then extract dives locally. The model byte at
`0x2000D` identifies the hardware variant.

## Memory layout

| Range | Contents |
|---|---|
| `0x00000` .. `0x1F600` | Profile ring buffer (dive records) |
| `0x2000D` | Model byte |

## Ring-buffer extraction

Dives live in a circular ring of 128-byte blocks. Each block's first two bytes
tag it:

| First 2 bytes | Meaning |
|---|---|
| `FF FF` | opening block of a dive |
| `FF FE` | closing block of a dive |
| all `FF` | empty block |

Extraction:

1. Scan the ring for opening (`FF FF`) and closing (`FF FE`) blocks.
2. Pair each opening with the **next closing in circular order**, wrapping once
   past the end of the ring back to the earliest closing.
3. A dive whose blocks straddle the wrap boundary is stitched across it.
4. Orphan closings (no matching opening) are ignored.
5. Sort newest-first by dive number.

The circular pairing matters: on the test device the newest dive (872) wrapped
from the end of the ring back to the start and was missed by a naive linear scan.

## Dive log format

Field offsets are relative to a dive's **opening block**. This same layout is
produced by the Petrel 1 after decompression, so one parser (`PredatorParser`)
handles both.

### Header (opening block)

| Offset | Size | Field |
|---|---|---|
| `+2`  | BE16 | Dive number (e.g. 871) |
| `+8`  | u8   | Units: `0` metric, `1` imperial |
| `+12` | BE32 | Start time, Unix seconds. Also the dive fingerprint. |
| `+20` | 10 x u8 | Gas O2 % per mix slot (`+20`..`+29`) |
| `+30` | 10 x u8 | Gas He % per mix slot (`+30`..`+39`) |

Start time is stored as an epoch with UTC offset 0. The device displays this value
directly as local time, so storing it verbatim keeps dates and times matching the
device screen.

### Samples

Samples begin at `+128` within the dive and are **16 bytes each, fixed stride**,
one every **10 seconds**.

| Offset in sample | Size | Field |
|---|---|---|
| `+0`  | BE16 | Depth. Metric: 0.1 m units. Imperial: 0.1 ft units. (mm = raw x 100 metric, raw x 30.48 imperial) |
| `+2`  | BE16 | Stop (ceiling) depth |
| `+4`  | u8   | TTS, minutes |
| `+7`  | u8   | O2 %  |
| `+8`  | u8   | He %  |
| `+9`  | u8   | NDL, minutes |
| `+12` | u8   | Sensor 0 |
| `+13` | i8   | Temperature, signed degrees C |

### Closing block

The closing block is the first 128-aligned block after the opening one whose first
two bytes are `FF FE`. Do **not** assume it is the last block: a ring-extracted
Predator dive happens to end there, but a Petrel's per-dive blob carries a trailing
block after it (see [shearwater-petrel1.md](shearwater-petrel1.md)). Scan for the
marker instead, or the close marker is read as a `0xFFFE` depth sample (6553.4 m)
and padding as the duration.

| Offset | Size | Field |
|---|---|---|
| `closeStart + 6` | BE16 | Duration, minutes |

Max and mean depth and minimum temperature are derived from the samples (minimum
temperature over submerged samples only).

Temperature (sample `+13`, signed) follows the dive's unit flag: **Celsius for a
metric dive, Fahrenheit for an imperial one**. Confirmed against real dives (an
imperial dive reads ~70 F = ~21 C; a metric dive reads ~10-20 C directly). The
parser converts Fahrenheit to the stored milli-Kelvin for imperial dives.
