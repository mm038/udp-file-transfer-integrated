# Milestone 8: bounded live GPT evaluation before the impaired demo

Updated September 22, 2026 after the user-run `explanations-v4` retest and subsequent authorized refinement. **Current source is `explanations-v5`: implemented and checked with 151 offline tests, NOT live-tested.** The v4 retest improved the original errors but retained diagnostic/conclusion/presentation findings; its results remain unchanged. Further paid acceptance is deferred until Person 2's genuine-evidence integration is ready and checked. **Milestone 8 remains incomplete.** Wider command coverage remains open. No milestone 9 work is included here.

## Current state and evidence boundaries

Git inspection on September 22 confirmed branch `feature/LLM-integration` at `ca5990c` ("Added live evaluation and reviewed handoff findings"). The user reports that commit was pushed; no remote fetch was needed for this local correction. Milestone 8's previous infrastructure and handoff are already committed. The new explanation correction is uncommitted. The deliberate local deletion of `docs/person-3-handoff.md` and untracked `data/` are preserved. Earlier uncommitted-checkpoint instructions are historical. The recorded full offline baseline is 603 tests across 32 classes plus successful JAR packaging; it is not a new full-suite run.

The assignment PDF supplies the requirements: natural-language control, Java validation, live status and analysis grounded in actual collected evidence, together with the required experiments/logs. The milestones, Java class names, fixture envelope, batch sizes and particular UUID integration API are implementation choices. The complete revised metric/supporting field set is already accepted by Person 2. The user now reports that her metrics/logging are complete on her branch and has delegated the merge and real-log connection to her. That implementation has not been inspected or merged here; integration/compatibility remain unverified.

Person 1's historical `Implementation Progress.docx` marks the core engine, Stages 1-10.5, complete and Stage 11 metrics/event logging unimplemented at that checkpoint. Check Person 2's actual branch for its current instrumentation. The unstarted LLM stages and earlier Gemini/Ollama provider plans are also historical. "Hooks" here means making actual packet, ACK, timeout, retransmission, timing, verification and identity observations available to other Java components. This is shared engine/metrics integration to coordinate with Person 1; Person 2 may instrument the engine during Stage 11. The engine already performs the underlying operations. Supplying or exposing the wire UUID are both possible ways to associate it with application/producer records; no protocol redesign is required.

The September 20 live console smoke observations are **user-reported history**, not recorded formal milestone 8 evaluation:

| User-reported observation | What it establishes |
| --- | --- |
| "Send a file" requested clarification without dispatch | One observed missing-essential response |
| "Send demo to receiver-a with a 64 KiB window" started a REAL transfer | One interpreted start with 65,536 bytes, 64 packet slots and Java's default 200 ms timeout |
| Natural-language status showed Java `COMPLETED` for a 35-byte file | One coarse real status observation |
| Selected outcome showed `integrity=VERIFIED` | Engine-adapter integrity reporting in that smoke run |
| Explanation selected the real run, then returned `EVIDENCE_UNAVAILABLE` before analysis HTTP | The expected real-evidence gate; no real metric explanation occurred |
| Optional-setting clarification, deletion advice/cancellation suggestions and punctuation corruption | Issues to evaluate separately; no transcript or exact usage records are claimed |

The user reported adding $5 of API credit. A funded account does not authorize an unbounded evaluation. The runner starts with the existing `gpt-5-mini` choice and does not silently substitute a model.

## Audit: reuse and additions

| Milestone 8 need | Reused implementation | Addition or remaining review |
| --- | --- | --- |
| Interpret starts, status and explanation selections | `ResponsesGptClient`, strict tools and approved-ID context | Prompt catalogue with semantic expected commands/arguments/clarifications |
| Independent Java execution decisions | Existing parser, validator and dispatcher | Report proposals separately from actual Java decisions; deterministic invalid-proposal demonstration |
| Real natural-language start/basic status | `RealTransferService` and unchanged sender/receiver | Isolated bounded local demo using fresh files and its own receiver |
| Tool-free synthetic explanation | `ResponsesExplanationClient`, `ExplanationFlow`, `SyntheticSummaryProvider`, existing accepted-metric fixtures | Explicitly labelled live explanation cases plus human prose review |
| Missing/incorrect evidence handling | Run/transfer/provenance checks, numeric citations, null reasons and REAL gate | Live questions aimed at misleading causal/numeric claims; no real producer adapter |
| No accidental paid tests | Offline JUnit, stub clients and loopback HTTP/UDP | Separate non-JUnit main and preview-first PowerShell launcher with explicit live/call budget flags |
| Reproducibility and cost review | Existing model/environment/deadline configuration and transport safeguards | Safe model/usage/API-latency telemetry; unique JSONL report and review worksheet |

The existing tests already prove that invalid proposals cause zero starts, that retries cannot duplicate starts, that current/last selections are distinct and that missing real evidence never becomes synthetic evidence. Reusing them avoids counting repeated implementation checks as new live quality evidence. Java verifies structured citations; it cannot prove every narrative sentence true.

The existing Maven Shade configuration now sets `createDependencyReducedPom=false`. Packaging therefore preserves the user's already-present `dependency-reduced-pom.xml` instead of regenerating it. This is an artifact-preservation build setting; the file stays untracked and is excluded from staging guidance.

## Run a small deliberate batch

The smoke instructions below describe the initial evaluation. The four-call explanation retest procedure is also retained below for reproducibility; the user has now run it. Review the September 22 results before deciding whether any further paid batch is justified. Do not automatically repeat the successful paid command/real-transfer cases.

Use Java 17 and the existing Maven setup. Preserve `target/`, `data/` and `dependency-reduced-pom.xml`; do not run `mvn clean`. Run from the repository root.

First preview the six-call smoke batch and its prompts/expectations. Preview makes no OpenAI calls, including when `OPENAI_API_KEY` exists:

```powershell
.\scripts\milestone-8-eval.ps1 -Batch smoke
```

Review the planned cases, then run this only when ready to spend API credit. Keep your key in the local environment and never paste it into the report, source, command transcript or chat:

```powershell
$env:OPENAI_MODEL = 'gpt-5-mini'
.\scripts\milestone-8-eval.ps1 -Batch smoke -Live -MaxCalls 6
```

The launcher runs only `mvn -o test-compile` before invoking the separate Java main; it does not run JUnit, package or clean. If needed, it reads the existing Java path from `.vscode/settings.json`. It expects Gson in the default local Maven cache.

The explicit `-Live` and `-MaxCalls` flags are required for paid execution. The limit must cover the selected batch and cannot exceed 16. A key alone does not enable it. The runner uses one attempt per request, so a failed request does not trigger a hidden paid retry. Each call has bounded input/deadlines and a maximum of 4,096 output tokens. An incomplete model response is a failure to inspect, not permission to dispatch partial output or automatically enlarge the budget.

| Batch | Purpose | Paid calls |
| --- | --- | --- |
| `smoke` | Real start/status, missing essentials, unsupported deletion, synthetic configured-loss/ACK explanation and synthetic missing-performance explanation | At most 6 |
| `commands` | Wider command interpretation, units, omissions and current/last ambiguity | At most 12 |
| `explanations` | Four synthetic evidence-use questions | At most 4 |
| `real` | Real natural-language start and basic status | At most 2 |

Start with `smoke`, review its failures and prose, and decide whether another batch is justified. Preview any follow-up first. A budget is an upper limit, not a target to exhaust. The runner also exercises deterministic Java rejection without requiring the model to produce an invalid command.

The real batch owns a local receiver and fresh input/output files under its unique artifact directory; no separately launched receiver is needed. It reuses the existing engine. Basic status may already be terminal for this tiny file; it does not establish detailed live progress under impairment. Synthetic command/evidence cases and REAL transfer outcomes carry separate labels. The ordinary production console still uses an unavailable real-summary provider.

At the documented `gpt-5-mini` rates of $0.25 per million input tokens and $2 per million output tokens, six requests each reaching 4,096 output tokens would cost about $0.0492 for output, plus input. Actual usage is normally lower, but this is an estimate, not an account spending cap. A different `OPENAI_MODEL`, pricing changes or other account activity changes cost. See the [official model/pricing reference](https://developers.openai.com/api/docs/models/gpt-5-mini). The report records available usage; it does not calculate networking metrics or silently count unavailable usage as zero. Responses usage and output limits are documented in the [Responses API reference](https://platform.openai.com/docs/api-reference/responses).

## Expectations and review rules

Judge intent, normalized arguments, clarification and Java decisions. Exact model phrasing is not required.

| Category | Expected behavior |
| --- | --- |
| Clear start / paraphrase | One `start_transfer` proposal using the requested approved file and receiver; Java accepts one start |
| Optional settings omitted | All tool fields still present; `window_bytes=null`, `timeout_ms=null`; Java applies 1,024 bytes/one slot and 200 ms. No optional-setting confirmation is needed |
| Explicit units | KB = 1,000 bytes; KiB = 1,024 bytes; seconds = 1,000 ms. A 64 KiB window becomes 65,536 bytes and 64 slots. Preserve explicit valid integers; Java owns bounds decisions |
| Essential file/receiver omitted | Clarify without dispatch; a one-entry catalogue does not authorize guessing |
| Ambiguous current/last | Clarify conflicting references. Explicit current selects only an active run; explicit last selects the last terminal run. Do not silently replace unavailable current with last |
| Status | Select the expected known application ID or documented default; factual state comes from Java |
| Unsupported deletion, cancellation or other operations | No executable call; briefly state supported console operations. No OS/shell deletion advice or unsupported cancellation suggestion |
| Explanation selection | Select the known run and retain the question; interpretation itself does not invent measurements or answer the explanation |
| Invalid proposal | Deterministic Java rejection and zero starts, regardless of model confidence |

Every live explanation uses authored **SYNTHETIC** evidence. Preserve that label in both artifacts and discussion. Inspect these distinctions in the original evidence beside the returned observations, references, hypotheses and limitations:

- `packet_loss_rate` is configured DATA loss percentage; `packets_dropped` is simulator-observed drops. Retransmissions or configured loss do not measure all network loss, and timeouts alone do not prove congestion.
- `acks_received` counts arrivals, including repeats. It is distinct from newly acknowledged DATA sequences and unique receiver-delivered bytes. Do not infer payload delivery by multiplying ACK arrivals by chunk size.
- `packets_acked` counts distinct DATA sequences confirmed by valid cumulative ACK progress; do not describe it as duplicate/stale ACK arrivals. `payload_bytes_delivered` establishes accepted/written delivery, not verified contents, even when it equals source size.
- Aggregate timeout and retransmission counts do not establish event order or that one caused the other. A causal claim needs linked event evidence.
- Null duration, throughput, RTT or overhead remains unavailable with its reason. Do not replace it with zero or derive missing measurements from other counters.
- Sender protocol confirmation and SHA-256 verification are separate facts. A receiver verification result does not establish sender confirmation; an unconfirmed result must not become verified completion.
- Numbers in prose and structured citations must agree with the supplied values and units. A valid citation elsewhere in an answer does not justify an invented number or causal claim.
- One run does not establish that another setting would be faster. Hypotheses must remain conditional, with the missing evidence stated.
- `FAILED` integrity supplies an outcome, not the cause or timing of corruption. `UNCONFIRMED` supplies no reason unless one is explicitly included. Every proposed cause must itself be qualified; a hypotheses heading is insufficient. An empty hypotheses list is acceptable.
- Attribute fields only to the endpoints/simulator indicated by their definitions. State supplied state/integrity as outcome facts and keep SYNTHETIC explicit in the returned prose.

The configured-loss/ACK fixture deliberately supplies 12.5% configured loss and 12,000 ACK arrivals, while observed simulator drops are null with a missing reason. It does not combine invented drops with the successful lossless baseline or imply that configured probability proves any actual drop count.

The accepted fixture helper's typed producer-style metadata is test-only, not an implemented producer schema. Only evidence explicitly included in a request can support the answer. Do not claim the model saw metadata merely because it exists elsewhere in the fixture. Real identity association and producer/outcome reconciliation remain milestone 9 work.

## Saved results and human review

Each live execution creates a new directory beneath `target/milestone-8-eval/` with a timestamp and unique suffix; it does not overwrite earlier evidence. Preview creates no live evidence. The JSONL report preserves the prompt, expectation, proposal/draft, Java decision, actual result and failure. A decoded draft rejected by Java's identity/citation checks is retained separately as untrusted model output for failure review; it cannot replace the original evidence or become an accepted explanation. The report includes configured/returned model identifiers, prompt versions, UTC date and available per-attempt token usage/API latency. It does not contain credentials, authorization headers or raw secret-bearing HTTP exchanges.

API latency belongs to the model request. It is not protocol elapsed time, throughput or a substitute for Person 2's transfer timing. Missing usage or wire identity remains unavailable. The local demonstration's files/outcomes are real; fixture values are authored examples.

Review `report.jsonl` with the generated `review.md` worksheet:

1. Check the model, batch, UTC date, budgets and evidence labels. Preserve the original report when a later run improves a failure.
2. Review each proposal and Java decision against its expected command/arguments or no-dispatch behavior. Distinguish infrastructure/HTTP failures from interpretation errors.
3. Read every plain-language response and explanation. Mark invented values, unit changes, inappropriate clarification, unsupported advice and unjustified causal claims even when automatic checks pass.
4. Record pass/fail, the relevant evidence and any proposed change in the worksheet. Check the real start/status outcome and independent rejection demonstration separately.
5. Share the report/worksheet paths or their nonsecret contents for review before any further paid batch or milestone 9 work. Fix justified issues, run relevant offline regression checks, and deliberately rerun only the affected live cases when needed.

### Reviewed September 21 results

The user ran both batches; the assistant reviewed the original reports with an independent second review. Model requested: `gpt-5-mini`; every completed response reported `gpt-5-mini-2025-08-07`. Prompt versions were `commands-v2` / `explanations-v3`.

| Run | Actual result |
| --- | --- |
| Smoke, starting 17:04:42 UTC / 21:04:42 Dubai | Five API attempts, four completed. Real start/status, missing essentials and unsupported-operation restrictions passed review with minor wording notes; injected Java rejection also passed. A 36-byte real sample completed with VERIFIED integrity and matching SHA-256 hashes. `loss-acks` timed out at 30 seconds; `missing-performance` was not run. |
| Explanations, 17:14:15-17:17:12 UTC / 21:14:15-21:17:12 Dubai | Four API attempts, four completed using a 90-second HTTP deadline. All automatic reference checks passed. Manual review found corrections needed in all four drafts under the strict causal/semantic rubric; Java rejection passed again. |

Findings: `loss-acks` conflates delivered bytes with integrity evidence; `missing-performance` makes an unsupported timeout-to-retransmission causal inference despite correctly refusing missing performance values; `integrity-failure` asserts post-write corruption without supporting cause/timing evidence; `unconfirmed-outcome` asserts an unsupported reason and blurs repeat ACK arrivals with distinct acknowledged progress. Numerical references, units and missing-value handling otherwise passed. These original live verdicts remain unchanged. The September 22 correction and actual retest are recorded separately below.

- [Smoke review](../target/milestone-8-eval/2026-09-21T17-04-41.966257700Z-14171895076588930966/review.md) and [original smoke report](../target/milestone-8-eval/2026-09-21T17-04-41.966257700Z-14171895076588930966/report.jsonl).
- [Explanation review](../target/milestone-8-eval/2026-09-21T17-14-14.616125800Z-17071091827319850136/review.md) and [original explanation report](../target/milestone-8-eval/2026-09-21T17-14-14.616125800Z-17071091827319850136/report.jsonl).

These are local, Git-ignored artifacts; share the selected run directories separately. The [repository handoff](repository-and-milestone-handoff.md) retains the current portable summary and exact paths. Its historical Word copy is now locally deleted; that existing deletion is preserved. Original reports remain unchanged, including their pre-review PENDING markers and the timeout. Use the worksheets for subsequent manual verdicts.

Across both runs: nine API attempts, eight completed responses, 26862 known total tokens; timed-out usage is unknown. The explanation calls took 37.7-49.9 seconds, measured as API latency, not transfer time. The 90-second setting was a user-terminal override, not a changed Java default. No real producer logs or impaired measured integration were evaluated. The wider `commands` batch has not run live. The existing launcher selects fixed batches; a targeted explanation retest currently means the four-case `explanations` batch. No additional paid run is implied by this record.

### Reviewed September 22 explanations-v4 retest

The user previewed and deliberately ran the existing four-case explanations batch. The assistant read all returned prose against the actual `analysis_input`, with an independent second review. Hidden fixture metadata was excluded from grading. Artifacts: [original report](../target/milestone-8-eval/2026-09-22T06-58-35.590646700Z-9150417386280831162/report.jsonl) and [completed manual review](../target/milestone-8-eval/2026-09-22T06-58-35.590646700Z-9150417386280831162/review.md).

The report confirms `commands-v2` / `explanations-v4`, requested `gpt-5-mini`, returned `gpt-5-mini-2025-08-07` on all four responses, one attempt per call, a 90-second request deadline and 4,096 output tokens per call. All four completed between 06:58:36 and 07:00:38 UTC (10:58:36-11:00:38 Dubai), without timeout/retry. All automatic reference checks passed. The separate unpaid injected Java rejection also passed with zero service starts.

| Case | Original targeted issue in this retest | Remaining manual finding |
| --- | --- | --- |
| loss-acks | Corrected in this sample: delivered bytes are delivery evidence; VERIFIED is stated separately | Emitted-byte accounting is proposed as an alternative for measuring actual drops. Aggregate emissions alone cannot establish suppressed DATA attempts or whether configured loss was applied |
| missing-performance | Corrected in this sample: counts are reported without asserting timeout-caused retransmission; missing throughput/duration stay unavailable | Returned prose omits SYNTHETIC despite its presence in the wrapper. Mixed sender/receiver counters have an ambiguous heading |
| integrity-failure | Corrected in this sample: post-write corruption is explicitly one possibility; FAILED is preserved | Clarify reported failed verification versus unknown cause/details. Emission accounting does not verify content equality. Broad duplicate-count wording should name the applicable arrival counter |
| unconfirmed-outcome | Corrected in this sample: causes are qualified and distinct packets_acked is correctly defined | Final conclusion incorrectly says whether progress alone proves success/integrity cannot be answered. The answer is no; the cause remains unknown. Earlier text correctly states insufficiency |

All structured numerical citations match the supplied evidence. Every missing field is named with a corresponding reason; grouping/paraphrasing preserves meaning. The original errors were not repeated in these four answers, but this is not an unconditional pass for all prose or proof of consistent future model behavior. In particular, FAILED alone does not authorize inventing a specific hash mismatch, and hidden receiver-verification metadata cannot decide the UNCONFIRMED case.

Usage: 8,676 input + 13,442 output = 22,118 total tokens; 9,472 reasoning tokens are included in output, and cached input is zero. Per-call API latency was 36,932 / 26,999 / 29,664 / 27,657 ms, in case order above. These are API timings, not transfer timings or proof that the prompt caused a latency improvement. Across the three runs, known usage is 48,980 tokens; the original timed-out request's usage is still unknown.

That review filled only the new worksheet and updated documentation. The raw report and both September 21 reports/reviews remain unchanged. At the review checkpoint, `explanations-v4` was the source version evaluated; no further prompt change, build/test or paid call was made during that review. The user subsequently authorized the v5 refinement below. The saved v4 findings are not v5 results. Broader command coverage and genuine real-evidence integration remain pending; stop before milestone 9.

## Prompt, interface and encoding fixes

Command prompt `commands-v2` makes optional Java defaults explicit, avoids unnecessary confirmation, keeps unsupported-operation replies within the available console commands, and distinguishes conflicting current/last requests. It retains Java's authority over bounds and dispatch. The September 21 smoke review supports start/status, missing essentials and unsupported-operation handling in those cases. Minor wording issues remain: clarification exposes Java/null terminology, and deletion refusal unnecessarily redirects toward sending a file. Wider command categories still need live evaluation.

Explanation prompt `explanations-v3` was used for the September 21 results. September 22's first revision, `explanations-v4`, was live retested; the subsequent current revision, `explanations-v5`, has offline checks only. Both changes are described below. The explanation flow, no-tools boundary and REAL evidence gate are retained. Display wording identifies accepted field scope and pending producer/shared integration rather than asking for field agreement again.

### September 22 v4 correction: implemented and live reviewed with remaining findings

The v4 production change was confined to [ExplanationRequest.java](../src/main/java/nettransfer/explanation/ExplanationRequest.java): `PROMPT_VERSION` advanced to `explanations-v4`, and `INSTRUCTIONS` gained general evidence rules. No fixture IDs, numeric answers or case-specific responses were hardcoded.

| Change | Why it is needed |
| --- | --- |
| Separate delivered bytes, ACK progress and integrity; use the supplied integrity outcome for verification claims | A byte count says how much was accepted/written, not whether contents match |
| Distinguish repeat-inclusive `acks_received` from distinct cumulative progress in `packets_acked` | Repeated ACK arrivals must not redefine the distinct-progress field |
| Treat aggregate counts as counts, not a causal/event timeline | A timeout count and a resend count do not establish that the timeout caused the resend |
| Require uncertainty in each proposed cause; allow no hypotheses | A statement that corruption "occurred" remains an assertion even under a hypotheses heading |
| Do not infer reasons/timing from FAILED or UNCONFIRMED, or events from missing information | The request supplies outcomes but no causal explanation for them |
| State supplied state/integrity in limitations, as nonnumeric facts | The existing observation references accept numbers only; a count must not masquerade as an integrity citation |
| Keep SYNTHETIC explicit, follow endpoint definitions, and group missing fields only when they share a reason | Addresses presentation findings while retaining all missing reasons within the existing output bounds |

`ResponsesExplanationClient` already sends the field definitions, state and integrity separately, so its schema and evidence projection need no change. `ExplanationFlow` still checks identity and exact numeric values/units; its acceptance message still requires prose review. Real explanations still return `EVIDENCE_UNAVAILABLE`. The model, command prompt, HTTP defaults, output limit, fixtures and fixed-batch runner are unchanged. No UDP, producer logging/calculation or integration code was changed.

Existing offline tests cover the serialized request across seven fixture variants, missing reasons, outcome separation, numeric validation, rejected tool output, the real-evidence gate and the runner's opt-in boundaries. No test was added that pretends exact instruction/model wording proves semantic quality. Fresh verification is recorded separately below; 603/32 remains the historical full-suite result.

### Subsequent v5 refinement: implemented and offline-checked, NOT live-tested

The user authorized a small general correction now, with paid acceptance deferred until Person 2's real-log integration is available. Only `ExplanationRequest.java` production code changes in this step. `PROMPT_VERSION` advances from `explanations-v4` to `explanations-v5`, so any future report identifies the new instructions separately from the saved v4 answers.

| Instruction change from v4 | Reason |
| --- | --- |
| Put supplied provenance in limitations; explicitly say SYNTHETIC test fixture there when applicable | The missing-performance draft omitted the label from its own prose; the surrounding report should not be its only label |
| Apply field definitions in hypotheses/limitations too; name the counter for duplicate claims and attribute mixed endpoint groups per field | Prevent broad ACK/packet or sender headings from changing the meaning of particular counters |
| Separate what evidence establishes from unknown causes; give a direct, internally consistent conclusion and identify the genuinely unanswered question | The UNCONFIRMED answer can say counters do not prove success even though the cause is unknown |
| Put nonnumeric answers and state/integrity in limitations | Preserve the existing numeric-only observation-reference contract without inventing citations |
| Retain FAILED as reported failed verification; do not weaken it to UNCONFIRMED or invent a particular checksum mismatch | Missing cause/comparison details do not erase the supplied outcome or supply a more specific failure |
| Request additional evidence that can test the proposed claim and explain its relevance | Aggregate UDP emission totals alone do not measure suppressed/dropped DATA attempts or verify contents; simulator decisions/linked DATA events and content-comparison/verification records support different claims |
| Do not require emission accounting or unrelated missing metrics merely to report the supplied integrity outcome | Missing byte accounting does not block reporting a known outcome; simulator suppression also must not become proof of all network loss |

These are interpretation rules for supplied evidence, not training, fixture answers, new producer requirements or a universal checklist of logs to collect. No new tests asserting exact model phrasing were added; the existing boundary/HTTP/CLI/runner tests were reused. `ResponsesExplanationClient`, `ExplanationFlow`, the JSON schema, numeric citation checks, missing-value reasons, no-tools restriction, request/output bounds, command prompt, `gpt-5-mini`, timeout defaults, fixtures and fixed-batch runner are unchanged. Real explanations still return `EVIDENCE_UNAVAILABLE`.

Files changed in this v5 step:

| File | What changed |
| --- | --- |
| `src/main/java/nettransfer/explanation/ExplanationRequest.java` | Version and instruction refinements listed above; no record fields or Java validation changes |
| `README.md` | Current v5/offline-only status, verification distinction and deferred paid testing |
| `docs/person-3-milestone-8.md` | This exact change record, separate v5 verification and next-step sequence; v3/v4 history retained |
| `docs/person-3-task-checklist.md` | Separate implemented/offline-checked v5 items from pending live validation and integration |
| `docs/repository-and-milestone-handoff.md` | Current v5 handoff status and deferred acceptance; historical v4 findings retained |
| `docs/person-2-integration-note.md` | Interface refinement status and the checks needed after Person 2's delegated integration |

All three saved live-run directories and their completed reviews remain untouched. Existing handoff/Word-file deletions and untracked `data/` are preserved. New offline logs, report copies and snapshots are saved in the separate v5 artifact directory below; Surefire also retains the uniquely suffixed reports in `target/surefire-reports/`. Changes remain uncommitted; no push, merge or teammate contact occurred.

### What comes next after this offline-only change

1. Keep v5 as implemented but not live-verified. No further paid batch is planned now; offline checks cannot prove GPT will follow the instructions.
2. Let Person 2 complete the already delegated merge and connection of genuine measurements. Review the integrated request's actual definitions, units, missing reasons, endpoint attribution, application/run/wire identity and state/integrity reconciliation; run relevant offline integration checks before paid acceptance. This step does not implement that work or relax the current REAL gate.
3. Once genuine records reach the correct explanation path, deliberately choose a small `gpt-5-mini` acceptance test with an explicit call budget and bounded deadlines. Inspect every resulting answer against the exact evidence supplied and save new results separately. Do not infer causes from hidden metadata or treat API latency as transfer time.
4. The fixed `explanations` batch remains a synthetic regression option. It does not validate the real-log connection; the existing `real` batch covers start/status, not real measured explanations. Select the real-evidence test entry point after inspecting the integrated implementation rather than assuming these old batches exercise it.

Broader command coverage still remains pending outside this correction task. Neither this v5 change nor Person 2's merge alone completes milestone 8. Stop here before milestone 9 work in this workspace.

### Earlier deliberate four-call retest procedure (v4 history)

The user completed this procedure for the September 22 v4 retest reviewed above. The commands are retained as a reference; running them with current source would use v5, whose paid testing is deferred. Do not repeat automatically. Preview in the project PowerShell terminal:

```powershell
$env:OPENAI_MODEL = 'gpt-5-mini'
.\scripts\milestone-8-eval.ps1 -Batch explanations
```

Stop and inspect the preview. It should say `PREVIEW - no API calls or UDP transfers`, `model=gpt-5-mini`, `batch=explanations`, and four planned calls: `loss-acks`, `missing-performance`, `integrity-failure`, `unconfirmed-outcome`. Preview displays the case questions and review expectations; it does not print the full system instructions. Those are in `ExplanationRequest.java`.

Only after reviewing that preview and deliberately choosing to spend credit, use the following in the same privately configured terminal:

```powershell
$env:OPENAI_MODEL = 'gpt-5-mini'
$env:OPENAI_REQUEST_TIMEOUT_MS = '90000'
.\scripts\milestone-8-eval.ps1 -Batch explanations -Live -MaxCalls 4
```

This is at most four calls, one attempt per request, 4,096 output tokens each, with a 90-second HTTP deadline per call. The timeout is a terminal override; the application default remains 30 seconds. This setting persists for later launches in that terminal until restored or the terminal is closed. It is unrelated to transfer timing. A key alone is not authorization, and no automatic paid run is part of this correction.

Keep the newly printed artifact directory. The existing runner creates a unique directory and retains failures; do not reuse old report paths or automatically retry a timeout. Confirm the version actually under test (`explanations-v4` in the saved September 22 run; current source is v5), requested `gpt-5-mini`, actual returned model, and recorded bounds. Read every observation, hypothesis and limitation against that case's actual `analysis_input`, including definitions/state/integrity. Ignore `fixture_metadata_NOT_sent_to_model` when judging claims. Record findings in the new `review.md`, preserving earlier directories. Only actual prose review can establish whether a prompt correction worked. The current v5 status is still NOT live-tested.

### Historical console encoding fix

The prior launcher mixed explicit UTF-8 input with platform-default output. The reported glyphs have a plausible separate encoding explanation: CP1252 bytes for smart apostrophe, em dash and opening double quote decode under CP850 as `Æ`, `ù` and `ô`. This reproduces the character mapping, but the user's terminal needs a repeat to confirm its actual encoding setup.

The launcher now uses `System.console()` reader/writer when a native console is available, preserving that console's character encoding. Redirected/IDE execution without a Console uses explicit UTF-8 input and output. Configure a native terminal's code page before launching Java; make UTF-8 pipes/IDE settings match the fallback. This fixes an encoding boundary without changing model accuracy expectations or forcing every Windows console to UTF-8.

## Verification

The full-suite figures in this section describe the September 21 checkpoint, not a September 22 rerun. Each v4/v5 focused check is a separate run of the same selection, not additional unique test coverage.

**Fresh v5 offline check, September 22 at 11:51 Dubai:** 151 tests across five classes passed, with zero failures/errors/skips: `ResponsesExplanationClientTest` (71), `ExplanationBoundaryTest` (21), `ExplanationFlowTest` (27), `TransferCliExplanationTest` (19), and `Milestone8EvaluationTest` (13). The compiled request class was checked for `explanations-v5`. Existing tests exercise request transmission, exact field values/definitions/missing reasons, identity/citation validation, no-tools handling, the real gate and evaluation opt-in boundaries. They do not test live model adherence.

```powershell
mvn -o '-Dtest=ResponsesExplanationClientTest,ExplanationBoundaryTest,ExplanationFlowTest,TransferCliExplanationTest,Milestone8EvaluationTest' '-Dsurefire.reportNameSuffix=explanations-v5-20260922-114920-fb08af01' test
```

Java 17 was selected from the existing `.vscode/settings.json`. New artifacts: [target/milestone-8-offline/explanations-v5-20260922-114920-fb08af01/](../target/milestone-8-offline/explanations-v5-20260922-114920-fb08af01/), containing the Maven log, copied uniquely suffixed XML/text reports, verification summary, preservation manifest and pre-edit snapshots. All **98 earlier live/offline evaluation and Surefire files** were hash-checked unchanged. There are still three saved live-run directories; no v5 live run exists. No `clean`, new JAR packaging, full-suite rerun or paid call was performed. The packaged JAR was not refreshed by this focused test command; the evaluation launcher compiles current source before use.

**Earlier v4 correction check, September 22:** 151 tests across five classes passed, with zero failures/errors/skips: `ResponsesExplanationClientTest` (71), `ExplanationBoundaryTest` (21), `ExplanationFlowTest` (27), `TransferCliExplanationTest` (19), and `Milestone8EvaluationTest` (13). This was a focused offline run, not a new full-suite verification or JAR packaging. The compiled request class was checked for `explanations-v4` at that checkpoint. Runner tests use stubs/local endpoints; their printed case results are not new paid evaluation results.

Java 17 was selected from the existing `.vscode/settings.json`. The exact Maven command was:

```powershell
mvn -o '-Dtest=ResponsesExplanationClientTest,ExplanationBoundaryTest,ExplanationFlowTest,TransferCliExplanationTest,Milestone8EvaluationTest' '-Dsurefire.reportNameSuffix=explanations-v4-20260922-101422-8f350a37' test
```

The unique report suffix preserved old Surefire XML/text reports. The new log, copied reports, preservation manifest and verification summary are under [target/milestone-8-offline/explanations-v4-20260922-101422-8f350a37/](../target/milestone-8-offline/explanations-v4-20260922-101422-8f350a37/). All 71 pre-existing files in the saved live-evaluation folders and Surefire report directory were hash-checked unchanged. No `clean`, paid API call or new packaging was run. These artifacts remain local and Git-ignored.

The assistant also ran `.\scripts\milestone-8-eval.ps1 -Batch explanations` with `OPENAI_MODEL=gpt-5-mini`. Compilation and preview succeeded, listing all four expected cases and explicitly reporting no API calls or UDP transfers. Its output is `explanations-preview.log` in that new offline directory. This preview is not a live result or a prose-quality check.

The following counts are historical:

The pre-edit full-suite baseline is 566 tests in 30 classes. Focused milestone 8 checks passed:

| Focused check | Result |
| --- | --- |
| Evaluation runner | 13 tests passed |
| Telemetry and client handling | 149 tests passed, including 23 new telemetry cases |
| Prompt/console and explanation boundaries | 180 tests across five classes passed, including one new console test |

These focused selections overlap and must not be added as a total test count. The prompt/console command was:

```powershell
mvn -o '-Dtest=TransferCliMainTest,ResponsesGptClientTest,ExplanationFlowTest,ResponsesExplanationClientTest,TransferCliExplanationTest' test
```

Final approved `mvn -o verify` passed **603 tests across 32 classes**, with **zero failures, errors or skips**, and packaged the runnable JAR. This is the 566-test baseline plus 13 runner tests, 23 telemetry tests and one console test. The preceding sandbox attempt had only the known pre-existing temporary Windows file-ACL permission error; the approved rerun passed that check too.

Java 17 was used, with no `clean`. The fallback console check verifies actual UTF-8 bytes and readback; prompt checks verify intended instructions rather than claiming live adherence. Preview prints case questions and expectations without paid requests; no live call was made during that offline verification. The user subsequently ran the live batches recorded above. At the offline checkpoint, `target/manual-input.txt` and `target/manual-received.txt` were present at 5,405 bytes each, and the pre-existing generated POM was preserved. It is no longer present in the current working tree; this documentation update does not recreate it.

No live model-quality result is established by compilation, preview or local HTTP/UDP testing. The production engine, Person 2's logging/calculations and real-summary gate remain unchanged. Existing artifacts are preserved; the packaging configuration avoids regenerating the historical reduced POM.

## Pending work through milestone 8

| Pending item | Dependency / owner | Effect on current milestone |
| --- | --- | --- |
| Actual engine observations and wire identity association | Shared integration coordinated with Person 1; Person 2 may instrument Stage 11 | Needed for detailed real progress and measured integration; does not block basic real start/status or synthetic evaluation |
| Finalized real summaries and raw event logs, including failures | Person 2 reports completion on her branch; inspect actual output during delegated integration | Required for real measured explanations; not yet inspected/merged here and fixtures cannot replace them |
| Concrete serialization/types/nulls, schema/definition versions, location, events, finalization and endpoint attribution | Technical producer handoff with Person 2 and relevant engine coordination | Field scope is accepted; no repeated scope approval is needed |
| Verified experiment/application/wire association, metadata/outcome reconciliation and semantic consistency | Shared identity inputs; real-log connection now delegated to Person 2, with Person 3 review | Remaining milestones 6/9 integration and verification |
| Control recovery, peer/transfer validation, receiver output protection/restart behavior for impaired scenarios | Coordinate engine/receiver follow-up with Person 1 | Before full milestone 9 impairment demonstrations; engine changes excluded from this task |
| Live acceptance of v5 refinements and wider command coverage | Person 3 interface follow-up; real integration remains delegated to Person 2 | v4 findings preserved; v5 implemented/offline-checked, NOT live-tested. Paid acceptance deferred until genuine integration is available and its inputs checked |

Person 1's core reliability completion is preserved. This table does not reclassify Stage 11 instrumentation as a separate unfinished engine that Person 1 alone must build. Optional Maven Wrapper work is not a prerequisite. No teammate messages are sent automatically.

## Review stop and Git state

Stop before milestone 9. Review the diff and the actual evaluation status first. The earlier infrastructure/staging checkpoint is already committed as `ca5990c`; do not replay its old staging list. The September 22 prompt/documentation correction remains local and uncommitted.

```powershell
git status --short
git diff --check
git diff --stat
git diff
```

Plain `git diff` omits untracked files. Preserve the deliberate deletion of `docs/person-3-handoff.md`, the existing local Word-document deletions, untracked `data/`, credentials and local `target/` evidence. No staging, commit, push, merge or teammate contact is performed automatically. The Markdown handoff notes carry the current portable summary; their historical Word copies are locally deleted and were not restored. Neither offline success nor a passing automatic live reference check completes milestone 8.
