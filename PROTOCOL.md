# Wire Format — DATA/ACK Binary Header

This document covers only the high-frequency DATA and ACK messages, which
use a compact binary format. START, START_ACK, FINISH, FINISH_ACK, and
ERROR are separate JSON-encoded control messages (see `ControlMessage`)
and are not covered here.

Reviewed against the integrated working tree based on commit `985b1a8`, including
the verified local repairs, on September 25, 2026. This document
is an implementation reference, not yet the complete assignment protocol
deliverable: full JSON control formats, the state machine, command-validation
boundary and a normal/loss sequence diagram still need to be assembled. See the
[current evaluation checkpoint](README.md#current-evaluation-checkpoint).

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

The decoder enforces exact datagram length (`27 + payloadLen`), the 1024-byte
payload maximum, DATA/ACK type, and zero ACK payload before allocating the payload.
The receiver also enforces each DATA sequence's expected length from the accepted
file/chunk size, including the final remainder and the empty-file convention,
before updating sequence state, writing, acknowledging or resetting progress.
This repairs the earlier framing finding. Saved
[framing validation](target/evaluation/data-framing-20260925-161314-533/validation.json)
records 160 passing focused tests; [independent checks](target/evaluation/data-framing-20260925-161314-533/independent-framing-checks.json)
retain malformed-frame rejection and corrected-file hash evidence.

One pre-existing limit remains: START's advertised file size is converted to an
`int` chunk count without an explicit extreme-size/overflow bound. Use bounded
practical experiment sizes. This is a documented limit, not a demonstrated
blocker for the required assignment experiments; no false-success case was reproduced.

## Why these limits (fragmentation avoidance)

Standard Ethernet MTU = 1500 bytes. UDP header = 8 bytes, IPv4 header =
20 bytes (overhead outside our control) = 28 bytes total. A max-size DATA
packet on the wire is 1051 + 28 = 1079 bytes, leaving over 400 bytes of
headroom below the 1500-byte MTU. This is a deliberate simplicity choice
to avoid IP fragmentation, at the cost of needing more packets for large
files than a larger chunk size would require.

## Sequence number semantics

Chunk-indexed (0, 1, 2, ...), not byte-offset. Every chunk except possibly
the last is exactly `chunkSize` bytes (proposed by the sender and accepted or
rejected by the receiver in the START handshake),
so byte-precision offers no benefit here.

START_ACK does not propose an alternative chunk size. Both ordinary launchers
propose 1024 bytes. An empty file is one zero-length DATA chunk.

ACK's seqNum is cumulative: "every chunk from 0 up to and including this
number has been correctly received." Matches the reliability design
(receiver tracks highestContiguousSeqReceived).

Before any in-order DATA is accepted, the receiver tracker uses -1 internally;
it does not send that sentinel as an ACK. The first ACK follows acceptance of
chunk 0. The sender accepts progress ACKs only from zero through the highest
sent sequence. Window capacity and retransmission counters are local engine
settings and observations, not additional binary header fields.

## CRC-32 scope

CRC-32 (java.util.zip.CRC32) is computed over every header field except
the CRC field itself, plus the payload if present:
`type + transferId + seqNum + payloadLen [+ payload]`

This applies to ACK packets too, not just DATA. Rationale: a corrupted ACK
is a correctness risk, not just noise — a bit-flip in seqNum could make the
sender believe the wrong chunk was acknowledged. A failed-CRC ACK is
treated the same as a lost ACK (ignored, left to time out and retransmit),
rather than acted on.

For metrics, a decoded ACK attributable to the expected peer/type/UUID is
recorded as `DATA_ACK_RECEIVED` before full acceptance validation. An attributable
ACK that fails validation can then produce `DATA_ACK_REJECTED`. There are no
`CORRUPT_ACK` or `LOST_ACK` events in the current `EventType` enum. A DATA deadline
can produce `DATA_TIMEOUT` and `RECOVERY_ROUND`; those observations do not prove
whether DATA, an ACK, or something else caused the missing progress.

## Relationship to ControlMessage

Transfer IDs are the same UUID across both formats: ControlMessage carries
it as a 36-character string (`UUID.toString()`); this binary format packs
it into 16 raw bytes (`UUID.fromString(...)`, then split into two longs).
Both refer to the identical transfer.

After DATA is acknowledged, the sender computes the source file's SHA-256 for
FINISH. The receiver requires all expected sequence slots and compares its
reconstructed file against that hash before returning the verification result.

## Sender ACK acceptance

Before a binary ACK can advance the sender window, cancel DATA timers, or
reset the consecutive recovery-round count, the sender requires all of the
following:

- successful decoding as one exact 27-byte ACK packet;
- ACK message type and valid CRC-32;
- the active protocol transfer UUID;
- the configured receiver IP address and UDP port; and
- a cumulative sequence number from zero through the highest DATA sequence
  already sent.

A stale or duplicate ACK within that range is a valid arrival but produces no
new window progress. A valid cumulative ACK may advance across several DATA
sequences. Malformed, corrupt, unrelated, and out-of-range packets are ignored
and cannot cancel timers or reset Go-Back-N recovery state.

## Control and timeout behavior

START is a bounded retry handshake rather than one unbounded blocking
exchange. By default the sender waits 1000 ms per attempt and permits five
retries after the initial attempt. Only a matching START_ACK from the expected
receiver completes the handshake. The ordinary command console instead uses
2000 ms per START attempt; the 1000 ms default here describes direct Main/engine
startup. Main's system properties do not configure all console-adapter settings.

During DATA transfer, expiry of the oldest outstanding DATA packet triggers a
Go-Back-N recovery round that resends every outstanding DATA packet. The
consecutive round count resets only when a validated cumulative ACK advances
the sender window. The default DATA deadline is 200 ms and the default retry
limit is five rounds.

The receiver waits up to five minutes for an initial valid START and, after
acceptance, fails after 60 seconds without newly written DATA progress. These
defaults are configurable through the application settings documented in
README.md.

FINISH uses its own bounded retry policy. By default the sender waits 1000 ms
per FINISH transmission and permits five retries after the initial attempt
(six transmissions total). It accepts a FINISH_ACK only from the negotiated
receiver address and port when the control message parses, has the active
transfer UUID, has type FINISH_ACK, and contains an explicit `verified` value.
Unrelated or malformed traffic is ignored within a fixed per-attempt deadline.
An attributable `verified=false` response is a terminal integrity failure;
exhaustion produces `FINISH_HANDSHAKE_TIMEOUT`.

After sending the first FINISH_ACK, the receiver closes the output file and
keeps the cached verification result for a bounded completion recovery period.
The default receiver completion-recovery interval is 6250 ms: all six default sender wait intervals plus
a 250 ms scheduling margin. During this fixed window, an identical FINISH from
the same endpoint and transfer UUID receives the cached FINISH_ACK. The file is
not rewritten or rehashed, and unrelated traffic does not extend the deadline.
The receiver command returns and publishes a verified staging file after this
window expires.

## Receiver metrics observation rules

Receiver DATA measurements begin only after a valid START is accepted. A DATA
datagram increments `packets_received` after it is decoded and its sender
endpoint, message type, transfer UUID, exact framing, sequence range, CRC and
expected payload length are validated.
This includes valid duplicate and ahead-of-gap arrivals. Malformed, corrupt,
wrong-peer, wrong-transfer, and control datagrams do not increment it.

`packets_duplicated` increments only for the sequence tracker's `DUPLICATE`
outcome. `payload_bytes_delivered` increments by the actual payload length only
after an `ACCEPTED` payload write completes. Partial failures retain previously
written bytes. SHA-256 match or mismatch is recorded as receiver integrity
evidence only when the comparison runs; timeouts before comparison leave it
unavailable. Receiver-local success remains separate from sender-confirmed
`transfer_success`, which a standalone receiver cannot observe.

When enabled, `RECEIVE_DELIVERY_V1` applies random DATA loss at receiver delivery
and fixed per-direction delay to receiver DATA and sender ACK delivery. Those
datagrams have already been emitted: shim drops remain in emission bytes but do
not increment engine `packets_received`. Control handshakes are unaffected.
See [controlled impairment observations](LOGGING.md#controlled-impairment-observations)
for scope, event names, configuration and saved passing evidence.

The existing empty-file convention sends one zero-length DATA packet, so an
empty successful run observes zero delivered bytes while still observing that
DATA arrival.

## RTT and emitted UDP payload measurements

The sender timestamps each original DATA sequence immediately after its socket
send succeeds. A valid ACK that advances the window by exactly one sequence may
close one RTT sample when that sequence was never retransmitted. Duplicate,
wrong-peer, wrong-UUID, corrupt, malformed, and out-of-range ACKs cannot produce
samples. Progress covering multiple sequences is excluded because one
cumulative ACK does not provide an unambiguous independent RTT for each DATA
packet. RTT uses the sender's monotonic clock, milliseconds, arithmetic mean,
and nearest-rank 95th percentile; it does not adjust the DATA timeout.

Endpoint UDP emission accounting occurs after `DatagramSocket.send` succeeds
and adds the actual encoded datagram length. Every successful DATA, ACK, START,
START_ACK, FINISH, FINISH_ACK, retry, and recovery resend is counted separately.
The boundary excludes transport and network headers and does not imply remote
delivery. Sender and receiver totals remain endpoint-local and provisional
until each engine reaches its terminal boundary. A complete combined emission
total and protocol overhead are produced only by UUID-based reconciliation of
finalized endpoint records and their supporting run-state and JSONL evidence.
Missing or inconsistent endpoint evidence prevents a verified combined export.

## Live endpoint snapshots

Each endpoint exposes an atomic immutable snapshot of its context, current
metrics, local emission accounting, lifecycle, and supporting progress. Sender
states cover START negotiation, DATA transfer, FINISH confirmation, and its
terminal decision. Receiver states cover initial START waiting, DATA reception,
verification, FINISH_ACK recovery, and its local terminal result. Receiver
success does not populate sender-confirmed `transfer_success`.

Active sender elapsed time uses the same monotonic origin as the finalized
transfer duration. Exact sender ACK progress sums the original encoded DATA
payload lengths for newly acknowledged sequences, including a short final
chunk or zero-length empty-file chunk. This supporting value and its ACK-based
rate remain distinct from receiver-written `payload_bytes_delivered` and the
reconciled delivered-byte throughput. Snapshots are provisional until that
endpoint reaches `SUCCEEDED` or `FAILED`; one endpoint becoming terminal does
not finalize the other endpoint or any combined metric.

## Persistent event evidence

When enabled, sender and receiver record their own ordered schema-v1 JSONL event
streams. Protocol attempts are logged before socket sends; emission events are
logged only after `DatagramSocket.send` succeeds and carry the actual encoded UDP
payload length. ACK acceptance, receiver sequence outcomes, payload writes,
integrity comparison, and terminal status come from the same established engine
decisions that update metrics.

Endpoint event sequences and monotonic timestamps are local. Receiver events before
a validated START have no protocol UUID, and receiver-local completion remains
separate from sender-confirmed success. `run-state.json` distinguishes recording,
finalized success, finalized failure, incomplete, and logging-failed evidence. Each
handled terminal endpoint persists its final metrics snapshot after closing the JSONL
writer and before marking run state finalized.

## Final metrics reconciliation

`MetricsExporter.reconcile(senderRunDirectory, receiverRunDirectory)` accepts two
explicit directories. The actual protocol UUID is the primary association key; local
run IDs remain distinct. Reconciliation also checks roles, REAL provenance, run-state
identity, event ordering and terminal status, file and configuration compatibility,
receiver peer attribution, and event-derived counters and emitted bytes. Filename,
timestamp, file size, experiment label, and directory order never establish identity.

The final transfer duration and RTT statistics come from the sender's persisted
measurement. Delivered bytes and integrity come from the receiver. Combined UDP
payload emission is the overflow-safe sum of the two complete endpoint totals.
Protocol overhead uses:

```text
protocol_overhead_bytes = udp_payload_bytes_emitted - payload_bytes_delivered
protocol_overhead_ratio = protocol_overhead_bytes / udp_payload_bytes_emitted
```

The UUID-scoped output contains one UTF-8 JSON object in `summary.jsonl` and a
`manifest.json` linking both endpoint records, event logs, and run states. Publication
uses a temporary sibling directory followed by an atomic move when supported. An
existing UUID result is a collision error and is never replaced.

The earlier null-RTT/retransmission-ratio reason propagation finding is repaired
and covered by saved focused validation. See
[LOGGING](LOGGING.md#missing-reason-propagation-repair). Null values still require
reasons; a missing measurement is never treated as an observed zero.

## Byte layout diagram

```
Offset:  0      1                              17     21          23              27         (variable)
Field:  [type] [transferId — 16 bytes         ] [seqNum] [payloadLen] [crc32      ] [payload...]
```
