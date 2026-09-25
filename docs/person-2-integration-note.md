# Connecting measurements to the LLM interface

Person 3 to Person 2 | Historical coordination note from 22 September 2026

## Current integrated status (25 September 2026)

This note preserves the original repository's pre-integration handoff and live-evaluation history. It is not the operating guide for `udp-file-transfer-integrated` at `feature/metrics-llm-integration`, commit `985b1a8`. That integrated version connects the engine, live observations, endpoint logs, reconciliation, REAL summary provider and explanation flow. The active prompts are `commands-v2` / `explanations-v6`, with `gpt-5-mini`. REAL explanations are supported when validated evidence is available; they are no longer unconditionally blocked.

Use the [README evaluation checkpoint](../README.md#current-evaluation-checkpoint), [LOGGING.md](../LOGGING.md) and the saved final validation/review files for current behavior and verification limits. Evaluation Sections 3-6 are complete. Section 7 found the integrated system ready for Person 4's required experiments, with the limitations below; this does not complete the experiment matrix or submission. The [Stage 7 acceptance record](stage-7-acceptance.md) concerns an implementation stage, not evaluation Section 7.

- The four inspection gaps are resolved in saved validation: [unavailable metric reasons](../target/evaluation/metrics-null-reasons-20260925-131319-640/validation.json), [controlled impairment](../target/evaluation/controlled-impairment-20260925-134232-676/validation.json), [DATA framing](../target/evaluation/data-framing-20260925-161314-533/validation.json), and [bounded API retry/evidence capture](../target/evaluation/api-evidence-capture-20260925-151307-190/validation.json).
- A-C passed transfer acceptance with verified receiver contents. A demonstrated [natural-language start and explanation selection](../target/evaluation/section5-run-a-32768-20260925-204658-930498/validation.json); C demonstrated [genuinely active natural-language status](../target/evaluation/section5-run-c-delay200-20260925-211634-307439/natural-language-status-review.json). Section 6 demonstrated [clarification with retained context](../target/evaluation/section6-clarification-prep-20260925-215444-525297/validation.json) and [unsupported-operation nonexecution](../target/evaluation/section6-unsupported-prep-20260925-221110-460830/validation.json). The live model's refusal and the existing offline deterministic Java rejection are separate evidence.
- A's original explanation remains [REQUIRES_CORRECTION](../target/evaluation/section5-run-a-32768-20260925-204658-930498/explanation-review.json): its combined sender/receiver UDP emissions were incorrectly attributed to the sender, with other unsupported claims. Its transfer measurements remain valid; the original response and failed review are deliberately preserved as a documented limitation. [B](../target/evaluation/section5-run-b-loss2-20260925-210849-739240/explanation-review.json), [C](../target/evaluation/section5-run-c-delay200-20260925-211634-307439/explanation-review.json), and [D](../target/evaluation/section5-run-d-unavailable-20260925-212915-115426/explanation-review.json) passed assignment-minimum prose review with minor presentation caveats. Use verified measurements for comparisons and reviewed explanations for presentation; reviewer corrections are not original model output.
- [D's bounded-failure acceptance passed](../target/evaluation/section5-run-d-unavailable-20260925-212915-115426/validation.json), with transfer FAILED and integrity UNCONFIRMED. Live integrity FAILED remains NOT EXERCISED. No identified issue requires a code fix or paid regeneration of A before Person 4 starts experiments.
- PENDING PERSON 4/team: small and large files in baseline, at least 2% random loss, and meaningful delay or jitter, plus the required timeout/window comparison, report, protocol specification and demonstration. The six size/scenario combinations follow from assignment coverage; the evaluation plan's eight-run proposal is a team design, not an instructor-mandated run count. Completed capability checks do not constitute those final deliverables.

These evidence links point to local ignored `target/` files and must be shared separately when handing off the repository. Earlier preparation/session-result statuses and remaining-work lists may be stale; use the final validation and semantic-review files above.

The historical sections below retain the September 20-22 original-repository coordination context; updates explicitly refer to the integrated version. Preserve the earlier paid-call results, prompt versions and evidence paths as historical evidence; they do not establish current v6 quality.

## Historical request before integration

At this historical checkpoint, the natural-language interface could start a real transfer, report its basic state, and interpret questions. Detailed progress and real measured explanations still needed actual measurements and a reliable association with the correct transfer.

The complete revised metric and supporting-field set is already accepted. This note does not ask to approve that scope again. It summarises the contracts to check when connecting our components.

Handoff status on September 22 in the original repository: the user reported that Person 2 had completed metrics/logging on her branch and would take over the merge and real-log connection. Her implementation had not been inspected or merged there. The requirements below recorded checks for that integration; they do not mean those components remain absent from the integrated repository.

September 22 Git inspection in the original repository found `feature/LLM-integration` at `ca5990c`, “Added live evaluation and reviewed handoff findings”. The original milestone 8 implementation/documents were committed, and the user reported that commit was pushed; no fresh remote fetch verification was claimed. At that checkpoint, the deliberate local deletion of `docs/person-3-handoff.md` was excluded and preserved, `data/` was untracked, `target/` evidence was local, and the new September 22 corrections were uncommitted. These are historical original-workspace facts, not this integrated checkout's Git state.

## Historical live evaluation in the original repository

The user ran a bounded smoke batch and a follow-up synthetic explanations batch with gpt-5-mini on September 21. Saved outputs were reviewed by the assistant with an independent second review. Real natural-language start/basic status, clarification, unsupported-operation restrictions and deterministic Java rejection passed, with minor command wording notes. The real sample was 36 bytes and completed with VERIFIED integrity and matching SHA-256 hashes.

The smoke run stopped on a 30-second explanation API timeout. All four follow-up explanations completed using a 90-second HTTP deadline. Although their automatic reference checks passed, all four were flagged for semantic/causal wording corrections: delivery bytes presented as integrity evidence; an inferred timeout-to-resend causal link; asserted corruption without cause/timing evidence; and an unsupported reason for UNCONFIRMED plus confusion between repeated ACKs and distinct acknowledged progress. These are historical interface/explanation findings, not evidence that Person 2's producer implementation failed. No real producer logs were used in these explanation tests.

These evidence paths are relative to the original `udp-file-transfer` repository root, not this integrated checkout. They are preserved historical locations, not a claim that the ignored evidence folders were copied here:

- Smoke: target/milestone-8-eval/2026-09-21T17-04-41.966257700Z-14171895076588930966/
- Explanations: target/milestone-8-eval/2026-09-21T17-14-14.616125800Z-17071091827319850136/
- September 22 v4 explanations retest: target/milestone-8-eval/2026-09-22T06-58-35.590646700Z-9150417386280831162/

Each historical evidence folder contains the original report.jsonl and completed review.md. report.jsonl retains its original pre-review PENDING labels; the worksheet records the later review. target/ is ignored by Git, so these folders must be shared separately if needed. [The repository handoff](repository-and-milestone-handoff.md) contains the historical portable test summary and model/usage details. The September 22 original-workspace record reported local deletion of its Word copy, this note's Word copy and `Metrics_Summary_Revised.docx`; that is not a statement about this checkout's Git status. Use the current-status links above for integrated guidance.

Person 3's first September 22 correction changed only `ExplanationRequest.java` production prompt instructions to `explanations-v4`: delivery is separate from integrity, repeat ACK arrivals are separate from distinct DATA progress, and aggregate counts cannot establish a causal event sequence. Causal hypotheses must themselves be uncertain and identify missing evidence; an empty hypothesis list is allowed. State/integrity are supplied outcome facts for limitations, not invented numerical references or proof of a cause/timing. Explicit synthetic labeling and endpoint attribution are also reinforced. Numeric citations, missing-value reasons, tool-free explanations and Java validation are preserved. The September 21 live results used `commands-v2` / `explanations-v3`; they are not evidence of v4 behaviour.

The user previewed and deliberately ran the four-call explanations retest on September 22, 06:58:36-07:00:38 UTC (10:58-11:00 Dubai). It requested `gpt-5-mini` and returned `gpt-5-mini-2025-08-07`, using `explanations-v4`, one attempt per call, a terminal-only 90000 ms HTTP deadline and at most 4096 output tokens per call. The application default is unchanged. All automatic checks passed; the assistant and an independent second reviewer then assessed the actual prose against only the model-visible evidence. The original four substantive errors did not recur in these sampled answers.

Remaining findings prevent a blanket full PASS: unsupported diagnostic uses of emitted-byte totals; missing-performance's omitted SYNTHETIC label and mixed-endpoint heading; an unclear failed-verification conclusion; and unconfirmed-outcome's final cannot-answer statement after correctly explaining that counters cannot prove success/integrity. FAILED is preserved without inventing a literal mismatch or its cause. See [the milestone 8 record](person-3-milestone-8.md) and the new `review.md` for details, usage and prior offline verification. No additional source fixes, tests or paid calls were made during this review; the historical 603-test/32-class packaging checkpoint remains historical.

Following the historical review, the user authorized a narrow refinement to `explanations-v5`. Diagnostic suggestions had to identify evidence capable of testing the claim: aggregate emission totals alone do not establish DATA drops or verify contents. The prompt directly answered what the supplied evidence establishes even when the cause is unknown, preserved FAILED as reported failed verification without inventing a checksum mismatch, placed explicit evidence provenance (including SYNTHETIC test fixture when applicable) in limitations, and preserved per-field counter meanings and endpoint attribution throughout the prose. These were general interpretation rules, not a new producer contract. The integrated source now uses v6.

At that original-repository checkpoint, the v5 refinement was uncommitted and not live-tested; no additional paid calls were made. See [the milestone 8 record](person-3-milestone-8.md) for its historical offline verification status. Existing live reports/reviews remain unchanged historical v3/v4 evidence. Model selection remained `gpt-5-mini`, and that refinement did not change the application timeout default.

Milestone 8 remained open at that historical checkpoint. Further paid retesting was paused pending the merge, genuine-log connection and checks of actual inputs, contracts, identities and outcomes. The existing four-call explanations batch is synthetic and cannot by itself validate real integration. The blanket REAL `EVIDENCE_UNAVAILABLE` gate described in that handoff has since been replaced by validated REAL evidence handling in the integrated version. The integrated evaluation has now completed Sections 3-6; use the current-status assessment above instead of this historical milestone's pending list. Any later paid work still requires approval, bounded calls and review against only model-visible evidence.

## Historical ownership and handoff responsibilities

| Area | Responsibility |
| --- | --- |
| Packet transfer, ACK handling, recovery and integrity operations | Person 1's existing engine. Core stages 1-10.5 are recorded as complete. |
| Instrumentation, event logs, metric calculations and impairment experiments | Primarily Person 2's measurement work. Changes at engine event points and identity integration are shared coordination with Person 1. |
| Loading the supplied records, checking identity and meanings, displaying results and sending evidence to GPT | Existing Person 3 interface; the user has now delegated merge and real-log connection to Person 2. Person 3 retains review/testing participation. |
| Explanation wording corrections found in live evaluation | Person 3 implemented the September 22 v4 correction and reviewed the user-run retest. Original errors did not recur in that sample. The subsequent v5 refinement addresses residual findings but is not live-tested; validation remains open, independently of the delegated merge/log connection. |

Stage 11 instrumentation was still pending at the historical progress-document checkpoint. Instrumentation is implemented and exercised in the integrated version; [LOGGING.md](../LOGGING.md) defines its behavior and the current-status evidence above records the completed checks and remaining experiment coverage. The progress document's older provider plans and unstarted LLM stages do not describe the current interface.

## What a hook means

A hook is a way for another Java component to observe something the engine actually did. It might be a callback, event object, observer or recorded snapshot. The exact API is a team implementation choice; this note does not prescribe one.

Example: the engine retransmits a DATA packet -> instrumentation records that resend -> the metrics producer includes it in the run's retransmission count -> the interface displays the count and supplies it to GPT.

Person 2's instrumentation should be checked against the Stage 11 event points, coordinating placement and protocol meanings with Person 1 where clarification is needed. The interface consumes observations and summaries and does not recreate packet counters in the LLM layer.

## Why the interface depends on this

- Detailed live status needs observations during a running transfer. A final summary alone cannot provide live ACK progress or current timing.
- Real post-transfer explanations need finalized measurements and linked event evidence. GPT cannot reconstruct missing measurements from the request or the file size.
- Selecting the right evidence needs a verified association between application, protocol and experiment identities.
- The final impaired demo needs a working impairment setup and actual collected logs to show beside the explanation.

In the original pre-integration interface, basic real starts/status, Java rejection, offline regression checks and explicitly labelled synthetic GPT evaluation could proceed independently, but a real explanation returned EVIDENCE_UNAVAILABLE before the explanation API call. The integrated flow now supports validated REAL records. Fixtures must never fill missing real evidence.

<!-- pagebreak -->

## Observations to expose with Person 1

Record what happened, where it happened and which run it belongs to. Keep instrumentation lightweight so it does not introduce unnecessary delay into packet handling.

| Observation | What it supports |
| --- | --- |
| DATA send attempt and actual UDP emission, with sequence, attempt number and encoded byte length | Original sends versus resends; actual emitted-byte accounting. Current receive-side impairment drops already-emitted DATA at receiver delivery, so these drops remain sender emissions. |
| Receiver DATA arrival, validation, duplicate/out-of-order outcome, and unique bytes accepted/written | Arrival counts versus unique delivered payload; duplicate counts and evidence of invalid or discarded traffic. |
| DATA-ACK arrival and validation, plus new cumulative ACK progress | ACK arrivals versus distinct acknowledged DATA sequences and unique sender-confirmed progress. Repeated ACKs must not add progress. |
| Detected DATA deadline expiry and recovery/resend decisions | Timeout occurrences versus resend counts and consecutive recovery rounds. A socket polling timeout alone is not a DATA expiry. |
| START, ACK/control and FINISH activity, actual emissions at both endpoints | Timing boundaries, protocol completion and complete protocol-overhead accounting. |
| Monotonic event times and unambiguous send-to-ACK associations | Transfer duration and applicable RTT samples. Keep GPT/API time outside transfer timing. |
| Terminal sender outcome, receiver SHA-256 check and recorded failure reason | Distinguishing protocol success, verified integrity, failed integrity and unconfirmed results. |
| Actual simulator drops/delay decisions and applied configuration | Distinguishing configured impairment from observed simulator events. |

For live status, provide a documented way to obtain a consistent snapshot or consume events as the run proceeds. Sender-confirmed ACK progress and receiver-delivered bytes are different observations. Expose only the facts actually available. The live adapter API and refresh method remain technical integration choices, not a new accepted summary schema.

## Transfer identity must be trustworthy

The original interface created application transfer_id/run_id values while the engine separately created its wire UUID internally. The integrated version carries and validates the association between application, endpoint-run and protocol identities; see [LOGGING.md](../LOGGING.md). An experiment_id may still be a label rather than a UUID. The following were the original association requirements:

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
- packet_loss_rate is configured DATA loss percentage. In current RECEIVE_DELIVERY_V1, packets_dropped records already-emitted DATA dropped at receiver delivery. Configured delay applies separately to receiver DATA delivery and sender ACK delivery; it is not measured RTT. Neither retransmissions nor sent-minus-received establishes all network loss.
- acks_received counts ACK arrivals, including repeats. It is not packets_acked or payload_bytes_delivered. Cumulative ACKs can confirm multiple sequences.
- The retry limit concerns consecutive recovery rounds without progress. It is not the lifetime number of retransmitted packets.
- Missing measurements are null with reasons. Zero means an observed zero. Do not infer missing values to make a summary look complete.
- transfer_success represents sender-confirmed completion. integrity_verified distinguishes a checked match, a checked mismatch and an unavailable check. Preserve receiver-only verification even when sender confirmation fails.
- Failed-run payload and performance must remain labelled as partial/failed-run evidence. A file's original size is not proof of successful delivery.
- Overhead needs complete, compatible emission accounting from both endpoints. RTT needs documented valid samples; configured delay is not RTT.

The revised document gives working formulas and counting/timing boundaries. Resolve implementation details against actual outputs and document any changes. The instructor requires metric categories and evidence; exact JSON names, p95 choice, callback API and storage layout are team choices.

<!-- pagebreak -->

## Historical handoff checklist for integration

The integrated implementation now supplies endpoint event JSONL, run-state files, endpoint records, reconciliation and a REAL provider. See [LOGGING.md](../LOGGING.md) for the implemented schema and paths. The original requested deliverables below remain useful review criteria, not a list of entirely absent components.

- Actual machine-readable raw event logs, separate from per-transfer summaries. A summary row does not replace packet/event evidence.
- Finalized summary records for successful and failed runs, with linked supporting logs. Include available partial observations on failure rather than reporting successful-file metrics.
- The actual format and location: JSONL or CSV, field types, units, numeric precision, and null representation. JSONL is a recommendation, not an imposed format.
- Schema and metric-definition versions; record uniqueness; when records become readable/final; and how incomplete or interrupted runs are represented. The interface must not read a half-written record as final.
- Documented event meanings, endpoint attribution, transfer/experiment associations, timing boundaries, counter locations and outcome semantics. Suggested event contents include time, endpoint/direction, message type, sequence/ACK, attempt, byte sizes and validation/drop/timeout outcome; exact schema is still to be supplied.
- A small set of real success/failure examples with the commands that produced them. Use these to implement and verify the real provider and reconciliation checks during the delegated integration and Person 3's review.

## Impairment and final demonstration dependencies

Provide reproducible configuration and commands for the impairment mechanism you implement, including affected direction/message types, simulator placement, delay/jitter behaviour and a seed when applicable. Configure impairment in trusted Java/environment setup; the existing GPT command vocabulary does not include a loss-setting operation.

The assignment requires small and large files in each of: a baseline with low delay/no intentional loss; at least 2% random loss; and meaningful added delay or jitter. It also requires at least three raw metric logs and reproducibility commands/scripts. These experiments support the evaluation report; the instructor demonstration can show selected runs and their actual evidence.

The controlled impairment mechanism and live explanations have been exercised: [B's transfer validation](../target/evaluation/section5-run-b-loss2-20260925-210849-739240/validation.json) covers configured 2% receiver DATA loss and [C's transfer validation](../target/evaluation/section5-run-c-delay200-20260925-211634-307439/validation.json) covers 200 ms delivery delay in each direction. A-C used the same 262,267-byte file, 8192-byte window and 500 ms DATA timeout; they do not complete small/large coverage or the timeout/window comparison. Person 4's experiment matrix and the team's final demonstration rehearsal remain pending. The current receiver handles one transfer per process, so restart it and supply a fresh bare output filename for each run; the receiver writes under `storage/incoming`.

## Historical connection work delegated to Person 2

The handoff requested a merge preserving the Java validation/evidence boundaries, a read-only real-summary provider, observed status, identity/provenance/semantic checks, outcome reconciliation, and displayed evidence beside the explanation. These connections now exist in the integrated version; [Stage 7](stage-7-acceptance.md) records offline acceptance and its limits. The accepted synthetic fixtures remain separate from genuine producer records. The current-status section above supersedes historical open inspection, controlled-impairment and live-prose evaluation claims while retaining A's actual review failure and the remaining assignment work.

## References and status

- Assignment 1 FTP UDP(1).pdf, pages 2-3: interface, observability, experiments and required demonstration.
- docs/Metrics_Summary_Revised.md: accepted field inventory and working definitions.
- docs/repository-and-milestone-handoff.md and docs/person-3-milestone-8.md: historical original-repository interfaces, live results, boundaries and pending integration at those checkpoints.
- Implementation Progress.docx: historical engine completion through Stage 10.5; Stage 11 instrumentation pending.

This historical coordination note creates no additional metric-scope approval step. Its small milestone 8 live evaluation was reviewed with open findings. Integrated implementation and verification status are recorded separately in the current-status links at the top; updating this note does not perform or complete the remaining evaluation.
