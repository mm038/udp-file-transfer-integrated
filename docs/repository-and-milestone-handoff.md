# Repository guide and Person 3 milestone summary

Person 3 handoff to Person 2 | Updated 22 September 2026

## What this handoff covers

Person 2 is taking over the branch merge and the connection between her real logs/measurements and the existing LLM interface. The user reports that her metrics and logging are complete on her side; that implementation has not yet been merged into or inspected in this workspace. Older notes saying those outputs were pending describe the earlier local state.

The existing interface already interprets requests, validates them in Java, starts real transfers, and reports basic status. It also has a separate explanation client and evidence checks. The main remaining connection is to supply verified real evidence instead of test fixtures. Person 3 will review GPT behaviour and participate in final testing/demo review.

Git inspection on September 22 found branch `feature/LLM-integration` at `ca5990c`, “Added live evaluation and reviewed handoff findings”. The original milestone 8 implementation and handoff documents are committed. The user reports that commit was pushed; this update does not claim a fresh remote fetch verification. The locally deleted `docs/person-3-handoff.md` remains deleted and excluded from that commit, and `data/` remains untracked. Older uncommitted-checkpoint instructions do not describe this inspected state.

## Live tests performed and reviewed on 21 September

The user ran two deliberate live batches; the assistant reviewed their saved outputs against the supplied evidence, with an independent second review. Milestone 8 is partially evaluated and remains open. Automatic checks passing does not mean the explanation prose is accurate.

| Test | Reviewed outcome |
| --- | --- |
| Real natural-language start and basic status | PASS. One 36-byte loopback transfer completed with VERIFIED integrity and matching source/received SHA-256 hashes. GPT selected the correct last transfer for status, without starting another. The requested 64 KiB window became 65536 bytes / 64 slots; Java supplied the default 200 ms timeout. |
| Missing essential information | PASS. Asked for the file and receiver without guessing or demanding optional settings. Minor wording: unnecessarily mentions Java/null defaults. |
| Unsupported deletion | PASS on operation restrictions: no dispatch, OS deletion advice or unsupported cancellation suggestion. Minor wording: unnecessarily offers to send the demo instead. |
| Deterministic Java rejection | PASS in both runs. An injected invalid timeout was rejected without starting a transfer. This check did not call GPT. |
| Initial smoke explanation attempt | loss-acks timed out at the 30-second HTTP deadline; missing-performance was not run because the runner stopped. The timeout remains recorded. |
| Follow-up synthetic explanations | All four requests completed with a 90-second HTTP deadline and passed automatic reference checks. All four were flagged for prose corrections under the strict review rubric; see the historical findings below and September 22 retest status. |

The four explanation findings are: loss-acks labels delivered-byte counts as integrity evidence; missing-performance infers a timeout caused a retransmission from aggregate counts; integrity-failure asserts post-write corruption without evidence of the cause/timing; unconfirmed-outcome asserts an unsupported reason and confuses distinct acknowledged progress with repeat ACK arrivals. The missing-performance answer correctly refuses unavailable throughput/duration; its causal sentence is the reason it is not an unconditional pass. Numerical citations, units and missing-value handling otherwise passed review.

Requested model: gpt-5-mini. Every completed response reports gpt-5-mini-2025-08-07. Prompt versions: commands-v2 / explanations-v3. Smoke began at 17:04:42 UTC (21:04:42 Dubai); explanations ran 17:14:15-17:17:12 UTC (21:14:15-21:17:12 Dubai). There were nine API attempts across the two runs and eight completed responses. Known usage: 26862 total tokens; the timed-out request's usage is unknown, not zero. API latency is separate from UDP transfer timing.

## Exact result locations

Paths are relative to the repository root:

- Smoke: target/milestone-8-eval/2026-09-21T17-04-41.966257700Z-14171895076588930966/
- Explanations: target/milestone-8-eval/2026-09-21T17-14-14.616125800Z-17071091827319850136/
- September 22 v4 explanations retest: target/milestone-8-eval/2026-09-22T06-58-35.590646700Z-9150417386280831162/

Each directory contains report.jsonl (original prompts, proposals/drafts, evidence, Java decisions, API results and usage) and review.md (completed assistant review and open findings). The original reports retain their pre-review PENDING markers; consult review.md for the subsequent assessment. The smoke directory also contains data/input/demo.txt and received-demo.txt, both SHA-256 D896FEFCF45B29AD47F15CBB6F275B19431BDE5EB311E93E038C1E491891E0AF.

These target/ directories are local and ignored by Git. Pulling or merging the branch will not copy them. The September 21 handoff summary is committed; share the selected run directories separately if the recipient needs the full evidence. Preserve the original timeout and earlier findings alongside the retest. No credentials should be included in shared artifacts.

## September 22 explanation correction status

The first September 22 correction in `src/main/java/nettransfer/explanation/ExplanationRequest.java` used `explanations-v4`. This prompt-only correction separates delivered bytes from integrity verification, repeat-inclusive ACK arrivals from distinct acknowledged DATA progress, and aggregate counts from evidence of event causation. It also requires uncertainty within each causal hypothesis and the evidence needed to test it; hypotheses may be empty. The supplied state/integrity belong in limitations as nonnumeric facts, without invented numerical citations or unsupported reasons/timing. Synthetic labeling and supplied endpoint attribution are explicit.

These are general evidence rules, not fixture-specific answers. Numeric citation checks, missing-value reasons, tool-free explanations, Java validation and the real `EVIDENCE_UNAVAILABLE` gate remain in place. No UDP engine, producer logging/calculations, model selection or application timeout default was changed. The new changes are local and uncommitted. The September 21 runs used `explanations-v3`.

After previewing, the user deliberately ran the four-call `explanations` batch on September 22, 06:58:36-07:00:38 UTC (10:58-11:00 Dubai). All four `gpt-5-mini` requests returned `gpt-5-mini-2025-08-07`, using `commands-v2` / `explanations-v4`, one attempt per call, a terminal-only 90000 ms HTTP deadline and at most 4096 output tokens per call. All automatic checks passed. Reported usage was 8676 input + 13442 output = 22118 total tokens; 9472 reasoning tokens are included in output, and cached input tokens were zero. These API times are separate from synthetic transfer timing.

The assistant reviewed the actual new prose with an independent second review against `analysis_input`, including definitions/state/integrity, without using hidden fixture metadata. The four original substantive errors did not recur in these sampled answers. Remaining findings prevent a blanket full PASS: emission totals are suggested as evidence for measuring drops or required for verifying file contents; missing-performance omits SYNTHETIC in its prose and uses an ambiguous mixed-endpoint heading; integrity-failure needs a clearer failed-verification conclusion without assuming a literal content mismatch; and unconfirmed-outcome ends with an incorrect cannot-answer statement after correctly explaining that protocol progress alone proves neither success nor verified integrity. See [the milestone 8 record](person-3-milestone-8.md) and the new run's `review.md` for the per-case findings. This review made no additional source fixes, test runs or paid calls.

Following that review, the user authorized a narrow refinement. Current source now uses `explanations-v5`: suggested diagnostic evidence must be capable of testing the proposed claim; aggregate emission totals alone cannot establish DATA drops or verify contents. The prompt requires a direct, consistent answer about what the evidence establishes even when the cause is unknown, and preserves FAILED as reported failed verification without inventing a checksum mismatch. It places explicit provenance (including SYNTHETIC test fixture when applicable) in limitations and applies each field's counter meaning and endpoint attribution throughout observations, hypotheses and limitations.

The v5 changes remain uncommitted and have not been live-tested. No further paid call was made for this refinement. See [the milestone 8 record](person-3-milestone-8.md) for its separate offline verification status; passing offline checks does not establish the new model prose is correct. The v4 reports/reviews and earlier failures remain historical evidence, not v5 results.

## Recommended next work

Person 2 retains the delegated merge and real-log connection. Person 3 has implemented the v5 refinement for the reviewed residual findings; its live effectiveness remains unverified. This correction task does not perform the delegated integration or begin milestone 9. The real `EVIDENCE_UNAVAILABLE` gate remains in place until genuine evidence is integrated and verified.

Pause further paid retesting until Person 2's merge and genuine-log connection are available and the actual inputs, contracts, identities and outcomes have been checked. Then choose a small live acceptance test with fresh deliberate authorization, keeping `gpt-5-mini`, bounded calls/deadlines and a separate result directory; review the actual prose against only the evidence sent. The current launcher selects fixed batches, not individual cases, and its four-call explanations batch uses synthetic evidence, so it cannot validate the real connection by itself. An API key alone is not authorization. Broader command coverage remains pending outside this immediate correction task. Real measured explanations and the impaired demo need separate testing; milestone 8 remains incomplete.

## Where to find things

| Location | Plain-language purpose |
| --- | --- |
| src/main/java/ | The application source code: engine, console, validation, GPT clients and explanation flow. |
| src/test/java/ | Automated tests, supporting test data and the explicitly launched live evaluation program. |
| docs/ | Design notes, milestone records, checklists and handoff/demo instructions. Earlier documents include historical checkpoints. |
| scripts/ | Repeatable launch instructions. Currently contains milestone-8-eval.ps1, which starts the evaluation program. |
| data/input/ and data/received/ | Local source files and received copies used for transfers. These are local demo artifacts, not automatically included in Git. |
| target/ | Generated build output and test reports. This workspace also stores earlier demo evidence and temporary helpers here. |
| pom.xml | Maven's build configuration: Java 17, dependencies, testing and JAR packaging. |
| README.md and PROTOCOL.md | How to build/run the application, and documentation of the UDP protocol. |
| .git/, .gitignore and .vscode/ | Git history; rules for excluding generated/local files; and local editor/Java setup. Machine-specific settings may not travel with the branch. |

## The two main components and their connection

All paths below are inside src/main/java/nettransfer/.

| Code location | Responsibility |
| --- | --- |
| transfer/ | SenderEngine, ReceiverEngine and the reliability logic: chunks, window, timeouts and retransmissions. |
| protocol/, net/, integrity/ | Packet/control-message formats, UDP socket wrapper and file hashing. These support the engine. |
| cli/ | TransferCliMain opens the interactive console; TransferCli reads requests and displays Java results. |
| llm/ | GPT command interpretation, separate explanation HTTP client, configuration and safe API-call metadata. |
| control/command/ | Strict parsing, approved resources, parameter validation and dispatch. GPT proposals must pass this boundary. |
| control/engine/ | RealTransferService connects validated requests to the existing blocking engine using a background worker. |
| control/ and control/simulation/ | Shared request/status/outcome types and FakeTransferService for controlled synthetic tests. |
| explanation/ | SummaryProvider, RecordedSummary and ExplanationFlow: select evidence, check identity/references, and obtain a tool-free explanation. |

Command path: sentence -> GPT proposal -> Java validation -> real adapter -> UDP engine.

Intended measured-explanation path: real producer records -> verified evidence provider -> explanation client -> evidence and explanation shown together. The real provider connection is still to be implemented in this branch.

<!-- pagebreak -->

## Understanding target/

Editing a .java source file does not immediately change the executable program. Maven compiles source into .class files, runs tests, and packages the application as a JAR. Those outputs go under target/. Edit src/ and rebuild; do not edit compiled files in target/.

| Folder or file | Purpose |
| --- | --- |
| target/classes/ | Compiled application classes. Its nettransfer subfolders mirror the application packages in src/main/java/. |
| target/test-classes/ | Compiled tests, fixtures and the evaluation runner. Its package folders mirror src/test/java/. |
| target/generated-sources/ | Space for source code generated by build tools; the annotations subfolder may be empty. |
| target/generated-test-sources/ | Equivalent space for generated test source; test-annotations may be empty. |
| target/maven-archiver/ | Small packaging metadata files used by Maven. |
| target/maven-status/ | Compilation bookkeeping. The compile/testCompile subfolders record inputs and generated outputs. |
| target/surefire-reports/ | XML/text results from the ordinary JUnit tests: counts, failures and diagnostics. |
| target/milestone4-cli-20260920-115545-067025/ | Earlier real demo evidence: sender/receiver console output, verification.json, and isolated data/input and data/received files. |
| target/milestone-8-eval/ | Saved live evaluation artifacts. The two September 21 runs and September 22 v4 retest are listed above. Each contains report.jsonl and review.md; real-demo batches also retain their sample files. |
| target/udp-file-transfer.jar | The packaged runnable application, including Gson. This is the JAR used by the documented launch commands. |
| Other JARs, text/JSON files and .py helpers | Packaging intermediates, earlier smoke transcripts/hash evidence, extracted assignment text and temporary document/demo helpers. They are not a second application implementation. |

Build output is normally replaceable. However, earlier evidence is also stored in target/ here. Avoid mvn clean while that evidence needs preserving. Git ignores target/, so pulling the branch does not bring these local artifacts to another machine; share selected evidence separately if needed.

dependency-reduced-pom.xml, if encountered at the repository root, is an older Maven packaging by-product. The maintained build file is pom.xml. Current packaging disables generation of the reduced POM.

## Understanding scripts/ and evaluation batches

A .ps1 file is a PowerShell script: a saved sequence of commands. milestone-8-eval.ps1 compiles application/test source, then starts nettransfer.evaluation.Milestone8LiveEvaluation. It does not open the interactive transfer console.

A batch is a named group of prepared questions/checks, run one after another. Individual interactive testing is also valid. Grouping provides repeatability and a saved report.

| Batch | Checks included |
| --- | --- |
| smoke: at most 6 API calls | Real start/status, missing essentials, unsupported deletion, and two synthetic explanations. |
| commands: at most 12 API calls | Interpretation of starts, defaults, units, omissions, current/last references, explanation selection and invalid settings. Transfer execution is simulated. |
| explanations: at most 4 API calls | Explanation accuracy against labelled synthetic measurements, missing values and outcome distinctions. |
| real: at most 2 API calls | Natural-language start and status using a real local receiver and fresh sample files. |

The runner also injects an invalid proposal to demonstrate Java rejection without an API call. The actual case definitions live in src/test/java/nettransfer/evaluation/Milestone8LiveEvaluation.java; the PowerShell script selects and launches them.

<!-- pagebreak -->

## Tests, preview and live evaluation are different

Ordinary JUnit tests check the Java implementation with controlled inputs. Some use fake services or scripted model replies; others use actual local HTTP/UDP endpoints. They require no OpenAI credentials and make no paid API calls. A regression test checks that a change did not break behaviour that already worked.

The tests roughly mirror the source packages: control/command checks validation/dispatch; control/engine checks the adapter; cli checks console behaviour; llm checks request/response handling with local HTTP servers; explanation checks evidence rules and synthetic measurements; transfer/protocol/net/integrity check the engine and its supporting code.

Milestone8EvaluationTest tests the evaluator itself using stubs/local endpoints. Milestone8LiveEvaluation is a separate main program, not an automatically discovered JUnit test. It lives in test source so the paid runner and fixtures are excluded from the production JAR.

## The small command reference

Run from the repository root with Java 17 and Maven configured. Each command below is independent; there is no requirement to run all of them during the handoff.

| Command | What it does |
| --- | --- |
| mvn -o test | Run ordinary tests using cached Maven dependencies. Does not launch live GPT evaluation. |
| mvn -o verify | Run ordinary tests and package target/udp-file-transfer.jar. Preserve earlier evidence by omitting clean. |
| .\scripts\milestone-8-eval.ps1 -Batch smoke | Compile and preview the prepared smoke checks. No API call or UDP transfer. |
| .\scripts\milestone-8-eval.ps1 -Batch smoke -Live -MaxCalls 6 | Explicitly run the paid smoke batch, provided OPENAI_API_KEY is already configured privately in this terminal. |

The -o switch means Maven uses its local dependency cache. On a new machine without the dependencies, first use mvn verify to download the required build dependencies. The current PowerShell launcher expects Gson in the default user Maven cache and can use this workspace's .vscode Java path; configure the receiving machine's own JAVA_HOME as needed.

Live execution requires both -Live and a sufficient -MaxCalls. A key alone never starts these evaluation calls. OPENAI_MODEL is configurable; its default remains gpt-5-mini. The runner uses one HTTP attempt per call and stops further paid checks on API failures. Real smoke setup can also stop early when its transfer/preflight fails.

The terminal prints the new target/milestone-8-eval/<unique-run>/ directory. report.jsonl records prompts, proposals, Java decisions, expectations, failures, model IDs and available token usage/API latency. review.md is the human review worksheet. Automatic checks do not establish that every explanation sentence is accurate; read the prose beside the evidence. Synthetic records are explicitly labelled and never count as real measured integration.

## Interactive console versus the evaluation program

For a normal manual demonstration, start a receiver separately, then launch nettransfer.cli.TransferCliMain from the packaged JAR. You type one request at a time at transfer>. The evaluation program instead supplies its own prepared questions and, for real batches, starts its own local receiver.

Use README.md for the standard receiver/console commands. docs/live-demo-walkthrough.md explains the full intended demonstration and marks the impairment/real-evidence connections still pending in this branch. docs/person-3-milestone-8.md contains the detailed evaluation expectations and review procedure.

<!-- pagebreak -->

## Brief summary of Person 3's milestones

These are Person 3's interface milestones; their numbering is separate from Person 1's engine stages.

| Milestone | What was done and current status |
| --- | --- |
| 1. Establish the baseline | Set up Java/Maven, recorded a working engine build and matching-hash transfer, and drafted team boundaries. Shared observation/identity details remained integration work; a Maven Wrapper was optional. |
| 2. Shared types and simulation | Added common transfer requests, settings, status/outcome/error records, TransferService and a clearly synthetic fake service for repeatable tests. Independent implementation complete. |
| 3. Java validation and dispatch | Added strict command parsing, allowed files/receivers, parameter checks, unit/window handling and dispatch with protection against duplicate starts. Implemented and tested. |
| 4. Real engine and console | Added the background real-engine adapter and interactive console. Real starts and basic RUNNING/COMPLETED/FAILED states work while unavailable measurements remain explicit. |
| 5. GPT command interpretation | Added the GPT HTTP client, strict command proposals, clarification handling and current/last selection. Java remains the execution authority; API failures do not authorize a transfer. Implemented with offline tests; September 21 formal smoke start/status/clarification/unsupported-operation cases passed their recorded reviews with minor wording notes. |
| 6. Evidence-grounded explanation boundary | Added a separate tool-free explanation client, summary-provider boundary and identity/citation checks using synthetic evidence. Independent infrastructure is implemented; loading/reconciling real producer evidence remains integration work. |
| 7. Offline regression coverage | Audited and expanded tests, including all 26 accepted numerical fields and test-only supporting metadata. Independent offline scope complete: historical checkpoint was 566 tests. |
| 8. Live evaluation, partly reviewed | Added opt-in batches, semantic expectations, safe result/usage records, prompt improvements and console encoding fixes. September 21 smoke and follow-up explanations ran and were reviewed. Real start/status and command boundaries passed. September 22 v4 corrections were retested and the actual prose reviewed: original errors did not recur in this sample. The v5 refinement addresses residual findings but is not live-tested; wider command coverage also remains open. Milestone 8 is not complete. |
| 9. Real measured integration | Remaining work: merge producer implementation, connect real logs/observations, verify identity and semantics, and run actual impaired success/failure scenarios. This handoff delegates merge/log connection work to Person 2; this note implements none of it. |
| 10. Final documentation/demo | README and a draft walkthrough exist. Final measured examples, reproducibility instructions and rehearsal depend on the integrated system. Not marked complete. |

Historical full verification before the September 22 correction: 603 tests across 32 classes, zero failures/errors/skips, and successful JAR packaging. This is the earlier offline checkpoint. New correction-specific offline verification is recorded separately in [the milestone 8 record](person-3-milestone-8.md); neither checkpoint establishes compatibility with Person 2's unmerged code or overrides the live explanation findings.

## First steps for the merge and real-log connection

- Use the inspected `ca5990c` milestone 8 checkpoint and distinguish it from the new, uncommitted September 22 corrections. The user reports that checkpoint was pushed. Preserve the local deletion of `docs/person-3-handoff.md`, untracked `data/` and ignored `target/` evidence; this task does not stage, commit, push or merge them.
- Inspect Person 2's actual records/APIs, including units, missing values, finalization, endpoint evidence and experiment/application/wire ID association. The complete metric/supporting-field scope is already accepted.
- Start at explanation/SummaryProvider.java and ExplanationFlow.java for the real evidence connection; control/engine/RealTransferService.java and the shared status types are the observation connection points. The current REAL-evidence gate deliberately stops before provider/client calls. A loader alone will not enable real explanations: adapt and verify the flow for real evidence as part of integration.
- Preserve the Java parser/validator/dispatcher boundary, the distinction between measured and synthetic data, and sender success versus verified integrity. Do not fill missing measurements with fixture values or let GPT calculate missing producer metrics.
- After merging, run the offline checks, verify real success/failure evidence and detailed status, finish deliberate live GPT review, then rehearse the final impaired demonstration. Actual observer/identity changes should be coordinated with Person 1 where engine knowledge is needed.

[person-2-integration-note.md](person-2-integration-note.md) explains the technical dependencies in more detail. The historical `.docx` copies of that note, this repository guide and `Metrics_Summary_Revised.docx` are currently locally deleted; those deletions are preserved. Use the Markdown documents for current status. No merge, commit, push or additional live API call was performed to update these notes.
