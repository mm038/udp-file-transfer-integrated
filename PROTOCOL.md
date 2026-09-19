# Wire Format — DATA/ACK Binary Header

This document covers only the high-frequency DATA and ACK messages, which
use a compact binary format. START, START_ACK, FINISH, FINISH_ACK, and
ERROR are separate JSON-encoded control messages (see `ControlMessage`)
and are not covered here.

## Header layout

One fixed-size header shared by both DATA and ACK packets. ACK packets
have payloadLen = 0 and no payload bytes.

| Order | Field       | Size     | Type            | Meaning |
|-------|-------------|----------|-----------------|---------|
| 1     | type        | 1 byte   | unsigned byte   | MessageType ordinal; only DATA or ACK appear here |
| 2     | transferId  | 16 bytes | raw UUID        | UUID.getMostSignificantBits() then getLeastSignificantBits(), 8 bytes each |
| 3     | seqNum      | 4 bytes  | int             | Chunk index (0,1,2,...) for DATA; cumulative ACK number for ACK (highest contiguous chunk index received) |
| 4     | payloadLen  | 2 bytes  | unsigned short  | Number of payload bytes following. 0 for ACK |
| 5     | crc32       | 4 bytes  | unsigned int    | CRC-32 over [type + transferId + seqNum + payloadLen + payload], i.e. everything above except this field itself |
| 6     | payload     | 0-1024 bytes | raw bytes  | File chunk data. Absent for ACK |

**Fixed header size: 27 bytes** (1 + 16 + 4 + 2 + 4).

**Byte order: big-endian ("network byte order")** for all multi-byte
fields. This is the standard Internet convention and is what Java's
DataOutputStream/DataInputStream write by default.

## Size limits

- Max payload: 1024 bytes
- Max DATA packet: 27 (header) + 1024 (payload) = **1051 bytes**
- ACK packet: always exactly **27 bytes**

## Why these limits (fragmentation avoidance)

Standard Ethernet MTU = 1500 bytes. UDP header = 8 bytes, IPv4 header =
20 bytes (overhead outside our control) = 28 bytes total. A max-size DATA
packet on the wire is 1051 + 28 = 1079 bytes, leaving over 400 bytes of
headroom below the 1500-byte MTU. This is a deliberate simplicity choice
to avoid IP fragmentation, at the cost of needing more packets for large
files than a larger chunk size would require.

## Sequence number semantics

Chunk-indexed (0, 1, 2, ...), not byte-offset. Every chunk except possibly
the last is exactly `chunkSize` bytes (negotiated in the START handshake),
so byte-precision offers no benefit here.

ACK's seqNum is cumulative: "every chunk from 0 up to and including this
number has been correctly received." Matches the reliability design
(receiver tracks highestContiguousSeqReceived).

Out of scope for this document (behavioral, not wire-format, decided in
later stages): the bootstrapping value of the first ACK before any data
arrives, window-size fields, retransmission flags/counters.

## CRC-32 scope

CRC-32 (java.util.zip.CRC32) is computed over every header field except
the CRC field itself, plus the payload if present:
`type + transferId + seqNum + payloadLen [+ payload]`

This applies to ACK packets too, not just DATA. Rationale: a corrupted ACK
is a correctness risk, not just noise — a bit-flip in seqNum could make the
sender believe the wrong chunk was acknowledged. A failed-CRC ACK is
treated the same as a lost ACK (ignored, left to time out and retransmit),
rather than acted on.

Note for metrics: a failed-CRC ACK is logged as a distinct CORRUPT_ACK
event, not merged with LOST_ACK — same retry behavior, different
observable failure mode.

## Relationship to ControlMessage

Transfer IDs are the same UUID across both formats: ControlMessage carries
it as a 36-character string (`UUID.toString()`); this binary format packs
it into 16 raw bytes (`UUID.fromString(...)`, then split into two longs).
Both refer to the identical transfer.

## Byte layout diagram

```
Offset:  0      1                              17     21          23              27         (variable)
Field:  [type] [transferId — 16 bytes         ] [seqNum] [payloadLen] [crc32      ] [payload...]
```