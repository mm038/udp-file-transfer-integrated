# Milestone 8: bounded live GPT evaluation before the impaired demo

Updated September 21, 2026 after the user-run live batches. **Small smoke and synthetic explanation evaluations RUN / REVIEWED WITH FINDINGS; milestone 8 remains incomplete.** Real start/status and command boundaries passed their recorded reviews. Explanation corrections/retests and wider command coverage remain open. This documentation update performs no milestone 9 work.

## Current state and evidence boundaries

The working branch is `feature/LLM-integration`, tracking origin. The old local `person-3/llm-integration` branch was deleted. Milestone 6 HTTP implementation is committed, and milestone 7 is committed as `f1be0c8`. Its recorded baseline is 566 passing tests across 30 classes and successful JAR packaging. Older documents' uncommitted HTTP, old branch and review-stop statements describe their historical checkpoints.

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
- Null duration, throughput, RTT or overhead remains unavailable with its reason. Do not replace it with zero or derive missing measurements from other counters.
- Sender protocol confirmation and SHA-256 verification are separate facts. A receiver verification result does not establish sender confirmation; an unconfirmed result must not become verified completion.
- Numbers in prose and structured citations must agree with the supplied values and units. A valid citation elsewhere in an answer does not justify an invented number or causal claim.
- One run does not establish that another setting would be faster. Hypotheses must remain conditional, with the missing evidence stated.

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

Findings: `loss-acks` conflates delivered bytes with integrity evidence; `missing-performance` makes an unsupported timeout-to-retransmission causal inference despite correctly refusing missing performance values; `integrity-failure` asserts post-write corruption without supporting cause/timing evidence; `unconfirmed-outcome` asserts an unsupported reason and blurs repeat ACK arrivals with distinct acknowledged progress. Numerical references, units and missing-value handling otherwise passed. Corrections have been documented, not implemented or retested.

- [Smoke review](../target/milestone-8-eval/2026-09-21T17-04-41.966257700Z-14171895076588930966/review.md) and [original smoke report](../target/milestone-8-eval/2026-09-21T17-04-41.966257700Z-14171895076588930966/report.jsonl).
- [Explanation review](../target/milestone-8-eval/2026-09-21T17-14-14.616125800Z-17071091827319850136/review.md) and [original explanation report](../target/milestone-8-eval/2026-09-21T17-14-14.616125800Z-17071091827319850136/report.jsonl).

These are local, Git-ignored artifacts; share the selected run directories separately. The [repository handoff](repository-and-milestone-handoff.md) and its Word copy retain a portable summary and exact paths. Original reports remain unchanged, including their pre-review PENDING markers and the timeout. Use the worksheets for subsequent manual verdicts.

Across both runs: nine API attempts, eight completed responses, 26862 known total tokens; timed-out usage is unknown. The explanation calls took 37.7-49.9 seconds, measured as API latency, not transfer time. The 90-second setting was a user-terminal override, not a changed Java default. No real producer logs or impaired measured integration were evaluated. The wider `commands` batch has not run live. The existing launcher selects fixed batches; a targeted explanation retest currently means the four-case `explanations` batch. No additional paid run is implied by this record.

## Prompt, interface and encoding fixes

Command prompt `commands-v2` makes optional Java defaults explicit, avoids unnecessary confirmation, keeps unsupported-operation replies within the available console commands, and distinguishes conflicting current/last requests. It retains Java's authority over bounds and dispatch. The September 21 smoke review supports start/status, missing essentials and unsupported-operation handling in those cases. Minor wording issues remain: clarification exposes Java/null terminology, and deletion refusal unnecessarily redirects toward sending a file. Wider command categories still need live evaluation.

Explanation prompt `explanations-v3` reinforces ACK-arrival versus unique-delivery semantics, missing measurements, and sender confirmation versus integrity. The live review above nevertheless found semantic and causal errors; stronger prompt/interface handling and a targeted retest remain open. The explanation flow, no-tools boundary and REAL evidence gate are retained. Display wording identifies accepted field scope and pending producer/shared integration rather than asking for field agreement again.

The prior launcher mixed explicit UTF-8 input with platform-default output. The reported glyphs have a plausible separate encoding explanation: CP1252 bytes for smart apostrophe, em dash and opening double quote decode under CP850 as `Æ`, `ù` and `ô`. This reproduces the character mapping, but the user's terminal needs a repeat to confirm its actual encoding setup.

The launcher now uses `System.console()` reader/writer when a native console is available, preserving that console's character encoding. Redirected/IDE execution without a Console uses explicit UTF-8 input and output. Configure a native terminal's code page before launching Java; make UTF-8 pipes/IDE settings match the fallback. This fixes an encoding boundary without changing model accuracy expectations or forcing every Windows console to UTF-8.

## Verification

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

Java 17 was used, with no `clean`. The fallback console check verifies actual UTF-8 bytes and readback; prompt checks verify intended instructions rather than claiming live adherence. Preview prints full prompts and expectations without paid requests; no live call was made during that offline verification. The user subsequently ran the live batches recorded above. At the offline checkpoint, `target/manual-input.txt` and `target/manual-received.txt` were present at 5,405 bytes each, and the pre-existing generated POM was preserved. It is no longer present in the current working tree; this documentation update does not recreate it.

No live model-quality result is established by compilation, preview or local HTTP/UDP testing. The production engine, Person 2's logging/calculations and real-summary gate remain unchanged. Existing artifacts are preserved; the packaging configuration avoids regenerating the historical reduced POM.

## Pending work through milestone 8

| Pending item | Dependency / owner | Effect on current milestone |
| --- | --- | --- |
| Actual engine observations and wire identity association | Shared integration coordinated with Person 1; Person 2 may instrument Stage 11 | Needed for detailed real progress and measured integration; does not block basic real start/status or synthetic evaluation |
| Finalized real summaries and raw event logs, including failures | Person 2 reports completion on her branch; inspect actual output during delegated integration | Required for real measured explanations; not yet inspected/merged here and fixtures cannot replace them |
| Concrete serialization/types/nulls, schema/definition versions, location, events, finalization and endpoint attribution | Technical producer handoff with Person 2 and relevant engine coordination | Field scope is accepted; no repeated scope approval is needed |
| Verified experiment/application/wire association, metadata/outcome reconciliation and semantic consistency | Shared identity inputs; real-log connection now delegated to Person 2, with Person 3 review | Remaining milestones 6/9 integration and verification |
| Control recovery, peer/transfer validation, receiver output protection/restart behavior for impaired scenarios | Coordinate engine/receiver follow-up with Person 1 | Before full milestone 9 impairment demonstrations; engine changes excluded from this task |
| Explanation corrections/retests and wider live command coverage | Open interface evaluation follow-up; not automatically delegated with the merge | Small batches run/reviewed; outstanding milestone 8 findings can be addressed before real-log integration |

Person 1's core reliability completion is preserved. This table does not reclassify Stage 11 instrumentation as a separate unfinished engine that Person 1 alone must build. Optional Maven Wrapper work is not a prerequisite. No teammate messages are sent automatically.

## Review stop and local commit guidance

Stop before milestone 9. Review the diff and the actual evaluation status first. Milestone 6 HTTP and milestone 7 are already committed, so the older prerequisite staging instructions need not be replayed.

```powershell
git status --short
git diff --check
git diff --stat
git diff
```

Plain `git diff` omits untracked files. Inspect the new runner, launcher, telemetry record and tests before staging them. Stage only reviewed milestone 8 files; do not use `git add .`:

```powershell
git add pom.xml README.md docs/person-3-task-checklist.md docs/person-3-milestone-8.md
git add docs/repository-and-milestone-handoff.md docs/repository-and-milestone-handoff.docx docs/person-2-integration-note.md docs/person-2-integration-note.docx docs/live-demo-walkthrough.md
git add docs/person-3-milestone-5.md docs/person-3-milestone-6.md docs/person-3-milestone-6-http.md docs/person-3-milestone-7.md docs/Metrics_Summary_Revised.md docs/person-2-metrics-review.md
git add scripts/milestone-8-eval.ps1 src/test/java/nettransfer/evaluation/Milestone8LiveEvaluation.java src/test/java/nettransfer/evaluation/Milestone8EvaluationTest.java
git add src/main/java/nettransfer/llm/ApiCallObservation.java src/main/java/nettransfer/llm/ResponsesTransport.java src/main/java/nettransfer/llm/ResponsesGptClient.java src/main/java/nettransfer/llm/ResponsesExplanationClient.java
git add src/main/java/nettransfer/cli/TransferCli.java src/main/java/nettransfer/cli/TransferCliMain.java src/main/java/nettransfer/explanation/ExplanationRequest.java src/main/java/nettransfer/explanation/ExplanationFlow.java src/main/java/nettransfer/explanation/SummaryProvider.java
git add src/test/java/nettransfer/llm/ApiCallObservationTest.java src/test/java/nettransfer/llm/ResponsesGptClientTest.java src/test/java/nettransfer/llm/ResponsesExplanationClientTest.java
git add src/test/java/nettransfer/cli/TransferCliMainTest.java src/test/java/nettransfer/cli/TransferCliExplanationTest.java src/test/java/nettransfer/explanation/ExplanationFlowTest.java
```

Check these paths against the final `git status --short`, then inspect the full staged diff. The older `docs/person-3-handoff.md` is currently deleted in the working tree; that deletion is preserved and is excluded from the sample staging commands until separately reviewed. Do not stage `data/`, `data - Shortcut.lnk`, `dependency-reduced-pom.xml`, credentials or `target/` demo/report artifacts automatically. The updated repository handoff is the selected nonsecret evaluation summary for Git, with REAL/SYNTHETIC provenance intact; full raw run directories still need separate sharing.

```powershell
git diff --cached --check
git diff --cached --stat
git diff --cached
git commit -m "Add bounded milestone 8 live evaluation and review workflow"
```

These are local commands for the user after review, not actions performed by the assistant. The commit title describes infrastructure; it does not claim that pending live evaluation is complete. No push is performed automatically.
