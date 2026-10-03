# Shearwater transport layer

The link, framing, packet header and upload command set shared by every
Shearwater device we support (Predator, Petrel 1). The device-specific docs build
on this.

## Physical link

- **Bluetooth Classic, Serial Port Profile (SPP).** Service UUID
  `00001101-0000-1000-8000-00805F9B34FB`.
- **Prefer an insecure (unencrypted) RFCOMM socket**, falling back to a secure
  one only if the insecure connect is refused. Older radios (the Petrel 1 in
  particular) carry the small init handshake fine over a secure/encrypted channel
  but drop the ACL link the moment the first full data block flows. The tell is a
  pairing-code (`0000`) prompt appearing on *every* connect: that means an
  encrypted socket is forcing re-pairing. Insecure RFCOMM skips that and keeps the
  link up under load. Verified on a Petrel 1, 2026-10-03: secure socket always
  dropped mid-transfer; insecure socket completed a download.
  Implemented in `BluetoothRfcommTransport.connectPreferInsecure()`.
- The Android `BluetoothSocket` input stream has **no read timeout**. A reader
  thread drains it into a queue and reads wait with a deadline
  (`BluetoothRfcommTransport`).
- For a future wired (USB/serial) transport the line settings are **115200 8N1,
  no flow control**, read timeout ~3000 ms. Baud is irrelevant over RFCOMM.

## SLIP framing (RFC 1055)

Each packet is wrapped in SLIP:

| Byte | Meaning |
|---|---|
| `C0` | `END` |
| `DB` | `ESC` |
| `DB DC` | escaped `END` |
| `DB DD` | escaped `ESC` |

**Trailing `END` only.** Frames end with `C0`; they are *not* prefixed with a
leading `C0`. A leading `END` makes the device reject the frame ("Unexpected SLIP
END"). Implemented in `Slip`.

## Packet header (V1)

Inside each SLIP frame the payload carries a 4-byte header, then the command
payload:

- **Request:**  `FF 01 [payloadLen+1] 00  <payload>`
- **Response:** `01 FF [payloadLen+1] 00  <payload>`

The length byte counts the payload plus one. V1 is what the Predator and Petrel 1
use. (A 16-bit V2 header `FF 01 00 [len16 BE]` exists on newer hardware we do not
target.)

## Command set

First payload byte is the command; the success response echoes it with the high
bit set (`cmd | 0x40` for read/write, or the dedicated upload codes below).

| Command | Req | Rsp | Purpose |
|---|---|---|---|
| Read data by identifier  | `22` | `62` | not used in download |
| Write data by identifier | `2E` | `6E` | not used in download |
| Upload init    | `35` | `75` | announce address + length |
| Transfer data  | `36` | `76` | fetch next block |
| Upload exit    | `37` | `77` | end the transfer |
| Negative ack   | —    | `7F` | `7F [reqCmd] [errCode]` |

This is a diagnostic-style (ISO 14229 / UDS) request/upload sequence.

### Upload init — `35`

```
35 [comp] 34 [addr: BE32] [size: BE24]
```

- `comp` = `00` uncompressed, `10` compressed.
- `34` is the address/length format nibble: 4-byte address, 3-byte size.
- Response: `75 [b1] [maxlen...]`. The **high nibble of `b1` is the number of
  maxlen bytes** (<= 4), big-endian, giving the maximum block payload length. A
  real Predator replied `75 10 82` -> maxlen `0x82` = 130. Fallback 254 if the
  field is unreadable.

### Transfer data — `36`

```
36 [seq]          (V1: two bytes, command + sequence counter)
```

- The **sequence counter starts at 1**, increments per block, and wraps through
  the full byte range (`...FF 00 01...`). Starting at 0 yields `7F 36 73` (UDS NRC
  `0x73` wrong block sequence counter; the device shows "ISO 14229: Dwnld. Wrong
  Seq"). Confirmed on a real Predator, 2026-10.
- **Two bytes only for V1.** A V2 request appends a padding byte; a strict Petrel 1
  rejects the extra byte, so we never send it.
- Response: `76 [seq] <data...>`. The echoed `seq` must match the request.
  Accumulate data across blocks.

### Upload exit — `37`

```
37  ->  77 [..]
```

Always sent to close the transfer, even on error, so the device is not left
mid-session (`ShearwaterMemory` wraps reads in try/finally).

## Reading a fixed vs. unknown length

- **Known size** (Predator full dump): init with `comp=00` and the exact size;
  read blocks until `size` bytes are in.
- **Unknown size** (Petrel per-dive): init with `comp=10` and the maximum size
  (`0xFFFFFF`); the compressed stream self-terminates, so read blocks until the
  decompressor reports end-of-stream. Byte-progress has no meaningful total in
  this mode, so the UI counts dives instead.

## Robustness

- The first init after connecting is occasionally dropped by the device's serial
  stack; `ShearwaterMemory.begin()` retries the init a few times on timeout.
- A NAK (`7F`) is surfaced with its command and error code for diagnosis.
