# Person 2 metric proposal: compatibility review

**Current integrated status, September 25, 2026:** this is a historical proposal review from the original `udp-file-transfer` repository. In `udp-file-transfer-integrated`, branch `feature/metrics-llm-integration` at `985b1a8`, the engine instrumentation, endpoint logs, reconciliation, REAL provider and explanation flow are implemented. The active prompts are `commands-v2` / `explanations-v6`, using `gpt-5-mini`. The earlier blanket REAL-evidence rejection and absent-provider statements below are historical, not current behavior.

Use the [README evaluation checkpoint](../README.md#current-evaluation-checkpoint), [LOGGING.md](../LOGGING.md) and [current integration assessment](person-2-integration-note.md#current-integrated-status-25-september-2026) for current implementation and verification limits. Evaluation Sections 3-6 are complete; Section 7 found the system ready for Person 4's required experiments with documented limitations. The unavailable-reason, controlled-impairment, DATA-framing and bounded API retry/evidence-capture gaps have saved passing validation linked in that assessment. The [Stage 7 acceptance record](stage-7-acceptance.md) is an implementation-stage record, distinct from evaluation Section 7.

A-C passed transfer acceptance. A's original explanation remains [REQUIRES_CORRECTION](../target/evaluation/section5-run-a-32768-20260925-204658-930498/explanation-review.json), while its measured transfer results remain valid. [B](../target/evaluation/section5-run-b-loss2-20260925-210849-739240/explanation-review.json), [C](../target/evaluation/section5-run-c-delay200-20260925-211634-307439/explanation-review.json) and [D](../target/evaluation/section5-run-d-unavailable-20260925-212915-115426/explanation-review.json) passed assignment-minimum prose review with minor presentation caveats. D's [bounded-failure acceptance passed](../target/evaluation/section5-run-d-unavailable-20260925-212915-115426/validation.json): transfer FAILED, integrity UNCONFIRMED. Live integrity FAILED remains NOT EXERCISED. These results do not require fixing or regenerating A before the required experiments. Use verified measurements for comparisons and reviewed explanations for presentation, without relabelling reviewer corrections as original model output.

PENDING PERSON 4/team: the small/large file experiment matrix, timeout/window comparison, report, protocol specification and final demonstration. Implemented and tested capability is distinct from completed submission. Final validation/reviews supersede stale preparation or session-result statuses; their ignored `target/` evidence must be shared separately at handoff.

The complete revised metric/supporting-field set remains accepted. The proposal, arithmetic and historical milestone decisions below are retained to explain how that scope developed, not to reopen approval or assert that instrumentation is absent.

## Historical September 20 proposal review

Reviewed September 20, 2026 after milestone 6 was committed as `ab3c4c0` on `person-3/llm-integration`. Source: the separately supplied `Metrics_Summary.docx` in the assignment directory. This note records its proposed fields and their implications; it does not declare a team contract or implemented measurements. The Word document labels its example illustrative, not measured, and proposes one summary record per transfer in JSONL or CSV.

**Historical follow-up:** [Revised Metrics Summary (Markdown)](Metrics_Summary_Revised.md) records the additional assignment measurements, proposed definitions and corrected synthetic baseline. Its Word copy was a historical draft and is not the maintained current reference. At the original review, this was a discussion proposal; the complete revised field set was subsequently accepted.

**Historical implementation boundary:** milestone 6 separated the summary provider from the engine and kept its metric definitions synthetic. At that checkpoint, real explanations still depended on identity mapping, measurement semantics, real outputs and integration. The integrated version now has a real provider while retaining the separation from synthetic fixtures.

## Original proposed fields and handling questions

The following definitions came from Person 2's original proposal. The handling column preserves Person 3's questions at that time. For implemented counting points, use [LOGGING.md](../LOGGING.md): in particular, current `packets_received` counts valid attributable DATA arrivals, including valid duplicates and out-of-order arrivals, rather than all raw arrivals. Current RECEIVE_DELIVERY_V1 drops already-emitted DATA at receiver delivery and applies configured delay separately to receiver DATA and sender ACK delivery. Those drops remain sender emissions; configured loss percentage, observed drop count and measured RTT are different quantities.

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

The review identified explicit handling needed for the three nonnumeric fields: `experiment_id` belongs in identity metadata, and the two booleans belong in outcome handling, not artificial 0/1 metrics. `ExplanationRequest` already received a typed state/integrity outcome, while a future real adapter still needed to preserve and reconcile the producer's meanings. Verified integrity remains necessary before claiming verified completion. Receiver verification can coexist with an unconfirmed/failed sender result when final confirmation is missing.

## Code boundary at the historical milestone 6 checkpoint

- `SummaryProvider` was identified as the read-only integration point. `SyntheticSummaryProvider` was, and remains, exclusively for fixtures; relabelling those entries cannot make them real observations.
- The pre-integration `ExplanationFlow` returned `EVIDENCE_UNAVAILABLE` for all REAL selections before consulting a provider/client. The integrated version now supports validated REAL schemas and identity associations while rejecting mismatched evidence; the old blanket gate is no longer present.
- `TransferMetrics` was the small draft status contract at that checkpoint. The proposed terminal summary supplied neither unique ACKed bytes nor a live-progress API. The integrated version now has observed live status; packet/ACK counts still must not be substituted for unique acknowledged bytes.
- The existing engine, validator, command interpreter, immutable evidence, numerical citation checks, missing-value handling and separation of analysis from execution remain applicable.

## Historical decisions requested before real integration

The integrated choices are now documented in [LOGGING.md](../LOGGING.md). This list preserves the original questions and remains useful for checking their implementation against real evidence.

1. **Identity and provenance:** establish a trustworthy association between `experiment_id`, application run/transfer IDs and protocol identity. Do not join on filename, latest record, coincident timestamps, or a generated hash of `EXP001`. Agree schema/metric-definition versions, capture/finalization time, endpoint ownership and real versus synthetic provenance. These can be in a linked manifest or envelope rather than all being new numerical fields.
2. **Timing and outcomes:** define the transfer interval and exclude GPT time. Decide what throughput means for failed or unverified runs; original file size alone cannot supply partial delivered bytes. Retain failure reasons and distinguish unchecked integrity from a failed SHA-256 check. Specify zero-duration and zero-byte cases.
3. **Counter scope and missing evidence:** agree DATA/ACK counting points, duplicates, validation and simulator placement. Do not assume `sent - received = dropped`, drops equal retransmissions, or configured loss equals observed loss. Only throughput and integrity are explicitly nullable in this proposal; settle absent observations and missing reasons for other fields rather than defaulting them to zero.
4. **Storage and lifecycle:** choose the producer's actual format/location, unique-record rules and when a record is complete/readable, including failures. JSONL is a reasonable first integration choice because it preserves numbers, booleans and nulls directly; this is a recommendation, not agreement. If CSV is selected, explicitly define types, decimal representation and null versus empty values. The proposed JSONL rows are transfer summaries; an event log is a separate artifact even if it also uses JSONL.

The original division of work assigned counters, timing, logging and calculations to Person 2, with Person 3 validating and presenting the agreed outputs. The September 20 proposal review itself changed no parser, metric calculation, observer or protocol code.

## Assignment coverage still to coordinate

The assignment PDF's minimum evidence is broader than the original proposed summary. It includes payload bytes delivered, acknowledged/timed-out/duplicated packet counts, retransmission ratio, protocol overhead, configured parameters/scenario, a machine-readable event log, and sampled RTT with mean and percentile latency where applicable. The revised field set was later accepted; the integrated code implements these categories and A-C provide real measured evidence. Person 4's experiment coverage remains pending. In this cumulative-ACK engine, `acks_received` cannot substitute for acknowledged DATA-packet count, and DATA arrivals including duplicates do not isolate the duplicate count.

The assignment requires small and large files under baseline, at least 2% random loss, and meaningful added delay or jitter, plus a timeout/window comparison. This implies six size/scenario combinations; the evaluation plan's eight-run proposal is a practical team design, not an explicit instructor count. A-C used one 262,267-byte file and unchanged 8192-byte window/500 ms DATA timeout, so the capability evidence does not complete that matrix or comparison. The final report, protocol specification and demonstration remain pending with Person 4/the team.

The historical handoff proposed particular definitions and additions such as p95, an overhead accounting boundary, seeds and version metadata. The accepted scope and implemented definitions are recorded in the revised summary and LOGGING.md. These remain team design choices: the PDF specifies a percentile where applicable, not p95 specifically, and does not prescribe our exact overhead formula or a particular jitter scalar.

The review therefore requested a retransmission-ratio denominator and zero-denominator behavior, byte accounting for overhead, documented RTT sampling and separate live observation hooks. Those implementation choices are now documented in LOGGING.md. The underlying cautions remain: overhead cannot be recovered from DATA counts alone, and RTT cannot be inferred from `delay_ms`.

## Check of the illustrative example

The example's throughput arithmetic is consistent: `10,485,760 * 8 / 8.5 / 1,000,000` is approximately `9.86895`, which rounds to `9.87 Mbps`.

Its packet counts are not a feasible successful transfer for the current engine. With at most 1,024 payload bytes per DATA packet (`Packet.MAX_PAYLOAD_SIZE`), a 10 MiB file requires at least 10,240 original DATA sends. The illustration has 1,200 attempts including 120 retransmissions, leaving only 1,080 original attempts. Since the document explicitly calls this an illustration, this is a fixture-consistency point, not evidence of an implementation defect. Do not copy it unchanged into a successful-transfer regression fixture or describe it as measured results.

To make that count precise: 10,240 is the original DATA-chunk count at exactly 1,024 bytes per chunk, not total datagrams or retry-inclusive DATA attempts. Keeping 120 resends would require 10,360 DATA attempts. ACK/control datagrams are additional, and arrival/drop/timeout counts cannot be inferred from that arithmetic. The revised summary therefore uses an explicitly synthetic, lossless, in-order baseline with 10,240 DATA attempts and zero resends, rather than inventing a complete impaired-run history.

## Historical effect on the following milestones

This table records the September 20 plan in the original repository. Its pending and future work is not the current integrated status.

| Milestone | Planning adjustment only |
| --- | --- |
| 6, remaining dependencies | Keep the completed offline boundary. Record this proposal as input to the future real adapter; real summaries, identity/schema agreement and live explanation transport remain pending. |
| 7, offline regression | When separately authorized, audit existing coverage first. Add targeted synthetic compatibility cases for the agreed producer contract: identity mapping, seconds/Mbps precision, configured versus observed fields, boolean/null outcomes, missing data and inconsistent records. Do not assume receiving this proposal authorizes or completes real parser/provider work. |
| 8, live model evaluation | Continue distinguishing synthetic evaluation from real evidence. Use agreed field names/units and test misleading-loss-name, missing-evidence and outcome questions. The separate explanation HTTP transport and its offline checks must exist before live explanation evaluation. |
| 9, real integration | Begin with the identity/definition/storage decisions above and actual producer outputs. Add the real provider/outcome mapping, verify both success and failure evidence, then integrate observations and experiments. Keep missing assignment coverage visible instead of filling gaps in GPT. |
| 10, final demo | Present the actual chosen producer schema, units, outcome semantics and experiment evidence; distinguish configured loss from observed drops. Check the PDF's full evidence requirements, not just the illustrative summary. |

The September 20 update changed planning documents only and started no milestone 7 implementation or new tests. Its recorded verification was 437 passing tests and a built JAR from milestone 6; that count is historical original-repository evidence, not the current integrated total. No fresh Maven run, teammate message, commit or push was performed for that historical review. See the README checkpoint for the later integrated verification.
