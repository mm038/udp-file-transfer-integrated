# Person 2 metric proposal: compatibility review

**Current status:** the user has confirmed Person 2's agreement to the complete revised metric and supporting-field set. That scope is accepted for future implementation. The review below records the earlier proposal and its evolution; references to pending scope agreement are historical. Actual logs, concrete event/storage/identity integration and measured verification remain pending. The [checklist](person-3-task-checklist.md) separates independent offline work from those dependencies. No implementation phase is started by this update.

Reviewed September 20, 2026 after milestone 6 was committed as `ab3c4c0` on `person-3/llm-integration`. Source: the separately supplied `Metrics_Summary.docx` in the assignment directory. This note records its proposed fields and their implications; it does not declare a team contract or implemented measurements. The Word document labels its example illustrative, not measured, and proposes one summary record per transfer in JSONL or CSV.

**Follow-up discussion draft:** [Revised Metrics Summary (Word)](Metrics_Summary_Revised.docx) and [editable Markdown](Metrics_Summary_Revised.md) now list the additional assignment measurements, proposed definitions and a corrected synthetic baseline. The source Word file is unchanged. This revision is for the user to discuss with Person 2, not a declaration of agreement or a start of milestone 7.

**No existing implementation needs to change during this pause.** Milestone 6 deliberately separated the summary provider from the engine and kept its metric definitions synthetic. That boundary remains useful. A later real provider will adapt agreed Person 2 outputs into the explanation input. Receiving a field proposal does not unlock real explanations: identity mapping, measurement semantics, real outputs and integration are still pending.

## Proposed fields and future handling

The following definitions come from Person 2's document. The handling column is Person 3's analysis, pending agreement.

| Proposed field | Person 2's definition | Future handling / open point |
| --- | --- | --- |
| `experiment_id` (string) | Unique test run ID | Preserve as an external identifier; agree its verified mapping to application run/transfer UUIDs and the protocol transfer identity. `EXP001` is not a UUID. |
| `file_size_bytes` (integer) | Original file size in bytes | Source metadata; not automatically bytes delivered, especially on failure. |
| `transfer_time_sec` (number) | Elapsed transfer time in seconds | Preserve seconds and fractional precision. Agree start/end events, endpoint and monotonic timing. Do not assume it is already our draft `elapsedMillis`. |
| `throughput_mbps` (number/null) | Useful file bits / elapsed time / 1,000,000 | Decimal megabits per second, not bytes/s. Preserve the supplied value and rounding. Agree failure, partial delivery, zero duration and missing-evidence behavior. |
| `packet_loss_rate` (number) | Configured simulated DATA loss (%) | `CONFIGURED`; display as configured DATA loss percentage. Despite the name, this is not observed packet loss. |
| `delay_ms` (number) | Configured simulated delay in milliseconds | `CONFIGURED`; not measured RTT. Agree which directions/message types are delayed and whether this is fixed delay or a distribution parameter. |
| `packets_sent` (integer) | DATA send attempts including retransmissions | An attempt count, not unique chunks or necessarily emitted datagrams. Agree where it is counted relative to the simulator. |
| `packets_received` (integer) | DATA arrivals including duplicates | Not unique payload delivery. Agree receiving endpoint and whether arrivals are counted before validation or simulated receive-side drops. |
| `packets_dropped` (integer) | DATA packets dropped by simulator | Observed simulator drops; separate from configured loss and other possible network loss. Agree counting location and scope. |
| `retransmissions` (integer) | DATA resend attempts | Lifetime resend attempts, not the engine's resetting consecutive-round retry counter. |
| `acks_received` (integer) | ACK arrivals at sender | Not newly acknowledged chunks or bytes. Agree whether invalid, stale, duplicate or control acknowledgements are included. |
| `transfer_success` (boolean) | Protocol completion status | Keep as typed outcome evidence, reconcile with the selected engine outcome and its endpoint. It cannot independently establish verified completion. |
| `integrity_verified` (boolean/null) | Final SHA-256 result; null if not checked | Preserve true/false/null. Agree endpoint and evidence source; null is not false. Do not silently overwrite the sender outcome using receiver-only verification. |

Ten numerical fields fit the existing `RecordedSummary.Field` shape, which carries value, unit, definition, kind and missing reason. Field names in the old synthetic fixtures are illustrative; they do not force Person 2 to rename her outputs. No fixture or source metric was renamed in this review.

The three nonnumeric fields need explicit handling in the future adapter. `experiment_id` belongs in identity metadata, and the two booleans belong in outcome handling, not artificial 0/1 metrics. `ExplanationRequest` already receives a typed state/integrity outcome, but the real adapter will need to preserve and reconcile the producer's meanings. The current `TransferSummary` requires verified integrity before `COMPLETED`; retain that rule. Receiver verification can coexist with an unconfirmed/failed sender result when final confirmation is missing.

## What the current code continues to do

- `SummaryProvider` is an appropriate read-only integration point. The current `SyntheticSummaryProvider` remains exclusively for fixtures; it will not become a real file loader by relabelling its entries.
- `ExplanationFlow` continues returning `EVIDENCE_UNAVAILABLE` for REAL selections before consulting a provider/client. Its fixture-version check stays in place. A future integration must deliberately support an agreed real schema and identity association while preserving rejection of mismatched evidence.
- `TransferMetrics` remains the small draft status contract. The proposal is a terminal summary, not an observer or live-progress API. It supplies neither unique ACKed bytes nor the engine's chunk count. Packet/ACK counts must not be substituted for those fields, and seconds must not be truncated into integer milliseconds without agreed semantics.
- The existing engine, validator, command interpreter, immutable evidence, numerical citation checks, missing-value handling and separation of analysis from execution remain applicable.

## Decisions to settle before real integration

1. **Identity and provenance:** establish a trustworthy association between `experiment_id`, application run/transfer IDs and protocol identity. Do not join on filename, latest record, coincident timestamps, or a generated hash of `EXP001`. Agree schema/metric-definition versions, capture/finalization time, endpoint ownership and real versus synthetic provenance. These can be in a linked manifest or envelope rather than all being new numerical fields.
2. **Timing and outcomes:** define the transfer interval and exclude GPT time. Decide what throughput means for failed or unverified runs; original file size alone cannot supply partial delivered bytes. Retain failure reasons and distinguish unchecked integrity from a failed SHA-256 check. Specify zero-duration and zero-byte cases.
3. **Counter scope and missing evidence:** agree DATA/ACK counting points, duplicates, validation and simulator placement. Do not assume `sent - received = dropped`, drops equal retransmissions, or configured loss equals observed loss. Only throughput and integrity are explicitly nullable in this proposal; settle absent observations and missing reasons for other fields rather than defaulting them to zero.
4. **Storage and lifecycle:** choose the producer's actual format/location, unique-record rules and when a record is complete/readable, including failures. JSONL is a reasonable first integration choice because it preserves numbers, booleans and nulls directly; this is a recommendation, not agreement. If CSV is selected, explicitly define types, decimal representation and null versus empty values. The proposed JSONL rows are transfer summaries; an event log is a separate artifact even if it also uses JSONL.

Person 2 retains ownership of counters, timing, logging and metric calculations. Person 3 will validate and present their agreed outputs. No parser, metric calculation, observer or protocol change is implemented in this pause.

## Assignment coverage still to coordinate

The assignment PDF's minimum evidence is broader than this proposed summary. It includes payload bytes delivered, acknowledged/timed-out/duplicated packet counts, retransmission ratio, protocol overhead, configured parameters/scenario, a machine-readable event log, and sampled RTT with mean and percentile latency where applicable. These are not all supplied by this document. In this cumulative-ACK engine, `acks_received` cannot substitute for acknowledged DATA-packet count, and DATA arrivals including duplicates do not isolate the duplicate count. Missing evidence may be added to a summary or made available through agreed linked evidence; this review does not assume Person 2 has committed to an implementation for it.

The existing handoff proposes particular definitions and additions such as p95, a specific overhead accounting boundary, seeds and version metadata. Those remain team design proposals. The PDF specifies a percentile where applicable, not p95 specifically; it does not prescribe our exact overhead formula or a particular jitter scalar. Preserve required assignment coverage without treating our earlier recommendations as already agreed.

In particular, a retransmission ratio still needs an agreed denominator and zero-denominator behavior even though counts are now proposed. Overhead cannot be recovered from DATA counts alone without agreed byte accounting and relevant traffic evidence. RTT cannot be inferred from `delay_ms`. Live progress needs observation hooks independently of a final summary.

## Check of the illustrative example

The example's throughput arithmetic is consistent: `10,485,760 * 8 / 8.5 / 1,000,000` is approximately `9.86895`, which rounds to `9.87 Mbps`.

Its packet counts are not a feasible successful transfer for the current engine. With at most 1,024 payload bytes per DATA packet (`Packet.MAX_PAYLOAD_SIZE`), a 10 MiB file requires at least 10,240 original DATA sends. The illustration has 1,200 attempts including 120 retransmissions, leaving only 1,080 original attempts. Since the document explicitly calls this an illustration, this is a fixture-consistency point, not evidence of an implementation defect. Do not copy it unchanged into a successful-transfer regression fixture or describe it as measured results.

To make that count precise: 10,240 is the original DATA-chunk count at exactly 1,024 bytes per chunk, not total datagrams or retry-inclusive DATA attempts. Keeping 120 resends would require 10,360 DATA attempts. ACK/control datagrams are additional, and arrival/drop/timeout counts cannot be inferred from that arithmetic. The revised summary therefore uses an explicitly synthetic, lossless, in-order baseline with 10,240 DATA attempts and zero resends, rather than inventing a complete impaired-run history.

## Effect on the following milestones

| Milestone | Planning adjustment only |
| --- | --- |
| 6, remaining dependencies | Keep the completed offline boundary. Record this proposal as input to the future real adapter; real summaries, identity/schema agreement and live explanation transport remain pending. |
| 7, offline regression | When separately authorized, audit existing coverage first. Add targeted synthetic compatibility cases for the agreed producer contract: identity mapping, seconds/Mbps precision, configured versus observed fields, boolean/null outcomes, missing data and inconsistent records. Do not assume receiving this proposal authorizes or completes real parser/provider work. |
| 8, live model evaluation | Continue distinguishing synthetic evaluation from real evidence. Use agreed field names/units and test misleading-loss-name, missing-evidence and outcome questions. The separate explanation HTTP transport and its offline checks must exist before live explanation evaluation. |
| 9, real integration | Begin with the identity/definition/storage decisions above and actual producer outputs. Add the real provider/outcome mapping, verify both success and failure evidence, then integrate observations and experiments. Keep missing assignment coverage visible instead of filling gaps in GPT. |
| 10, final demo | Present the actual chosen producer schema, units, outcome semantics and experiment evidence; distinguish configured loss from observed drops. Check the PDF's full evidence requirements, not just the illustrative summary. |

No milestone 7 implementation or new tests were started. This update changes planning documents only. The last recorded code verification remains 437 passing tests and a built JAR from milestone 6; no fresh Maven run is claimed or needed for this documentation review. No teammate message, commit or push was performed.
