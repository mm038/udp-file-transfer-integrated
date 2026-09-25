# Persistent endpoint event logs

Implementation reference reviewed September 25, 2026 for the integrated working
tree based on commit `985b1a8`, including the verified local repairs. The logging,
reconciliation and real explanation route are connected;
the [current evaluation checkpoint](README.md#current-evaluation-checkpoint)
records completed Sections 3–6 and the remaining Person 4 experiments and team
deliverables. The earlier missing-reason propagation defect is repaired; saved
validation is linked below. Original failure artifacts remain historical evidence.

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

The direct `nettransfer.Main` logging root defaults to `logs` and can be changed
with the Java system property `nettransfer.logsRoot`. The command console instead
derives `<projectRoot>/logs`; it does not read that property.

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
| `impairment_decision_index` | integer/null | Transfer-local simulator decision ID; links a delayed datagram to its later delivery or cancellation. |
| `impairment_delay_ms` | integer/null | Configured fixed receive delay for an impairment decision, not measured RTT. |

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

### Controlled impairment observations

The implemented mechanism is `RECEIVE_DELIVERY_V1`, a receive-side shim enabled
explicitly in each process. It applies seeded random loss to eligible inbound
DATA at the receiver and fixed delay to eligible inbound DATA at the receiver
and ACK at the sender. Controls are unaffected. Eligibility checks the established
peer and transfer UUID, binary framing and CRC; the engines still enforce sequence
and accepted-file payload rules. This is fixed per-direction delay, not jitter or
a measured RTT. Use matching enabled profiles at both endpoints for reconciliation.

The event stream includes `IMPAIRMENT_STARTED`, `IMPAIRMENT_DROPPED`,
`IMPAIRMENT_DELAYED`, `IMPAIRMENT_DELIVERED`, `IMPAIRMENT_CANCELLED`,
`IMPAIRMENT_FAILED` and `IMPAIRMENT_FINISHED`. Decision events are `INBOUND`;
scope start/finish events are `LOCAL`. Cancelled queued datagrams and queue
failures are distinct from configured random DATA drops.

Current counting and configuration rules are:

- `packets_sent` counts sender DATA attempts, including retries, before socket
  send. Receive-side drops have already been emitted and remain in sender UDP
  emission accounting.
- `packets_received` counts DATA reaching the receiver engine and passing peer,
  transfer, framing, sequence, CRC and expected-payload-length validation,
  including valid duplicates and ahead-of-gap arrivals. It excludes shim drops.
- `packets_dropped` is the receiver's observed random DATA-drop count when its
  impairment scope started, including an observed zero. It remains null with a
  reason without that evidence; sender-only scope cannot supply receiver drops.
  Reconciliation takes this field from the receiver for `RECEIVE_DELIVERY_V1`.
- `packet_loss_rate` is configured DATA loss percentage (0–100), and `delay_ms`
  is configured fixed delay per affected direction. `scenario`,
  `impairment_seed` and `impairment_mechanism` identify the actual profile.
  They do not establish observed loss, throughput or RTT. An enabled zero-loss,
  zero-delay profile supplies observations; disabled or unobserved settings must
  not be silently converted from null to zero.
- Endpoint configuration retains sender DATA/window/handshake settings separately
  from receiver initial/inactivity/completion-grace timeouts. A value unavailable
  at that endpoint remains null; the sender's `timeout_ms` is the DATA retry timer.

Saved [focused validation](target/evaluation/controlled-impairment-20260925-134232-676/validation.json)
and [independent transfer checks](target/evaluation/controlled-impairment-20260925-134232-676/independent-transfer-checks.json)
cover baseline, 2% loss, fixed delay, timeout recovery, a larger window and bounded
complete-loss failure. These establish capability, not Person 4's completed matrix.

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

When a receiver first establishes its protocol UUID, the logger atomically refreshes its
still-`RECORDING` run state with that trusted identity. This exposes no partial metrics and makes no
finalization claim; it only lets exact repository lookup distinguish a matching active receiver as
`PENDING` during completion recovery instead of guessing by filename, timestamp, or recency.

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
null and require field-specific entries in `unavailable_reasons`. For example,
`packets_dropped` remains null without active observed drop evidence.

### Missing-reason propagation repair

The September 25 defect is repaired in the working tree. Reconciliation preserves
the supplying endpoint's reasons for null RTT count/mean/p95 and other copied
metrics, and supplies a reason when the retransmission ratio has no valid
denominator. The strict REAL provider still rejects genuinely missing reasons.
Saved [repair validation](target/evaluation/metrics-null-reasons-20260925-131319-640/validation.json)
records 74 passing focused tests, including reconciliation/provider paths, with
the pre-fix failures preserved separately.

After a successful original DATA emission starts sampling, zero eligible samples
means count zero and null mean/p95 with reasons. Without an original DATA emission,
sampling/count is unavailable. An empty file sends one zero-length DATA packet;
empty size alone therefore does not imply zero attempts or unavailable RTT.

Sender-confirmed `transfer_success` and receiver-observed `integrity_verified` remain
separate. A handled failed transfer can retain finalized partial measurements and be
reconciled when both evidence sets are complete; its failed outcome remains explicit. A
crash, active `RECORDING` state, missing terminal event, or logging failure is incomplete
evidence and cannot produce a verified complete summary.

## Persisted evidence lookup

`PersistedEvidenceRepository` is the application-facing reader for finalized evidence beneath one
explicitly configured, trusted logging root. `lookup(senderRunId, applicationTransferId)` first
selects the exact sender run and verifies both identities. It then uses only the sender record's
validated protocol UUID to find an unambiguous receiver. It never associates runs by filename,
timestamp, directory order, or recency. `lookupReceiver(protocolUuid)` and
`lookupReconciled(protocolUuid)` provide exact receiver and finalized-summary retrieval.

Every endpoint is passed through `MetricsExporter.readValidatedEndpoint`. Existing summaries are
passed through `MetricsExporter.readValidatedReconciled`, which revalidates their manifest, source
references, source endpoint records, identities, and calculated contents before reuse. If both
finalized endpoints exist and no summary has been published, the repository invokes the existing
`MetricsExporter.reconcile` API and then validates the result. Original endpoint evidence is never
modified.

Lookup results are typed as `AVAILABLE`, `PENDING`, `INCOMPLETE`, `UNAVAILABLE`, or `REJECTED`.
Only `AVAILABLE` carries validated evidence. A finalized sender failure may be available as
sender-only evidence when no finalized receiver counterpart exists; it is not labeled reconciled.
A finalized receiver-local integrity success can be reconciled with a sender FINISH-handshake
failure without changing the sender's failed outcome. Recording, interrupted, missing, ambiguous,
synthetic, malformed, conflicting, or unsafe evidence remains explicitly distinct.

All lookup-derived paths stay beneath the trusted root. Traversal, symbolic evidence paths,
external source references, duplicate candidates, and application/protocol identity conflicts are
rejected. Natural-language input and GPT output are not evidence paths and are not accepted by this
API.

`RealMetricsSummaryProvider` is the Stage 4 adapter from this repository to the
explanation evidence model. It preserves original endpoint records; repository
lookup can create a new reconciled summary. Its typed lookup requires the trusted application ID, sender run ID,
and protocol UUID and preserves repository outcomes as `AVAILABLE`, `PENDING`, `INCOMPLETE`,
`UNAVAILABLE`, or `REJECTED`. Only `AVAILABLE` is converted. The conversion explicitly copies the
26 supported numeric metrics, their units, observed/configured classification, and existing
unavailable reasons; it does not calculate missing values. Provenance, endpoint scope, terminal
outcomes, identities, schema versions, receiver-local integrity, and validated source references
remain typed metadata outside the numeric field list.

The explanation HTTP request contains these projected fields and metadata with
source-reference identifiers. It does not include the raw event-log contents or
their timeline, nor all persisted configuration/supporting observations.

`ExplanationFlow` can now consume this provider through its typed trusted-selection lookup. A REAL
summary reaches the explanation client only when it is `AVAILABLE`, REAL, complete, final,
identity-matched, on a supported schema and definition version, and scoped as `SENDER_FINAL`,
`RECEIVER_FINAL`, or `RECONCILED`. The ordinary provider supplies sender-final or
reconciled evidence; receiver-final is recognized by the wider contract but is
not produced by this provider. Typed `PENDING`, `INCOMPLETE`, `UNAVAILABLE`, and `REJECTED`
results retain their reason and do not invoke the client. Existing synthetic fixtures retain
`SYNTHETIC_FIXTURE` scope and their fixture-only definition version; neither evidence source can be
used as a fallback for the other.

`TransferCliMain` installs this provider in `ExplanationFlow` and gives both the repository and
`RealTransferService` the same Java-configured `<projectRoot>/logs` root. The path is derived from
validated startup configuration, not a GPT proposal or natural-language input. CLI status remains
a direct view of the retained sender `LiveMetricsSnapshot`; finalized explanation evidence remains
a separate repository lookup and validation step.

### Manifest schema

`manifest.json` schema version `1` records the metric-definition version, protocol UUID,
optional experiment/application identity, both local run IDs, relative references to both
endpoint records, event logs, run states, and the summary, REAL provenance, verified
identity status, complete evidence status, FINAL publication status, and finalization
timestamp. It contains no unnecessary absolute local paths.

## Consumer rules

### Separate API evaluation capture

The console's `-Dnettransfer.evaluation.record=true` option writes a fresh
`target/evaluation/llm-...` directory with safe request/response bodies, available
usage, HTTP-attempt timing/failure observations and Java decisions. This capture
is separate from endpoint metric logs; recording completion does not establish
model correctness. Missing response bodies or usage remain unavailable.
Saved [capture validation](target/evaluation/api-evidence-capture-20260925-151307-190/validation.json)
records 341 passing focused tests and a packaged startup check. The completed
live checks and retained prose limitations are listed in the
[README checkpoint](README.md#current-evaluation-checkpoint).

### Transfer evidence consumers

Consumers should read only independently parseable JSONL lines and consult `run-state.json` before
treating a local log as complete. Sender and receiver records can have different run IDs and wall
clocks. They are not joined by filename, timestamp, or directory placement.

The finalized artifacts form a read-only evidence contract for consumers including the command
console explanation flow. A consumer can
locate one verified protocol transfer, distinguish sender confirmation from receiver
integrity, inspect unavailable reasons, and follow the manifest to the original evidence.
Metrics export itself does not implement GPT calls, prompt construction, impairment simulation,
or natural-language interpretation. Only an `AVAILABLE` provider result may cross into the
separate explanation client; all other typed results remain local Java status and reason output.

For later end-to-end verification with direct Main, start a receiver and sender
with a fresh source and destination plus a shared `nettransfer.logsRoot`. For a
console sender, use its `<projectRoot>/logs` root for the receiver as well.
Confirm both processes terminate,
compare source and destination SHA-256 hashes, inspect both finalized run states and
endpoint records, invoke `reconcile`, then parse the summary and manifest and confirm
every referenced relative path resolves.
