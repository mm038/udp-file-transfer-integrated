# Metrics Summary - Revised Set and Integration Notes

Prepared September 20, 2026 from Person 2's `Metrics_Summary.docx` and the assignment PDF (minimum metrics on pages 2-3). The user has confirmed that Person 2 agrees to the complete metric set, including the supporting fields below. Use this accepted scope for future implementation. Definitions marked proposed and open integration notes distinguish design choices from instructor requirements and identify details to settle in the concrete producer handoff; they do not reopen field-scope approval. Person 2 owns measurement and logging, which have not yet been supplied. No milestone 7 implementation is started by this document.

## 1. What needs to be added

The original summary covers several required metrics, but does not explicitly supply all the assignment's minimum evidence. These are the additional measurements:

| Additional measurement | Proposed field(s) | Why the existing fields do not cover it |
| --- | --- | --- |
| Unique payload bytes delivered | `payload_bytes_delivered` | Original file size does not establish delivery, particularly on failure. |
| DATA packets acknowledged | `packets_acked` | `acks_received` counts ACK arrivals. A cumulative ACK can acknowledge multiple DATA packets, and repeated ACKs must not add progress. |
| DATA timeout occurrences | `packets_timed_out` | A resend count is not a timeout count; one Go-Back-N timeout can trigger several resends. |
| Duplicate DATA packets | `packets_duplicated` | Total arrivals including duplicates do not give the duplicate count separately. |
| Retransmission ratio | `retransmission_ratio` | Counts are proposed, but their ratio and denominator need an explicit definition. |
| Protocol overhead | `protocol_overhead_bytes`, `protocol_overhead_ratio` | DATA counts alone do not supply the byte accounting for headers, ACKs, controls and retransmissions. Bytes plus a fraction are proposed representations of this requirement. |
| RTT mean and a percentile, where applicable | `rtt_mean_ms`, `rtt_p95_ms` | Configured delay is not observed RTT. P95 is our proposed percentile, not a percentile mandated by the assignment. |

Also record the full transfer configuration and impairment scenario. The assignment separately requires a machine-readable event log; one summary row per transfer does not replace that log. Supporting fields such as RTT sample count, byte totals, identity mapping and schema versions make these measurements interpretable, but their exact names are team choices.

## 2. Common conventions

- Keep the original field names where possible. Use the definitions and formulas below as the working baseline for the accepted metric set. Confirm unresolved implementation details against the concrete producer handoff; the assignment does not mandate these JSON names or exact formulas.
- Use seconds for `transfer_time_sec`, decimal megabits/second for `throughput_mbps`, milliseconds for delay/RTT, and bytes for payload/accounting. Fractions such as `retransmission_ratio` are between 0 and 1; `packet_loss_rate` is a configured percentage between 0 and 100.
- Missing or inapplicable measurements are `null` with an entry in `unavailable_reasons`. Zero means an observed zero. Optional metadata such as an inapplicable seed, and `failure_reason` on success, may be null under their own definitions. Configured settings must come from the actual run configuration.
- Counters below are DATA-specific unless explicitly stated otherwise. Do not mix DATA, ACK and control traffic into one counter; retain endpoint/message-type details in the event log.
- A proposed summary contains one finalized record per terminal run, including failures, in JSONL. CSV is also possible after agreeing types and null representation. The event log is a separate artifact.

## 3. Revised field definitions

### Identity, outcome, time and payload

| Field | Type | Definition |
| --- | --- | --- |
| `experiment_id` | string | Original external experiment/run label. Agree its verified association with application run/transfer IDs; do not assume a label such as EXP001 is a UUID. |
| `file_size_bytes` | integer | Original input file size in bytes. |
| `payload_bytes_delivered` | integer/null | Unique payload bytes successfully accepted and written at the receiver, excluding duplicate/retransmitted copies. This does not by itself prove final SHA-256 integrity. Requires receiver evidence. |
| `transfer_time_sec` | number/null | Proposed interval: sender's first START send attempt to its terminal success/failure decision, measured with a monotonic clock. Excludes GPT interpretation/explanation time. Confirm these boundaries with the engine/metrics owners. |
| `throughput_mbps` | number/null | Useful delivered-payload rate: `payload_bytes_delivered * 8 / transfer_time_sec / 1000000`, when both inputs are observed and duration is positive. On failure label this failed-run delivered-payload rate (possibly partial), not successful-file throughput. Null when inputs are unavailable or duration is zero. |
| `transfer_success` | boolean | Sender-confirmed successful protocol completion. Receiver-only completion must not override missing sender confirmation. |
| `integrity_verified` | boolean/null | SHA-256 comparison result from the identified endpoint: true means match, false means checked mismatch, null means no available check. Record its source in the linked evidence/metadata. |
| `failure_reason` | string/null | Additional supporting field: recorded reason for failure; null on success. Do not infer a cause from unrelated counters. |

Preserve partial receiver delivery and receiver verification separately when the sender lacks final confirmation. The Person 3 interface still requires confirmed success and verified integrity before claiming completion. Preserve numeric precision; document export rounding (the example below shows Mbps to two decimal places).

### Packet activity

| Field | Type | Definition |
| --- | --- | --- |
| `packets_sent` | integer/null | Sender DATA send attempts including retransmissions. Proposed counting point: before the outgoing DATA impairment simulator. Attempts suppressed by that simulator remain attempts. |
| `packets_received` | integer/null | DATA arrivals at the receiver attributable to the run, including duplicates and out-of-order arrivals. Log validation/acceptance separately; an arrival is not necessarily accepted payload. |
| `packets_dropped` | integer/null | DATA attempts dropped by the identified simulator. This is simulator-observed loss, not necessarily all network loss. |
| `retransmissions` | integer/null | Sender DATA resend attempts after a sequence's first attempt. A Go-Back-N round may add multiple resends. This is not the engine's resetting consecutive-round retry counter. |
| `acks_received` | integer/null | DATA-ACK arrivals at the sender attributable to the run, including repeats; exclude START_ACK and FINISH_ACK. Log ACK validation separately. |
| `packets_acked` | integer/null | Distinct DATA sequence numbers newly confirmed by valid cumulative ACK progress for the expected peer/transfer. Count each once; repeated ACK arrivals do not increment this count. |
| `packets_timed_out` | integer/null | Detected DATA timeout occurrences, counted once per expired send attempt that the recovery logic detects. For the current oldest-packet Go-Back-N trigger, count the detected trigger, not every packet resent with it. Include a detected timeout that ends in retry-limit failure. Exclude socket polling timeouts without a DATA deadline expiry. |
| `packets_duplicated` | integer/null | Valid receiver DATA arrivals whose sequence was already accepted, matching the current receiver's DUPLICATE outcome. Ahead-of-gap discarded packets are a separate category, not accepted duplicates. |
| `retransmission_ratio` | number/null | Proposed DATA attempt fraction: `retransmissions / packets_sent`. Null if attempts are zero or either count is unavailable. A ratio of 0.10 means 10% of attempts were resends; it does not mean 10% packet loss. |

Agree counting locations before adding hooks. Do not assume `packets_sent - packets_received = packets_dropped`, or that retransmissions equal drops. Delay, duplication, out-of-order discard, ACK loss and incomplete observations can break those equalities. Keep optional out-of-order-discard, invalid-packet and control-retry counts separate when available.

### Overhead and RTT

| Field | Type | Definition |
| --- | --- | --- |
| `udp_payload_bytes_emitted` | integer/null | Supporting total: actual emitted UDP payload bytes from both endpoints for this run, counting each emission once. Include encoded DATA headers/payloads, ACKs, controls and retransmissions. Exclude attempts suppressed before socket send and exclude IP/UDP/link headers. |
| `protocol_overhead_bytes` | integer/null | Proposed application-protocol overhead: `udp_payload_bytes_emitted - payload_bytes_delivered`. Requires complete compatible byte evidence from both endpoints and the receiver. On failure this includes traffic that produced no unique delivered payload. |
| `protocol_overhead_ratio` | number/null | Proposed overhead fraction: `protocol_overhead_bytes / udp_payload_bytes_emitted`. Null for zero emitted bytes or incomplete accounting. Document this denominator. |
| `rtt_sample_count` | integer/null | Supporting count of valid RTT samples. Zero means sampling ran and produced no usable samples; null means sampling/count evidence is unavailable. |
| `rtt_mean_ms` | number/null | Arithmetic mean of valid sender-observed DATA/ACK RTT samples. Null when there are no usable samples or sampling is unavailable. |
| `rtt_p95_ms` | number/null | Proposed 95th percentile of the same samples. Proposed nearest-rank rule: sort samples and select one-based rank `ceil(0.95 * n)`. Null when there are no usable samples or sampling is unavailable. |

RTT sampling must have a documented send-to-ACK association. Exclude samples ambiguous because of retransmission; define how cumulative ACKs select samples. The assignment asks for mean and a percentile where applicable, not necessarily p95 or this percentile algorithm. Never infer RTT from configured delay or total transfer time.

The overhead formula above is our working UDP-payload accounting boundary, not an instructor-prescribed formula. Its implementation needs complete supporting logs and matching endpoint accounting. Do not count sender emission and receiver observation of the same datagram as two emissions, or estimate overhead from packet counts alone.

### Configuration and scenario

| Field | Type | Definition |
| --- | --- | --- |
| `chunk_size_bytes` | integer | Effective accepted DATA payload chunk size. Current engine maximum: 1024. |
| `window_bytes_requested` | integer | Resolved requested byte budget, including the documented Java default when omitted by the user. Retain it to explain byte-to-packet rounding; do not describe a default as an explicit user choice. |
| `window_packets` | integer | Effective sender window in DATA packets. |
| `timeout_ms` | integer | Configured DATA retransmission timeout. |
| `retry_limit` | integer | Maximum consecutive retransmission rounds without progress; not total resends or retries per packet. |
| `packet_loss_rate` | number | Original field: configured simulated DATA loss percentage. Display as configured DATA loss (%), never observed loss rate. |
| `delay_ms` | number | Original field: configured simulator delay in milliseconds. Record affected direction/message types and fixed/distribution semantics in the scenario. |
| `scenario` | string | Scenario identifier linked to the full impairment configuration, including direction, message types, delay variation/jitter and simulator placement when used. |
| `impairment_seed` | integer/null | Recommended reproducibility field when seeded random impairment is used; null when not applicable. |

### Supporting metadata for safe integration

Agree a summary or linked manifest carrying `schema_version`, `metric_definition_version`, `evidence_source` (REAL/SYNTHETIC), capture/finalization time, and a verified mapping between `experiment_id`, application `run_id`/`transfer_id`, and nullable `protocol_transfer_id`. Also retain endpoint attribution, file identity, integrity evidence source, and per-field `unavailable_reasons`. These are integration recommendations, not exact fields demanded by the assignment.

Agreement on the metric set does not establish those actual mappings or supply measured evidence. Real explanations stay unavailable until a reviewed provider can verify them. Non-numeric identity/outcome metadata must not be disguised as numerical measurements.

## 4. Packet-count clarification

The previous review's 10,240 figure refers to ORIGINAL DATA chunks, not all packets in the exchange:

```text
10 MiB = 10,485,760 bytes
10,485,760 / 1,024 = 10,240 original DATA chunks
DATA send attempts = original attempts + retransmission attempts
```

At a smaller chunk size, more original chunks are required. ACKs and START/FINISH control messages are additional datagrams, outside `packets_sent` as defined here.

The original illustration had 1,200 DATA attempts including 120 resends, leaving 1,080 original attempts. Those cannot carry the stated 10 MiB under the current payload limit. If preserving 120 resends and a successful 10 MiB transfer with 1,024-byte chunks, the DATA-attempt count would instead be 10,360. That arithmetic alone does not determine arrivals, drops, duplicate packets, timeouts, ACK counts or duration; those require actual evidence or a fully specified synthetic scenario.

This is a correction to an explicitly illustrative example, not a finding that Person 2's unimplemented measurements are wrong. The revised complete example below uses a simpler lossless baseline instead of asserting unobserved details of the original loss scenario.

## 5. Corrected illustrative summary

SYNTHETIC FORMAT EXAMPLE ONLY - NOT MEASURED RESULTS. Assume 1,024-byte chunks, all DATA arriving in order, every DATA-ACK delivered, no corruption, loss, duplicate DATA or timeouts, and successful final verification. The duration is an authored example value. Configured loss and delay are changed to zero to describe this baseline; it is not a correction of measured experiment data.

Overhead and RTT stay null because this illustration supplies neither complete emitted-byte observations nor RTT samples. Those nulls demonstrate unavailable-evidence handling, not completion of the assignment's measurement work. The full identity manifest described above is omitted from this compact example; this is not an import-ready real summary.

```json
{
  "experiment_id": "SYNTHETIC-BASELINE-001",
  "evidence_source": "SYNTHETIC",
  "file_size_bytes": 10485760,
  "payload_bytes_delivered": 10485760,
  "transfer_time_sec": 8.5,
  "throughput_mbps": 9.87,
  "packet_loss_rate": 0.0,
  "delay_ms": 0,
  "packets_sent": 10240,
  "packets_received": 10240,
  "packets_dropped": 0,
  "retransmissions": 0,
  "acks_received": 10240,
  "packets_acked": 10240,
  "packets_timed_out": 0,
  "packets_duplicated": 0,
  "retransmission_ratio": 0.0,
  "udp_payload_bytes_emitted": null,
  "protocol_overhead_bytes": null,
  "protocol_overhead_ratio": null,
  "rtt_sample_count": null,
  "rtt_mean_ms": null,
  "rtt_p95_ms": null,
  "chunk_size_bytes": 1024,
  "window_bytes_requested": 65536,
  "window_packets": 64,
  "timeout_ms": 200,
  "retry_limit": 5,
  "scenario": "synthetic_lossless_in_order_baseline",
  "impairment_seed": null,
  "transfer_success": true,
  "integrity_verified": true,
  "failure_reason": null,
  "unavailable_reasons": {
    "udp_payload_bytes_emitted": "Complete endpoint emission byte observations are not supplied by this illustration.",
    "protocol_overhead_bytes": "Complete UDP-payload byte accounting is unavailable.",
    "protocol_overhead_ratio": "Complete UDP-payload byte accounting is unavailable.",
    "rtt_sample_count": "RTT sampling evidence is not supplied by this illustration.",
    "rtt_mean_ms": "RTT sampling evidence is not supplied by this illustration.",
    "rtt_p95_ms": "RTT sampling evidence is not supplied by this illustration."
  }
}
```

For this illustration, `10485760 * 8 / 8.5 / 1000000 = 9.868950588... Mbps`, shown as 9.87. The equality between ACK arrivals and acknowledged DATA chunks is specific to this baseline; it is not a general cumulative-ACK formula.

## 6. Separate event log and discussion decisions

Keep raw packet/event evidence in CSV or JSONL, distinct from the per-transfer summary. Proposed event fields include verified run/transfer identity, endpoint/direction, monotonic event time and separate UTC timestamp, event type, message type, sequence/cumulative ACK, attempt number, payload/encoded-byte sizes, and validation/acceptance/drop/timeout outcome. These are suggested event fields, not an already agreed logger schema.

Before real integration, obtain the concrete identity association, event/serialization format, output location, version and finalization rules. Check that the producer implements the working timing, counter, failure, overhead, RTT and null definitions above; document any implementation-driven changes rather than silently changing meanings. Confirm which measurements come from the summary and which have linked supporting logs. Metric calculation and logging remain Person 2's work with Person 1's agreed hooks; Person 3 consumes and explains verified evidence. The accepted field set does not need to be approved again.

Milestone 7 remains paused. No source code, tests, engine hooks, logger, metrics calculator or real-summary adapter has been changed for this revision.
