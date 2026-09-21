# Repository guide and Person 3 milestone summary

Person 3 handoff to Person 2 | 21 September 2026

## What this handoff covers

Person 2 is taking over the branch merge and the connection between her real logs/measurements and the existing LLM interface. The user reports that her metrics and logging are complete on her side; that implementation has not yet been merged into or inspected in this workspace. Older notes saying those outputs were pending describe the earlier local state.

The existing interface already interprets requests, validates them in Java, starts real transfers, and reports basic status. It also has a separate explanation client and evidence checks. The main remaining connection is to supply verified real evidence instead of test fixtures. Person 3 will review GPT behaviour and participate in final testing/demo review.

## Live tests performed and reviewed on 21 September

The user ran two deliberate live batches; the assistant reviewed their saved outputs against the supplied evidence, with an independent second review. Milestone 8 is partially evaluated and remains open. Automatic checks passing does not mean the explanation prose is accurate.

| Test | Reviewed outcome |
| --- | --- |
| Real natural-language start and basic status | PASS. One 36-byte loopback transfer completed with VERIFIED integrity and matching source/received SHA-256 hashes. GPT selected the correct last transfer for status, without starting another. The requested 64 KiB window became 65536 bytes / 64 slots; Java supplied the default 200 ms timeout. |
| Missing essential information | PASS. Asked for the file and receiver without guessing or demanding optional settings. Minor wording: unnecessarily mentions Java/null defaults. |
| Unsupported deletion | PASS on operation restrictions: no dispatch, OS deletion advice or unsupported cancellation suggestion. Minor wording: unnecessarily offers to send the demo instead. |
| Deterministic Java rejection | PASS in both runs. An injected invalid timeout was rejected without starting a transfer. This check did not call GPT. |
| Initial smoke explanation attempt | loss-acks timed out at the 30-second HTTP deadline; missing-performance was not run because the runner stopped. The timeout remains recorded. |
| Follow-up synthetic explanations | All four requests completed with a 90-second HTTP deadline and passed automatic reference checks. All four need prose corrections under the strict review rubric; see the findings below. |

The four explanation findings are: loss-acks labels delivered-byte counts as integrity evidence; missing-performance infers a timeout caused a retransmission from aggregate counts; integrity-failure asserts post-write corruption without evidence of the cause/timing; unconfirmed-outcome asserts an unsupported reason and confuses distinct acknowledged progress with repeat ACK arrivals. The missing-performance answer correctly refuses unavailable throughput/duration; its causal sentence is the reason it is not an unconditional pass. Numerical citations, units and missing-value handling otherwise passed review.

Requested model: gpt-5-mini. Every completed response reports gpt-5-mini-2025-08-07. Prompt versions: commands-v2 / explanations-v3. Smoke began at 17:04:42 UTC (21:04:42 Dubai); explanations ran 17:14:15-17:17:12 UTC (21:14:15-21:17:12 Dubai). There were nine API attempts across the two runs and eight completed responses. Known usage: 26862 total tokens; the timed-out request's usage is unknown, not zero. API latency is separate from UDP transfer timing.

## Exact result locations

Paths are relative to the repository root:

- Smoke: target/milestone-8-eval/2026-09-21T17-04-41.966257700Z-14171895076588930966/
- Explanations: target/milestone-8-eval/2026-09-21T17-14-14.616125800Z-17071091827319850136/

Each directory contains report.jsonl (original prompts, proposals/drafts, evidence, Java decisions, API results and usage) and review.md (completed assistant review and open findings). The original reports retain their pre-review PENDING markers; consult review.md for the subsequent assessment. The smoke directory also contains data/input/demo.txt and received-demo.txt, both SHA-256 D896FEFCF45B29AD47F15CBB6F275B19431BDE5EB311E93E038C1E491891E0AF.

These target/ directories are local and ignored by Git. Pulling or merging the branch will not copy them. This handoff summary travels with the docs once committed; share the two selected run directories separately if the recipient needs the full evidence. Preserve the original timeout alongside the follow-up. No credentials should be included in shared artifacts.

## Recommended next work

Proceed with the delegated merge and real-log connection while keeping the explanation corrections as explicit open interface work. Those corrections can be made and evaluated using the existing synthetic cases before real integration; connecting real logs alone does not resolve them. Handing off integration does not automatically assign the prompt fixes to Person 2.

After a justified prompt/interface correction, run the relevant offline checks and deliberately repeat the four-case explanations batch. The current launcher selects fixed batches, not individual cases. Complete the broader commands batch coverage as well; it has not been run live. After integration, separately test real measured explanations, identity/outcome reconciliation and the impaired demo. Synthetic evaluation does not establish any of those. No further paid test or prompt change was made while updating this note.

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
| target/milestone-8-eval/ | Saved live evaluation artifacts. Two September 21 run folders are now present, listed above. Each contains report.jsonl and review.md; real-demo batches also retain their sample files. |
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
| 8. Live evaluation, partly reviewed | Added opt-in batches, semantic expectations, safe result/usage records, prompt improvements and console encoding fixes. September 21 smoke and follow-up explanations ran and were reviewed. Real start/status and command boundaries passed; explanation corrections/retests and wider command coverage remain open. Milestone 8 is not complete. |
| 9. Real measured integration | Remaining work: merge producer implementation, connect real logs/observations, verify identity and semantics, and run actual impaired success/failure scenarios. This handoff delegates merge/log connection work to Person 2; this note implements none of it. |
| 10. Final documentation/demo | README and a draft walkthrough exist. Final measured examples, reproducibility instructions and rehearsal depend on the integrated system. Not marked complete. |

Latest recorded full verification: 603 tests across 32 classes, zero failures/errors/skips, and successful JAR packaging. This is the earlier offline checkpoint, not a new run during this documentation update. It does not establish compatibility with Person 2's unmerged code or override the live explanation findings.

## First steps for the merge and real-log connection

- First checkpoint/share the current Person 3 changes. At this inspection, feature/LLM-integration tracks origin and the recorded committed milestone 7 baseline is f1be0c8, but milestone 8 changes and new documents/scripts/tests remain uncommitted. Pulling only the remote branch will miss that work. Review and commit the intended source/docs before the branch handoff; do not automatically include local data or target artifacts. The older docs/person-3-handoff.md is currently deleted in the working tree; this update preserves that state and uses this existing guide as the current handoff. Review that deletion deliberately before staging.
- Inspect Person 2's actual records/APIs, including units, missing values, finalization, endpoint evidence and experiment/application/wire ID association. The complete metric/supporting-field scope is already accepted.
- Start at explanation/SummaryProvider.java and ExplanationFlow.java for the real evidence connection; control/engine/RealTransferService.java and the shared status types are the observation connection points. The current REAL-evidence gate deliberately stops before provider/client calls. A loader alone will not enable real explanations: adapt and verify the flow for real evidence as part of integration.
- Preserve the Java parser/validator/dispatcher boundary, the distinction between measured and synthetic data, and sender success versus verified integrity. Do not fill missing measurements with fixture values or let GPT calculate missing producer metrics.
- After merging, run the offline checks, verify real success/failure evidence and detailed status, finish deliberate live GPT review, then rehearse the final impaired demonstration. Actual observer/identity changes should be coordinated with Person 1 where engine knowledge is needed.

person-2-integration-note.docx explains the technical dependencies in more detail and now reflects the same reported producer status and live-test findings. No merge, commit, push or additional live API call was performed to update these notes.
