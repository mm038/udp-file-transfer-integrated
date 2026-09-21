# Connecting measurements to the LLM interface

Person 3 to Person 2 | Coordination note | 21 September 2026

## The main request

The natural-language interface can already start a real transfer, report its basic state, and interpret questions. To display detailed progress and explain real network behaviour, it needs your actual measurements and a reliable way to associate them with the correct transfer.

The complete revised metric and supporting-field set is already accepted. This note does not ask to approve that scope again. It summarises the contracts to check when connecting our components.

Current handoff status, updated after the September 21 live tests: the user reports that Person 2 has completed metrics/logging on her branch and will take over the merge and real-log connection. Her implementation has not been inspected or merged in this workspace. The requirements below are integration checks against that work, not a claim that she still needs to build every listed item.

## Live evaluation already performed

The user ran a bounded smoke batch and a follow-up synthetic explanations batch with gpt-5-mini on September 21. Saved outputs were reviewed by the assistant with an independent second review. Real natural-language start/basic status, clarification, unsupported-operation restrictions and deterministic Java rejection passed, with minor command wording notes. The real sample was 36 bytes and completed with VERIFIED integrity and matching SHA-256 hashes.

The smoke run stopped on a 30-second explanation API timeout. All four follow-up explanations completed using a 90-second HTTP deadline. Although their automatic reference checks passed, all four need semantic/causal wording corrections: delivery bytes presented as integrity evidence; an inferred timeout-to-resend causal link; asserted corruption without cause/timing evidence; and an unsupported reason for UNCONFIRMED plus confusion between repeated ACKs and distinct acknowledged progress. These are interface/explanation issues, not evidence that Person 2's producer implementation failed. No real producer logs were used in these explanation tests.

Results are relative to the repository root:

- Smoke: target/milestone-8-eval/2026-09-21T17-04-41.966257700Z-14171895076588930966/
- Explanations: target/milestone-8-eval/2026-09-21T17-14-14.616125800Z-17071091827319850136/

Each contains the original report.jsonl and completed review.md. report.jsonl retains its original pre-review PENDING labels; the worksheet records the later review. target/ is ignored by Git, so these folders must be shared separately if needed. docs/repository-and-milestone-handoff.md and its Word copy contain the portable test summary, model/usage details, repo guide and milestone status.

Milestone 8 remains open: explanation corrections/retests and broader command coverage are pending. The delegated merge/log connection can proceed alongside that interface work. Real measured explanations and impairment testing still require the integrated system; synthetic success cannot replace them. This update ran no new API calls and made no prompt/engine changes.

## Who owns which part?

| Area | Responsibility |
| --- | --- |
| Packet transfer, ACK handling, recovery and integrity operations | Person 1's existing engine. Core stages 1-10.5 are recorded as complete. |
| Instrumentation, event logs, metric calculations and impairment experiments | Primarily Person 2's measurement work. Changes at engine event points and identity integration are shared coordination with Person 1. |
| Loading the supplied records, checking identity and meanings, displaying results and sending evidence to GPT | Existing Person 3 interface; the user has now delegated merge and real-log connection to Person 2. Person 3 retains review/testing participation. |
| Explanation wording corrections found in live evaluation | Open interface follow-up, independent of real-log availability. Not automatically transferred by the merge/log-connection handoff. |

Stage 11 instrumentation was still pending at the historical progress-document checkpoint. Check Person 2's actual completed branch for its present implementation. This does not mean the engine lacks the underlying transfer operations. The progress document's older provider plans and unstarted LLM stages do not describe the current interface.

## What a hook means

A hook is a way for another Java component to observe something the engine actually did. It might be a callback, event object, observer or recorded snapshot. The exact API is a team implementation choice; this note does not prescribe one.

Example: the engine retransmits a DATA packet -> instrumentation records that resend -> the metrics producer includes it in the run's retransmission count -> the interface displays the count and supplies it to GPT.

Person 2's instrumentation should be checked against the Stage 11 event points, coordinating placement and protocol meanings with Person 1 where clarification is needed. The interface consumes observations and summaries and does not recreate packet counters in the LLM layer.

## Why the interface depends on this

- Detailed live status needs observations during a running transfer. A final summary alone cannot provide live ACK progress or current timing.
- Real post-transfer explanations need finalized measurements and linked event evidence. GPT cannot reconstruct missing measurements from the request or the file size.
- Selecting the right evidence needs a verified association between application, protocol and experiment identities.
- The final impaired demo needs a working impairment setup and actual collected logs to show beside the explanation.

Basic real starts/status, Java rejection, offline regression checks and explicitly labelled synthetic GPT evaluation can continue independently. Currently, a real explanation returns EVIDENCE_UNAVAILABLE before the explanation API call. Fixtures will never fill missing real evidence.

<!-- pagebreak -->

## Observations to expose with Person 1

Record what happened, where it happened and which run it belongs to. Keep instrumentation lightweight so it does not introduce unnecessary delay into packet handling.

| Observation | What it supports |
| --- | --- |
| DATA send attempt and actual UDP emission, with sequence, attempt number and encoded byte length | Original sends versus resends; actual emitted-byte accounting. A simulator-suppressed attempt is not an emission. |
| Receiver DATA arrival, validation, duplicate/out-of-order outcome, and unique bytes accepted/written | Arrival counts versus unique delivered payload; duplicate counts and evidence of invalid or discarded traffic. |
| DATA-ACK arrival and validation, plus new cumulative ACK progress | ACK arrivals versus distinct acknowledged DATA sequences and unique sender-confirmed progress. Repeated ACKs must not add progress. |
| Detected DATA deadline expiry and recovery/resend decisions | Timeout occurrences versus resend counts and consecutive recovery rounds. A socket polling timeout alone is not a DATA expiry. |
| START, ACK/control and FINISH activity, actual emissions at both endpoints | Timing boundaries, protocol completion and complete protocol-overhead accounting. |
| Monotonic event times and unambiguous send-to-ACK associations | Transfer duration and applicable RTT samples. Keep GPT/API time outside transfer timing. |
| Terminal sender outcome, receiver SHA-256 check and recorded failure reason | Distinguishing protocol success, verified integrity, failed integrity and unconfirmed results. |
| Actual simulator drops/delay decisions and applied configuration | Distinguishing configured impairment from observed simulator events. |

For live status, provide a documented way to obtain a consistent snapshot or consume events as the run proceeds. Sender-confirmed ACK progress and receiver-delivered bytes are different observations. Expose only the facts actually available. The live adapter API and refresh method remain technical integration choices, not a new accepted summary schema.

## Transfer identity must be trustworthy

The interface currently creates application transfer_id/run_id values. The engine separately creates its wire UUID internally. Person 2's experiment_id may be a label rather than a UUID.

- Associate experiment_id, application run/transfer IDs and the wire protocol_transfer_id through actual execution evidence or a trusted manifest.
- Supplying the engine UUID from the application or exposing the internally created UUID are both possible approaches. Neither requires a protocol redesign.
- Record endpoint attribution, file identity and REAL/SYNTHETIC provenance. State which endpoint supplied integrity evidence.
- Never join records solely by filename, the newest record or similar timestamps. Keep an unavailable wire ID null until the association is verified.

Person 1 and Person 2 coordinate how identities and events become observable. The delegated integration must implement and test the consumer-side association checks against the actual inputs, with Person 3 participating in review.

<!-- pagebreak -->

## Measurements and records to supply

Use docs/Metrics_Summary_Revised.md as the accepted field inventory and working definition reference. The numerical fields are grouped here so none are lost in the handoff:

| Group | Accepted numerical fields |
| --- | --- |
| Payload and performance | file_size_bytes, payload_bytes_delivered, transfer_time_sec, throughput_mbps |
| DATA and ACK activity | packets_sent, packets_received, packets_dropped, retransmissions, acks_received, packets_acked, packets_timed_out, packets_duplicated, retransmission_ratio |
| Byte accounting and RTT | udp_payload_bytes_emitted, protocol_overhead_bytes, protocol_overhead_ratio, rtt_sample_count, rtt_mean_ms, rtt_p95_ms |
| Effective configuration | chunk_size_bytes, window_bytes_requested, window_packets, timeout_ms, retry_limit, packet_loss_rate, delay_ms |

Also carry the accepted supporting information: experiment identity and verified ID association; schema/metric-definition versions; evidence source; capture/finalization time; endpoint and file attribution; scenario and applicable impairment seed; transfer_success; nullable integrity_verified and its evidence source; failure_reason; and per-field unavailable_reasons. Keep typed metadata separate from numerical measurements.

## Meanings the interface must preserve

- transfer_time_sec uses seconds; throughput_mbps uses decimal megabits/second. Delay and RTT use milliseconds. Preserve supplied precision and document export rounding.
- packet_loss_rate is configured DATA loss percentage. packets_dropped records the identified simulator's drops; neither retransmissions nor sent-minus-received establishes all network loss.
- acks_received counts ACK arrivals, including repeats. It is not packets_acked or payload_bytes_delivered. Cumulative ACKs can confirm multiple sequences.
- The retry limit concerns consecutive recovery rounds without progress. It is not the lifetime number of retransmitted packets.
- Missing measurements are null with reasons. Zero means an observed zero. Do not infer missing values to make a summary look complete.
- transfer_success represents sender-confirmed completion. integrity_verified distinguishes a checked match, a checked mismatch and an unavailable check. Preserve receiver-only verification even when sender confirmation fails.
- Failed-run payload and performance must remain labelled as partial/failed-run evidence. A file's original size is not proof of successful delivery.
- Overhead needs complete, compatible emission accounting from both endpoints. RTT needs documented valid samples; configured delay is not RTT.

The revised document gives working formulas and counting/timing boundaries. Resolve implementation details against actual outputs and document any changes. The instructor requires metric categories and evidence; exact JSON names, p95 choice, callback API and storage layout are team choices.

<!-- pagebreak -->

## Concrete handoff needed for integration

- Actual machine-readable raw event logs, separate from per-transfer summaries. A summary row does not replace packet/event evidence.
- Finalized summary records for successful and failed runs, with linked supporting logs. Include available partial observations on failure rather than reporting successful-file metrics.
- The actual format and location: JSONL or CSV, field types, units, numeric precision, and null representation. JSONL is a recommendation, not an imposed format.
- Schema and metric-definition versions; record uniqueness; when records become readable/final; and how incomplete or interrupted runs are represented. The interface must not read a half-written record as final.
- Documented event meanings, endpoint attribution, transfer/experiment associations, timing boundaries, counter locations and outcome semantics. Suggested event contents include time, endpoint/direction, message type, sequence/ACK, attempt, byte sizes and validation/drop/timeout outcome; exact schema is still to be supplied.
- A small set of real success/failure examples with the commands that produced them. Use these to implement and verify the real provider and reconciliation checks during the delegated integration and Person 3's review.

## Impairment and final demonstration dependencies

Provide reproducible configuration and commands for the impairment mechanism you implement, including affected direction/message types, simulator placement, delay/jitter behaviour and a seed when applicable. Configure impairment in trusted Java/environment setup; the existing GPT command vocabulary does not include a loss-setting operation.

The assignment requires small and large files in each of: a baseline with low delay/no intentional loss; at least 2% random loss; and meaningful added delay or jitter. It also requires at least three raw metric logs and reproducibility commands/scripts. These experiments support the evaluation report; the instructor demonstration can show selected runs and their actual evidence.

Person 3 needs a rehearsed loss/delay run with real measurements for the final explanation demonstration. Engine recovery and receiver restart/output protection for the chosen scenarios remain coordination with Person 1. The current receiver handles one transfer per process, so restart it and use a fresh output path for each run until that behaviour changes.

## Remaining connection work delegated to Person 2

Merge the branches and resolve conflicts while preserving the Java validation/evidence boundaries. Implement the read-only real-summary provider; connect observed status; validate identity, provenance, field semantics and consistency; reconcile sender outcome with integrity evidence; display measurements beside the GPT explanation; and test matching, missing, failed and inconsistent records. Real evidence integration will deliberately replace the current REAL-evidence gate after verification. The accepted synthetic fixtures are not a producer schema to copy blindly. Person 3 will review behaviour and participate in final testing. This note does not perform the merge or integration.

## References and status

- Assignment 1 FTP UDP(1).pdf, pages 2-3: interface, observability, experiments and required demonstration.
- docs/Metrics_Summary_Revised.md: accepted field inventory and working definitions.
- docs/repository-and-milestone-handoff.md and docs/person-3-milestone-8.md: current interfaces, live results, boundaries and pending integration.
- Implementation Progress.docx: historical engine completion through Stage 10.5; Stage 11 instrumentation pending.

This is a coordination note, not verification of Person 2's reported completed implementation or a claim that real measured integration is complete here. It creates no additional metric-scope approval step. The small milestone 8 live evaluation has been run and reviewed with open findings; milestone 9 integration has not been implemented by this note.
