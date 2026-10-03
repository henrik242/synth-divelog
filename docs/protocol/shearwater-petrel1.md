# Shearwater Petrel 1

Unlike the Predator (one uncompressed dump), the Petrel 1 keeps a **manifest of
dives** and serves **each dive individually, compressed**. The decompressed dive
record uses the same older log format the Predator does, so
[shearwater-predator.md](shearwater-predator.md) covers the field offsets and
`PredatorParser` handles both. See [transport.md](transport.md) for the link,
framing and command set.

Status (2026-10-03): **verified end to end** against a real Petrel 1 — one clean
run downloaded 603 dives across 13 manifest pages with zero protocol errors. The
long-standing Bluetooth link drops during the first data block were a transport
problem, fixed by preferring an **insecure RFCOMM socket** (see transport.md). The
per-dive base address (`0xC0000000`) and the compressed-dive decode are confirmed
by that run.

## Why the Predator path does not work here

The first Petrel attempt used the Predator's full-dump download (init `0x35` at the
Predator address). The init was **accepted** (`0x75`, maxlen 130) but the first
`0x36` block read **timed out** and the Petrel showed "Send Packet Err". So the
Petrel does **not** serve a full dump at `0xDD000000`; it must be driven through
the manifest.

## Manifest

- **Address** `0xE0000000`, **uncompressed** (`comp = 00`).
- Read in fixed pages of **48 records x 32 bytes = 1536 bytes**.
- **Re-read the same address for each page.** The device advances its own internal
  pointer and serves the next page on the next read; the host does *not* add a page
  offset to the address. A seen-fingerprint guard stops us if the device ever
  repeats a page (and prevents an endless loop).
- Stop at a terminator record or an empty page.

### Manifest record (32 bytes)

| Offset | Size | Field |
|---|---|---|
| `+0`  | BE16 | Header: `A5C4` valid, `5A23` deleted, anything else = terminator |
| `+4`  | 4 bytes | Fingerprint (dive identity) |
| `+20` | BE32 | Dive data address |

Deleted records are skipped; a terminator ends the manifest.

## Per-dive read

For each valid manifest entry, read a **compressed** region:

- **Address** = `DIVE_BASE + entry.address`
- `DIVE_BASE` = `0xC0000000`. Confirmed against a real Petrel 1: all 603 dive reads
  used a `0xC0xxxxxx` address. (Some firmware is documented to use `0x80000000`; if
  a future device returns empty or garbled dives, try that base.) On the wire the
  leading `0xC0` appears SLIP-escaped as `DB DC`.
- Init compressed (`comp = 10`) with size `0xFFFFFF` (unknown length). The
  compressed stream self-terminates, so read blocks until the decompressor reports
  end-of-stream rather than to a fixed byte count.

The decompressed blob is a Predator-format dive record -> `PredatorParser`.

**Trailing block:** a Petrel's decompressed dive is 128-block aligned and ends with
one extra block *after* the closing (`FF FE`) block. The parser therefore locates
the closing block by scanning for its marker rather than assuming the last block.
Confirmed across all 603 dives in the verified run: each had exactly one trailing
block. (This was the cause of the "every dive shows 6553.4 m" bug.)

## Compression

Two stages, undone in this order:

### 1. LRE (run-length of zeros, 9-bit codes)

Read the input as a **big-endian stream of 9-bit codes**:

- bit 8 **set** -> literal: output the low 8 bits as one byte.
- bit 8 **clear** and value **> 0** -> a run of that many **zero** bytes.
- value **== 0** -> end of stream.

### 2. Block XOR de-obfuscation

After LRE, for every byte at index `i >= 32`:

```
data[i] ^= data[i - 32]
```

Both stages are implemented and unit-tested in `ShearwaterCompression`
(`decompressLre`, then `decompressXor`). The Predator dump is neither compressed
nor XORed; this applies only to Petrel per-dive reads.

## Protocol selection

The app picks this protocol when the paired Bluetooth device name contains
"petrel" (case-insensitive); otherwise it uses the Predator full-dump protocol.
