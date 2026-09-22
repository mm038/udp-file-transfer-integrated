# Persistent endpoint event logs

The Java engines can persist real protocol and measurement observations as JSON Lines. Logging is
enabled by the CLI. Programmatic callers enable it before starting an engine:

```java
EventLogger logger = sender.enableEventLogging(Path.of("logs"));
TransferResult result = sender.sendFile(path);
```

`ReceiverEngine` exposes the same API. Existing engine constructors remain valid; direct callers
that do not enable logging retain the previous in-memory-only behavior.

## Directory structure

Each endpoint process owns an independent local run directory:

```text
logs/
└── standalone/                    # or a safe experiment component
    ├── <sender-local-run>/
    │   ├── events-sender.jsonl
    │   ├── run-state.json
    │   └── endpoint-sender.json
    └── <receiver-local-run>/
        ├── events-receiver.jsonl
        ├── run-state.json
        └── endpoint-receiver.json
```

The original experiment and run IDs are retained inside every event. IDs made only of letters,
digits, `.`, `_`, and `-` are used directly as directory components. Other IDs are mapped to a
SHA-256-derived safe component, preventing absolute paths and traversal. Creating a run directory
is exclusive: an existing directory causes startup to fail rather than truncating evidence.

The CLI root defaults to `logs` and can be changed with the Java system property
`nettransfer.logsRoot`.

## JSONL schema version 1

Each UTF-8 line is one complete compact JSON object. Optional fields are present as JSON `null`.

| Field | Type | Meaning |
|---|---|---|
| `schema_version` | string | Event schema, currently `1`. |
| `event_id` | string | Local run ID plus local event sequence. |
| `event_sequence` | integer | Zero-based order within one endpoint run. |
| `timestamp_utc` | string | ISO 8601 UTC wall-clock observation time. |
| `monotonic_time_nanos` | integer | Local `System.nanoTime()` observation; never compare across JVMs. |
| `experiment_id` | string/null | Trusted experiment label from `TransferContext`. |
| `run_id` | string | Local application run ID. |
| `application_transfer_id` | string/null | Trusted application transfer identity. |
| `protocol_transfer_id` | UUID string/null | Actual START UUID once locally established. |
| `endpoint` | `SENDER`/`RECEIVER` | Endpoint that observed the event. |
| `event_type` | string | Stable event name listed below. |
| `direction` | `OUTBOUND`/`INBOUND`/`LOCAL` | Direction relative to the observing endpoint. |
| `message_type` | string/null | Protocol message involved. |
| `sequence_number`, `ack_number` | integer/null | DATA or cumulative ACK sequence when observed. |
| `attempt_number` | integer/null | Attempt number only when directly known. |
| `payload_bytes` | integer/null | DATA payload or successful write length. |
| `encoded_udp_payload_bytes` | integer/null | Actual encoded datagram length at successful send. |
| `validation_result` | string/null | Existing engine validation outcome. |
| `sequence_outcome` | string/null | Receiver sequence tracker outcome. |
| `event_outcome` | string/null | Event-specific observed result. |
| `failure_reason` | string/null | Available failure category or diagnostic. |
| `newly_acknowledged_packets` | integer/null | Actual cumulative window progress. |
| `retransmission` | boolean/null | Whether a DATA transmission is a retry. |
| `integrity_verified` | boolean/null | Actual SHA-256 comparison result when performed. |

Logs never contain DATA payload contents, file contents, authentication data, or unnecessary
absolute file paths.

## Event categories

Sender events cover START attempts and emissions, START_ACK outcomes, DATA attempts and successful
emissions, retransmissions, attributable ACK arrivals and validation decisions, cumulative ACK
progress, actual DATA deadlines, recovery rounds, FINISH attempts and emissions, FINISH_ACK
outcomes, and sender terminal success or failure.

Receiver events cover initial waiting, START arrival and acceptance/rejection, START_ACK attempts
and emissions, validated DATA arrival, validation failure, sequence outcomes, successful payload
writes, ACK attempts and emissions, FINISH arrival, actual integrity comparison, FINISH_ACK
attempts and emissions, duplicate FINISH recovery, completion-grace expiry, and receiver-local
terminal outcome.

An attempt event is recorded before `UdpChannel.send`. Its corresponding emission event is recorded
only after the socket send succeeds, using the encoded length also observed by endpoint emission
metrics. Logging does not decide packet acceptance, ACK progress, retransmission, file writes,
integrity, or terminal success.

## Run state and finalization

`run-state.json` schema version 1 contains the endpoint, local run ID, available protocol UUID,
logging start time, recording state, local terminal outcome, finalization time, event count,
writer closure status, and any logging failure.

Possible states are:

- `RECORDING`: the session was opened and may still be active. A process crash can leave this state.
- `FINALIZED_SUCCESS`: the terminal success event was written, the writer flushed and closed, and
  the state update completed.
- `FINALIZED_FAILED`: the same durable boundary for a handled local transfer failure.
- `INCOMPLETE`: the logger was closed without a terminal outcome.
- `LOGGING_FAILED`: persistence failed and complete evidence cannot be claimed.

State updates use a temporary file and atomic replacement where supported, with a replace fallback
when the filesystem does not support atomic moves. The JSONL writer is synchronized and buffered;
normal finalization flushes all pending records. A hard process or power failure can lose buffered
records, and a missing terminal event does not imply success or failure.

Startup failures such as an inaccessible root or run collision throw `IOException` before transfer
work begins. Runtime serialization, write, flush, or close failures are retained by
`EventLogger.getLoggingFailure()`, reported to standard error, and prevent a finalized evidence
claim. The network transfer result remains its actual protocol result.

## Endpoint metrics records

Handled terminal sender and receiver runs persist `endpoint-sender.json` and
`endpoint-receiver.json` using endpoint-record schema version `1` and
metric-definition version `1`. Each record contains REAL provenance, endpoint and local
run identity, optional experiment and application identity, the protocol UUID, original
filename and file attribution, actual configuration, the final `TransferMetrics`, local
emission and endpoint observations, lifecycle and terminal state, outcome and failure
reason, integrity evidence source, timestamps, and relative event-log and run-state
references.

The terminal engine snapshot is passed to the logger after the terminal event is
recorded. The logger flushes and closes JSONL, writes the endpoint record with a
create-new atomic-file operation, and finally writes finalized run state. This captures
sender duration, RTT, counters, and emissions as well as receiver delivery, integrity,
and completion-grace emissions. A logging or endpoint-record failure cannot produce a
finalized completeness claim.

## Reconciliation and finalized export

Use explicit endpoint run directories:

```powershell
mvn exec:java "-Dexec.mainClass=nettransfer.Main" "-Dexec.args=reconcile logs/standalone/<sender-run-id> logs/standalone/<receiver-run-id>"
```

The equivalent API is `MetricsExporter.reconcile(senderRunDirectory,
receiverRunDirectory)`. It does not scan for the newest run or select the first filename
match. Both directories must share the same explicit experiment scope.

Before calculating values, reconciliation verifies:

- supported endpoint, event, run-state, summary, manifest, and metric-definition versions;
- parseable finalized run states with closed writers and no logging failure;
- REAL provenance, opposite endpoint roles, and matching valid protocol UUIDs;
- consistent local run IDs, endpoint roles, UUIDs, event sequence, and terminal events;
- compatible experiment/application identities when both are present;
- nonconflicting filename, file size, chunk size, and receiver peer attribution;
- event-derived DATA attempts, retransmissions, ACK observations, timeouts, DATA arrivals,
  duplicates, written bytes, and exact local emission totals;
- complete terminal endpoint accounting and basic counter relationships.

Malformed, running, incomplete, ambiguous, or contradictory evidence raises a
machine-readable `EvidenceException` code. Source records remain unchanged and no
verified summary is created.

The successful layout is:

```text
logs/<scope>/
├── <sender-run-id>/...
├── <receiver-run-id>/...
└── reconciled/<protocol-uuid>/
    ├── summary.jsonl
    └── manifest.json
```

The exporter writes both files to a recognizable temporary sibling directory and then
publishes the complete directory with an atomic move where supported. The fallback move
still occurs only after both temporary files close. A pre-existing UUID directory causes
`SUMMARY_COLLISION`; retries never silently replace prior output.

### Final summary schema

`summary.jsonl` is UTF-8 with exactly one JSON object per transfer. Summary schema
version `1` and metric-definition version `1` are centralized in `MetricsSchema`. Its
top-level fields contain provenance, reconciliation and completeness status, protocol
UUID, both local run IDs, verified optional application and experiment IDs, the typed
`metrics` object, relative `source_evidence` references, and finalization time.

The metrics preserve the established names and units: seconds for transfer duration,
decimal Mbps for throughput, milliseconds for RTT, bytes for payload and emissions, and
fractions for ratios. Sender timing, counters, and RTT plus receiver delivered bytes and
integrity retain their original meanings. Complete combined fields use:

```text
udp_payload_bytes_emitted = sender local emitted bytes + receiver local emitted bytes
protocol_overhead_bytes = udp_payload_bytes_emitted - payload_bytes_delivered
protocol_overhead_ratio = protocol_overhead_bytes / udp_payload_bytes_emitted
throughput_mbps = payload_bytes_delivered * 8 / (transfer_time_sec * 1,000,000)
```

`MetricsCalculator` performs throughput, retransmission ratio, and overhead calculations.
RTT values are persisted eligible original-DATA-to-single-ACK sender samples; no
cross-host clock arithmetic or timeout/delay inference is used. Unknown values are JSON
null with field-specific entries in `unavailable_reasons`. For example,
`packets_dropped` remains null without active observed drop evidence.

Sender-confirmed `transfer_success` and receiver-observed `integrity_verified` remain
separate. A handled failed transfer can retain finalized partial measurements and be
reconciled when both evidence sets are complete; its failed outcome remains explicit. A
crash, active `RECORDING` state, missing terminal event, or logging failure is incomplete
evidence and cannot produce a verified complete summary.

### Manifest schema

`manifest.json` schema version `1` records the metric-definition version, protocol UUID,
optional experiment/application identity, both local run IDs, relative references to both
endpoint records, event logs, run states, and the summary, REAL provenance, verified
identity status, complete evidence status, FINAL publication status, and finalization
timestamp. It contains no unnecessary absolute local paths.

## Consumer rules

Consumers should read only independently parseable JSONL lines and consult `run-state.json` before
treating a local log as complete. Sender and receiver records can have different run IDs and wall
clocks. They are not joined by filename, timestamp, or directory placement.

The finalized artifacts form a read-only evidence contract for a future consumer. It can
locate one verified protocol transfer, distinguish sender confirmation from receiver
integrity, inspect unavailable reasons, and follow the manifest to the original evidence.
Metrics export does not implement GPT calls, prompt construction, impairment simulation,
or natural-language interpretation.

For end-to-end verification, start a receiver and sender with a fresh source and
destination plus a shared `nettransfer.logsRoot`, confirm both processes terminate,
compare source and destination SHA-256 hashes, inspect both finalized run states and
endpoint records, invoke `reconcile`, then parse the summary and manifest and confirm
every referenced relative path resolves.
