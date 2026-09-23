# Person 3 implementation checklist

Updated September 22, 2026 on inspected branch `feature/LLM-integration` at `ca5990c`. Older branch/commit/test descriptions below are historical checkpoints. The September 21 milestone 8 live batches were reviewed with findings. September 22's `explanations-v4` correction passed 151 focused offline tests across five classes, then the user ran its four-call live retest and the actual prose was reviewed. The original target errors were not repeated in this sample; diagnostic/conclusion/presentation findings remained. The subsequent authorized `explanations-v5` refinement passed a separate 151-test offline check and is **NOT live-tested**. Paid acceptance is deferred until Person 2's genuine-evidence integration is ready and its inputs checked. Model adherence and broader command coverage keep **milestone 8 incomplete**. The 603-test full verification and packaging remain historical. The source/docs changes are uncommitted. The [repository handoff](repository-and-milestone-handoff.md) records findings and artifacts; the [walkthrough](person-3-milestone-8.md) explains changes and results. Preserve the deleted older handoff and Word copies, local data and earlier evidence. Stop before milestone 9. No automatic staging, commit or push is authorized.

Person 2's complete [revised metric set](Metrics_Summary_Revised.md) is accepted. The user reports that her metrics/logging implementation is complete on her own branch and has delegated the merge and connection of real logs to this interface to her. Her branch and outputs have not been inspected or merged here. Pending integration items below mean work still to verify in the combined implementation, not a claim that her branch lacks it.

Work through the milestones in order; add tests alongside each component rather than postponing them until milestone 7. The engine now exists, so connect a real adapter before GPT. Keep the simulated service for deterministic tests and cases the real engine cannot yet expose.

Use the [repository and milestone handoff](repository-and-milestone-handoff.md) and [Person 2 integration note](person-2-integration-note.md) for the current structure, ownership, evidence and integration requirements. If the team changes the contract, update this checklist and its examples to match.

The assignment outcomes are natural-language control, live status, evidence-based explanations, deterministic Java validation, and a working integrated demonstration. The named classes, fake services, test structure, and implementation sequence below are our recommended way to deliver those outcomes, rather than additional assignment requirements. Person 1 owns the UDP engine; Person 2 owns networking experiments and their measured results.

## 1. Preserve the working baseline and agree the team contract

- [x] Establish working Java 17 and standalone Maven 3.9.16 for this workspace.
- [x] Pull and identify the historical Stage 10.5 baseline on `person-3/llm-integration` at `15a3fc36643a70b54c2fc767038ff4cc547cf31b`.
- [x] Establish the baseline build: the supplied `mvn clean verify` output reports 78 tests across 13 classes, zero failures/errors, and a built JAR.
- [x] Verify one manual sender/receiver transfer: both CLI processes reported `SUCCESS`; input/output are each 5,405 bytes and the supplied SHA-256 values match.
- [ ] Optionally add a pinned project Maven Wrapper during implementation setup; standalone Maven already works, so this is a reproducibility improvement rather than a prerequisite.
- [ ] Share the [integration note](person-2-integration-note.md) and [repository handoff](repository-and-milestone-handoff.md), then record feedback on observations, IDs, lifecycle, settings, metrics and ownership. Preparing these notes does not claim they were sent.
- [ ] Coordinate actual engine observations and a verified application/wire UUID association with Persons 1 and 2. Supplying or exposing the wire UUID are integration options; a particular overload/observer API is not a requirement. Person 2 may add instrumentation alongside Stage 11.
- [ ] Coordinate bounded control-handshake recovery, peer/transfer-ID validation, and receiver output protection with Person 1 before impaired integrated tests.
- [x] Record the user's confirmation that Person 2 agrees to the complete revised metric set, including supporting measurement, configuration and metadata fields.
- [ ] Finalize the implementable event/storage/identity contract around the accepted metric set (concrete serialization, event records, ID association, hooks and record finalization). Person 2 retains ownership of measurement/logging; field-scope agreement alone does not provide those implementations.
- [x] Record and analyze Person 2's proposed fields from `Metrics_Summary.docx`; preserve the distinction between her proposal, illustrative JSON and real measured outputs. See the [review and open decisions](person-2-metrics-review.md).
- [x] Prepare a [revised summary](Metrics_Summary_Revised.md) and [Word copy](Metrics_Summary_Revised.docx), separating assignment requirements from design choices and correcting the illustrative packet-count example. The user subsequently confirmed Person 2's agreement to the complete set; milestone 7 now covers its independent synthetic compatibility checks.
- [x] Record user-reported working OpenAI API access and credit from September 20. Credentials remain outside source/artifacts; ordinary offline tests require none. This smoke report does not establish formal milestone 8 results.

The matching baseline SHA-256 is `50AEF3550D0C6311A8A1F616CFAE1E5DB2ADFF5CE78B44F833F822139DD1A93F`. This proves the recorded happy-path transfer and test run, not fault recovery or later Person 3 integration. The existing receiver handles one transfer per process; restart it for each manual run and use fresh output paths until Person 1 confirms no-overwrite behavior.

**Done when:** the working baseline is recorded and the team has reviewed the integration proposal. Optional wrapper work does not block implementation; unavailable hooks remain explicit limitations.

## 2. Define shared types and a simulated engine

- [x] Define shared request, start acknowledgement, status snapshot, final result, and error types around the existing engine contract and the handoff.
- [x] Define the `TransferService` operations for starting a transfer, reading status, and retrieving a result.
- [x] Make start return Java-generated application `transfer_id`/`run_id` values promptly; keep `protocol_transfer_id` null until supplying or exposing engine identity provides a verified mapping.
- [x] Define timestamp/unit conventions and nullable unavailable metrics; map engine result counter sentinels of `-1` to unavailable, never to zero.
- [x] Implement `FakeTransferService` with explicitly advanced progress states and predefined success/failure results.
- [x] Label fake snapshots/fixtures as synthetic, including missing-metric cases; never use fake progress to fill gaps in real status.
- [x] Test the contract with a successful transfer, an engine failure, an integrity failure, an unknown ID, and a result requested before completion.

**Done when:** Java code can start a simulated transfer, inspect changing status, and retrieve its final outcome without UDP or GPT.

**Verified September 20, 2026:** the focused command `mvn -o '-Dtest=nettransfer.control.*Test' test` passed 15 tests in two classes. The subsequent `mvn -o verify` passed all 93 tests across 15 classes and packaged the JAR. The new tests use no sockets, file transfers, GPT calls or sleeps; the full suite still includes the existing UDP integration tests. This shell needed process-local `JAVA_HOME` set to the existing Java 17 path in `.vscode/settings.json`; no persistent environment or build configuration was changed. Maven offline mode (`-o`) used the existing dependency cache. `verify` also regenerated the pre-existing untracked `dependency-reduced-pom.xml`; `clean` was not run, preserving the earlier manual-transfer files.

Reading guide for our review:

1. [TransferRequest](../src/main/java/nettransfer/control/TransferRequest.java) and [TransferSettings](../src/main/java/nettransfer/control/TransferSettings.java) carry Java-selected IDs, source, receiver and effective settings. Their basic structural checks are separate from the command validator now implemented in milestone 3. Java generates the request and application transfer UUIDs; the fake returns that transfer UUID as both application transfer ID and run ID.
2. [TransferService](../src/main/java/nettransfer/control/TransferService.java) is the small shared boundary: `start(request)`, `status(transferId)`, `summary(runId)`. Start acknowledges acceptance while the run remains `RUNNING`. Unknown IDs and premature summary requests throw `TransferServiceException` with a typed error. A terminal transfer failure is retained in its snapshot/summary instead.
3. [TransferSnapshot](../src/main/java/nettransfer/control/TransferSnapshot.java), [TransferMetrics](../src/main/java/nettransfer/control/TransferMetrics.java), and [TransferSummary](../src/main/java/nettransfer/control/TransferSummary.java) are immutable records. Timestamps use UTC `Instant`; measurements use bytes, milliseconds and chunk counts. Snapshot time means when the evidence was captured, not when it was read. Protocol elapsed time is supplied evidence, never inferred from wall-clock timestamps. Missing fields remain `null` with a reason. The tested `chunkCountFromEngine` helper maps `-1` to `null` and is now used by milestone 4's real adapter.
4. [FakeTransferService](../src/main/java/nettransfer/control/simulation/FakeTransferService.java) starts at the first supplied progress fixture. Each explicit `advance(id)` moves to the next fixture, then the configured success, engine-failure or integrity-failure outcome. Status reads never advance it. `advance` is a fake-only helper, absent from the shared interface. All returned evidence is `SYNTHETIC`, and every protocol transfer ID remains null. Terminal summaries survive later runs. The fake atomically enforces one active run; milestone 3 now dispatches validated commands through that boundary.
5. Read [FakeTransferServiceTest](../src/test/java/nettransfer/control/FakeTransferServiceTest.java), especially `onlyExplicitAdvancementChangesEvidenceAndTimestamps`, to follow a complete simulated lifecycle. [TransferContractTest](../src/test/java/nettransfer/control/TransferContractTest.java) checks sentinel handling and rejects unverified completion. ACKing all bytes does not establish whole-file integrity.

The initial metric fields and version constants are explicitly `person-3-draft-1`. Rich progress/goodput, packet/retransmission counts, RTT, overhead, persisted summaries and their serialization remain pending Person 2's agreed contract and later milestones. Milestone 2 itself added no observer, engine overload, validator, dispatcher, real adapter, new CLI or GPT integration. The fake never reads the supplied source path or sends to the supplied endpoint.

**Milestone 2 checkpoint:** completed before beginning milestone 3. Teammate feedback on hooks and metric definitions remains pending.

## 3. Build deterministic command validation and dispatch

- [x] Define the structured `start_transfer`, `status`, and `explain` commands, including a way to return clarification or unsupported-request outcomes without dispatching.
- [x] Configure approved file IDs and receiver IDs; resolve them to paths and destinations in Java.
- [x] Parse JSON with explicit checks for allowed fields, exact types, required values, unexpected fields, duplicate keys, and integer overflow.
- [x] Validate permitted/readable files, known receivers, supported settings, numeric bounds, and normalized units.
- [x] Preserve initial engine defaults: 1,024-byte chunks, a one-packet window, 200 ms timeout, and five consecutive no-progress retry rounds; do not describe the last setting as five retries per chunk.
- [x] Convert requested byte windows to packet capacity with `floor(window_bytes / 1024)` and require at least one packet; display requested bytes and effective packet capacity.
- [x] Apply documented defaults in Java, reject unsupported rate/control requests explicitly, and request clarification when essential information is missing or ambiguous.
- [x] Enforce the single-active-transfer rule atomically at the start boundary, including concurrent start attempts.
- [x] Route accepted commands through one dispatcher and test that rejection causes zero engine calls while an accepted start causes exactly one.

**Done when:** an invalid structured proposal is rejected by Java with a useful reason and cannot change transfer state.

**Verified September 20, 2026:** `mvn -o '-Dtest=nettransfer.control.command.*Test' test` passed 123 new checks: 72 parser cases, 38 validator/configuration cases and 13 dispatcher tests. The complete `mvn -o verify` passed 216 tests across 18 classes, with zero failures/errors/skips, and packaged the JAR. The existing 78 engine tests and 15 milestone 2 tests still pass. No new manual transfer was run.

The first sandboxed run passed 122 checks but encountered Windows `AccessDeniedException` in the temporary file-permission test. The approved rerun outside the sandbox passed all 123, and full verification used the same access. All four directory-link cases and actual unreadability ran without skips; temporary links and permission changes were cleaned up/restored. Tests use temporary approved files and the fake service, with no GPT calls or new real transfers. Process-local Java 17 configuration and offline Maven dependencies were used as in milestone 2; `clean` was not run. Packaging regenerated the existing untracked `dependency-reduced-pom.xml`.

See the [milestone 3 walkthrough](person-3-milestone-3.md) for the exact files, a sample command, defaults, boundary decisions and reading order. In particular:

- Approved catalogue maps and paths are configured in Java; milestone 4 now supplies them through trusted CLI startup arguments, rather than a configuration-file loader. `receiver-a` has a localhost configuration helper. The structured contract accepts integer bytes/milliseconds, not unit strings; natural-language unit interpretation comes later.
- A batch with multiple calls is rejected before any call. Malformed/invalid commands cause zero calls to the service. Accepted starts cause one service start attempt. Concurrent distinct valid requests reach the service's atomic reservation; one is accepted and the other returns `TRANSFER_BUSY` before starting a transfer. This milestone verifies the service boundary with a counting fake, not a real engine adapter.
- Java request IDs prevent repeated/concurrent output from attempting a second start, including after terminal completion. New explicit requests use new IDs. This record is in memory, separate from Person 2's future logs.
- `explain` only selects a frozen service summary and keeps the question. Null IDs require trusted Java selection context, now supplied by milestone 4's CLI current/last tracking. Clarification and unsupported outcomes never execute a transfer.
- Milestone 3 itself did not add a real adapter or CLI; those follow in milestone 4 below. At that checkpoint, GPT integration, wire-ID/observer extensions, and Person 2's metric/logging implementations were still pending. Draft configuration bounds remain subject to team review.

**Milestone 3 checkpoint:** completed before beginning milestone 4. Person 2's work and the agreed hooks remain pending.

## 4. Connect the real engine and a responsive CLI before GPT

- [x] Implement a real `TransferService` adapter around the existing blocking `sendFile` call; run it in a background worker and return from start promptly.
- [x] Configure a finite per-attempt START-response timeout through `SenderEngine`; preserve its retry/idempotency behavior and document the total attempt horizon.
- [x] Preserve the current cumulative-ACK/Go-Back-N engine behavior; keep reliability logic in Person 1's engine rather than reimplementing it in the adapter.
- [x] Add a CLI entry point with help, a direct structured-command path through the validator, local status, and the agreed exit behavior.
- [x] Track the current and last transfer IDs in Java; define how phrases such as "this transfer" and "the last transfer" resolve.
- [x] Initially show only observable real states (`RUNNING`, `COMPLETED`, `FAILED`); report progress/throughput as unavailable while the engine lacks observers.
- [x] Map terminal results and failures honestly: claim completion only from the engine's verified-success result, and preserve unavailable counters.
- [x] Handle no transfer, ambiguous references, unknown IDs, failed starts, in-progress results, and verified completion explicitly.
- [x] Run one adapter-driven real transfer with a freshly started receiver and fresh output path; inspect status while the worker runs and check the final hash/outcome.

**Done when:** a structured command starts the real engine without blocking the CLI, status reports only observable facts, and the fake service still supports deterministic test scenarios. Percentage progress and engine-phase states wait for real observer hooks.

**Verified September 20, 2026:** `mvn -o '-Dtest=nettransfer.control.engine.*Test,nettransfer.cli.*Test' test` passed all 43 new cases: 21 adapter unit cases, four local UDP integration checks, 15 console cases and three launcher-configuration cases. The full `mvn -o verify` passed **259 tests across 22 classes**, with no failures, errors or skips, and packaged the JAR. Full verification used the previously approved access outside the sandbox for milestone 3's temporary file-permission test. No dependency/build/engine changes were needed, and `clean` was not run.

The local UDP tests exercise a real receiver with gated START_ACK so `RUNNING` can be inspected, a silent START peer, socket release during shutdown, and missing FINISH_ACK. In the last case the receiver verifies its file but the sender correctly reports `FAILED`/`UNCONFIRMED`. These bounded adapter checks do not establish complete impaired-network recovery or supply Person 2's experimental measurements.

**Separate packaged-CLI transfer:** outside JUnit, a scripted console session started a fresh existing receiver and used `nettransfer.cli.TransferCliMain` with an explicit approved file and fresh output. The actual CLI displayed `[REAL] RUNNING`, then `[REAL] COMPLETED`, and the selected outcome reported `integrity=VERIFIED`; the receiver reported `SUCCESS`. Input/output are each **8,388,608 bytes** with matching SHA-256:

```text
7D212B9C884F5C77896DE960AE17CC341CDA43B14D6A971F34CA29EBD4BADF7F
```

Temporary evidence is under `target/milestone4-cli-20260920-115545-067025/`: [verification record](../target/milestone4-cli-20260920-115545-067025/verification.json), [sender console](../target/milestone4-cli-20260920-115545-067025/sender-console.txt), and [receiver console](../target/milestone4-cli-20260920-115545-067025/receiver-console.txt), plus the input/output files. These are disposable build-directory artifacts, not Person 2's logger or authoritative experiment summaries; a future `clean` removes them.

Reading guide:

1. [RealTransferService](../src/main/java/nettransfer/control/engine/RealTransferService.java): `start` reserves/queues, `runTransfer` calls the existing sender outside the state lock, `finish` publishes a truthful immutable outcome, and `close` closes the socket and prevents late success from overwriting interruption. Initial response wait defaults to 2,000 ms; the engine's DATA timeout/defaults remain unchanged.
2. [TransferCli](../src/main/java/nettransfer/cli/TransferCli.java): local commands, common dispatcher use, current/last selection, coarse status display and active-exit refusal. A regression test covers completion occurring between selection refresh and a subsequent accepted start, preserving the earlier terminal run as `last`.
3. [TransferCliMain](../src/main/java/nettransfer/cli/TransferCliMain.java): trusted startup file-ID catalogue, fixed localhost receiver, reader/writer wiring, resource ownership and shutdown hook. EOF interrupts active work through service cleanup; normal `exit` waits for a terminal run by refusing to leave while active.
4. [Milestone 4 walkthrough](person-3-milestone-4.md) and updated [README](../README.md): exact launch commands, status meanings, timeout/cleanup choices and pending integrations.

`REAL` snapshots keep unknown wire ID, ACKed bytes, protocol duration and engine `-1` counters unavailable. File size is source metadata only. Generic engine failures preserve their reason with integrity `UNCONFIRMED`; no phase or typed integrity cause is guessed from prose. Final summaries and interruption records are in memory. Person 1's engine, wire protocol, existing `Main`, and JAR entry point remain unchanged; Person 2's metrics/logging and the agreed UUID/observer extensions are still pending.

**Milestone 4 checkpoint:** completed and reviewed before milestone 5 below. Real metric explanations continue to depend on Person 2's measurements and the agreed hooks.

## 5. Add the GPT API wrapper and command interpretation

- [x] Add an injectable GPT client interface with a stub implementation for offline use.
- [x] Implement the OpenAI Responses API adapter using Java 17 `HttpClient` and Gson, with configurable model and request timeouts.
- [x] Describe the approved commands with strict function schemas; allow at most one proposed command per user request and disable parallel tool calls.
- [x] Build prompts from the available command vocabulary and necessary approved metadata; keep file contents and credentials out of prompts.
- [x] Parse expected responses and handle clarification, refusal, incomplete output, unexpected output, and malformed arguments without executing a transfer.
- [x] Handle missing credentials, authentication failures, rate limits, and transport errors; ensure retries never dispatch the same start twice.
- [x] Connect natural-language input to GPT interpretation and then to the existing Java validator; load credentials outside source control and redact them from logs.

**Done when:** the stub GPT client drives the CLI through the same service boundary as the real adapter, and every proposed command still crosses deterministic validation.

**Verified September 20, 2026:** the initial focused command `mvn -o '-Dtest=nettransfer.llm.*Test,nettransfer.cli.*Test' test` passed 128 tests. Two additional malformed-argument/credential regression cases were then included in the final `mvn -o verify`, which passed all **371 tests across 25 classes**, with zero failures/errors/skips, and packaged the JAR. The 112 added checks comprise:

| Test class | Added checks | Verified behavior |
| --- | ---: | --- |
| `ResponsesGptClientTest` | 55 | Real Java HTTP requests against a loopback server: strict schemas, bounded metadata, expected/malformed/refused/incomplete/mixed output, retries, authentication, deadlines including a stalled body, transport errors, body limits, redirects and credential-safe errors |
| `GptSettingsTest` | 22 | Configurable model/API deadlines, finite bounds, safe invalid configuration and bounded attempts |
| `TransferCliGptTest` | 33 | Stub-to-CLI-to-validator flow, rejected proposals with no start, clarification context and request UUID lifetime, Java current/last selections, active-service preservation on API failure, and direct command fallback |
| `TransferCliMainTest` | 2 added (5 total) | Missing credentials and invalid optional API configuration do not prevent starting the direct console |

The local HTTP retry integration returns HTTP 429 and then a valid proposal. It verifies two HTTP attempts produce exactly one simulated `TransferService.start`, using the same Java request UUID in both attempts and the accepted request. Captured request bodies contain IDs and user text, with no configured paths, file contents or API key. Captured CLI/errors exclude the test key, including cases with escaped credentials in tool arguments. Unknown/malformed/multiple proposals cannot authorize a start. The tests use scripted model output; they do not establish live GPT interpretation quality.

A separate packaged-JAR smoke check, with `OPENAI_API_KEY` absent, entered a natural-language request, `status`, and `exit`. It reported `MISSING_CREDENTIALS`, then Java's no-selection clarification, then `Goodbye`, with no accepted start. Its local transcript is `target/milestone5-cli-smoke.txt` (ignored build output).

Java 17 and cached Maven dependencies were used. The focused run passed inside the sandbox; full verification used approved access outside it for the existing Windows file-permission test. No live OpenAI request or new manual UDP transfer was made. `clean` was not run, preserving prior transfer evidence. Packaging regenerated the existing untracked `dependency-reduced-pom.xml`; it is not part of this milestone's source changes.

Implemented files and choices:

1. `src/main/java/nettransfer/llm/`: `GptClient`, immutable `InterpretationRequest`, scripted `StubGptClient`, `GptSettings`, typed `GptException`, and `ResponsesGptClient` with `ResponsesJson`/`ResponsesBodySubscriber` helpers. No service/engine access exists inside the GPT client.
2. `TransferCli`: natural-language input and `ask <sentence>` use the injected interpreter, then the existing dispatcher. Up to two clarification exchanges share a Java request ID; a direct command, executed/rejected request or API failure clears the context. GPT text is explicitly labelled as non-execution.
3. `TransferCliMain`: environment configuration for key/model/API deadlines, with missing/invalid GPT configuration leaving direct commands available. HTTP retries happen before dispatch and preserve the logical request ID.
4. [Milestone 5 walkthrough](person-3-milestone-5.md), [README](../README.md), and [current repository handoff](repository-and-milestone-handoff.md): file map, example flow, setup and practical limits. The model/API format choices were checked against official OpenAI documentation.

The default API request can occupy the console for about 60.1 seconds across two attempts; the separate UDP worker keeps running. API deadlines are configurable and separate from UDP settings. `explain` still selects a frozen outcome/question only. Person 1's engine, the shared service/command contracts, deterministic validator/dispatcher, wire protocol, existing entry point and dependencies are unchanged. Person 2's metrics/logging and proposed engine hooks remain pending.

**Review stop:** milestone 5 only is complete in this step. Next, after review, milestone 6 would add a summary-provider boundary and evidence-grounded explanations using labelled fixtures until real summaries are supplied by Person 2. No milestone 6 implementation was started.

## 6. Ground explanations in recorded measurements

**Current status:** the original provider/fixture/explanation boundary is committed, and the separate Person 3 explanation HTTP adapter is implemented and verified using synthetic evidence and a loopback HTTP endpoint. Actual measured integration still waits for Person 2's outputs and verified identity/hooks connection. Live model evaluation remains separate milestone 8 work. The historical milestone 6 verification below preceded the now-completed independent milestone 7 regression audit; neither completes real measured integration.

- [x] Define and test a summary-provider boundary using explicitly labelled synthetic fixtures; return `EVIDENCE_UNAVAILABLE` for real runs while real saved summaries/contracts are pending.
- [x] Verify the selected service run/transfer mapping, then the fixture's run/transfer/provenance/nullable protocol identity before analysis; reject mismatches and never substitute fixtures for a real run.
- [ ] Consume Person 2's agreed metric values and definitions; treat missing fields and engine `-1` counters as unavailable rather than zero.
- [x] Distinguish configured impairment from observed measurements in the synthetic field kinds and display; preserve limitations about retransmissions, loss, timeouts and single-run comparisons. The complete metric set is now accepted; adoption of the producer's actual observations remains pending.
- [x] Build immutable bounded explanation input from the supplied synthetic summary; no event excerpt is needed for these fixtures. Actual event parsing waits for Person 2's agreed format.
- [x] Define a separate analysis request/prompt contract requiring numerical citations, observations versus hypotheses, and insufficient-evidence limitations; exercise it with an offline scripted client. The follow-up HTTP client preserves this separate analysis boundary without execution tools.
- [x] Validate returned run/transfer IDs and cited field/value/unit references, display original supplied measurements and missing reasons beside the draft, and retain valid evidence when analysis fails.
- [ ] Integrate Person 2's real saved summaries and agreed identity/measurement definitions; verify a real explanation beside those outputs.
- [x] Person 3 independent work: implement and verify a separate tool-free explanation HTTP adapter using synthetic evidence and a loopback HTTP endpoint. No live OpenAI calls were made; request/decoder/transport and flow checks passed.
- [ ] Later milestone 8 work: evaluate live explanation prose and prompt effectiveness. Offline reference checks do not establish prose truth.

**Done when:** an explanation can be traced to its run and displayed evidence, including cases where the available measurements cannot establish a cause.

**Independent implementation:** see the original [milestone 6 walkthrough](person-3-milestone-6.md) and current [HTTP follow-up](person-3-milestone-6-http.md) for exact files, flow, definitions, bounds, limitations and local commit commands. The provider is a read-only boundary, not a storage implementation. `person-3-explanation-fixture-1` is a Person 3 test format, not the agreed producer schema. The default CLI never enables fixtures; the real-run gate rejects even matching-ID synthetic records before provider/model invocation.

**Original checkpoint, September 20, 2026:** the focused `mvn -o '-Dtest=ExplanationFlowTest,ExplanationBoundaryTest,TransferCliExplanationTest' test` passed **61 new tests** (27 flow, 21 boundary, 13 CLI). The separately focused `CommandDispatcherTest` passed all **18 tests**, including five new identity checks. After integration, `mvn -o verify` passed **437 tests across 28 classes**, with zero failures, errors or skips, and built `target/udp-file-transfer.jar` (371 previous + 66 new).

The first full run found a regression in CLI explanatory wording and the known sandbox restriction on the existing temporary Windows file-ACL test. Restoring the useful distinction that an in-memory outcome is not a persisted experiment log resolved the assertion. The approved rerun outside the sandbox passed the complete suite, including the ACL check. Java 17 was configured only for the process from `.vscode/settings.json`; offline Maven used cached dependencies. No live OpenAI request or new manual transfer was made. Existing UDP/loopback-HTTP regression tests ran. `clean` was not run, the milestone 4 verification file still exists, and packaging regenerated the pre-existing untracked `dependency-reduced-pom.xml`. The final diff leaves the engine, adapter, metrics contract, dependencies and `.gitignore` unchanged.

**HTTP follow-up verified September 20, 2026:** `mvn -o '-Dtest=ResponsesGptClientTest,GptSettingsTest' test` passed **77 existing tests** after shared transport extraction. `mvn -o '-Dtest=ResponsesExplanationClientTest' test` passed **59 new tests**. Two new launcher configuration checks bring this follow-up to **61 added tests**. Final `mvn -o verify` passed **498 tests across 29 classes**, zero failures/errors/skips, and built the JAR. The first adapter run caught missing completion-status and noncanonical UUID acceptance; both were tightened before verification. The full sandbox run had only the known existing Windows ACL permission-test error; the approved rerun outside the sandbox passed all tests. The API key was cleared in the test process, Maven ran offline, and no live OpenAI call or new manual transfer was made. `clean` was avoided to preserve prior transfer evidence.

The new client explicitly projects frozen evidence into a strict structured-output request with no tools; `ResponsesTransport` reuses the established retry/deadline/body-limit/credential safeguards. The launcher installs the client with an unavailable provider; the existing REAL gate still returns `EVIDENCE_UNAVAILABLE` before HTTP. Exact decimal/null preservation, input/output identity, unsupported citations, HTTP failures and evidence retention are covered. At that checkpoint the accepted field-set audit remained milestone 7 work; the subsequent synthetic audit is recorded below. Live prose quality remains unverified. See the [follow-up walkthrough](person-3-milestone-6-http.md).

**Historical milestone 6 review stop:** only its independent work was completed at that checkpoint. Shared engine observation/identity integration and Person 2's metrics/logging remain pending. The subsequently authorized milestone 7 and milestone 8 work are recorded below.

## 7. Complete the offline regression suite

**Complete for independent offline scope.** The 498 existing tests were audited before editing. The first seven requirements already had substantive coverage; those tests were reused. The gaps were the full accepted metric vocabulary and a few analysis HTTP envelope cases. See the [audit table, field map and exact changes](person-3-milestone-7.md). All new code is test-only. Concrete producer parsing and real-output acceptance remain integration work under milestones 6 and 9; the REAL-evidence gate remains unchanged.

- [x] Cover valid commands, wrong JSON types, unknown/extra fields, unknown files/receivers, invalid bounds, missing essentials, and unsupported requests. Reused parser/validator/dispatcher tests.
- [x] Supply an invalid model-generated proposal through the stub GPT client and prove that Java rejects it with no engine side effect. Reused `TransferCliGptTest`'s twelve invalid proposals with zero starts.
- [x] Cover status before, during, and after a transfer, current/last ID resolution, conflicting starts, and integrity-dependent completion. Reused CLI/fake/contract/dispatcher checks, including concurrent starts and a selection race.
- [x] Cover byte-to-packet window conversion, worker responsiveness, unavailable engine counters, and honest real-state mapping without fabricating observer data. Reused real-adapter and validator checks.
- [x] Use a local HTTP test endpoint to verify API request construction and parsing of representative success, refusal, incomplete, malformed, timeout, and error responses. Reused both HTTP clients' offline cases.
- [x] Verify that API failures/retries cannot duplicate transfer starts and that credentials are absent from captured logs. Reused the two-attempt/one-start CLI HTTP test and credential-redaction cases.
- [x] Cover explanation fixtures with missing metrics, engine failure, and insufficient causal evidence; avoid assertions that require exact prose. Reused flow/boundary/CLI cases and retained original generic fixtures.
- [x] Add explicitly synthetic coverage for the accepted complete metric set: units/precision, configured versus observed fields, typed success/integrity/null handling, unavailable measurements and inconsistent counters. `AcceptedMetricRegressionTest` adds 50 checks; all 26 numerical fields also traverse HTTP projection and CLI rendering. Typed producer metadata remains test-only; actual metadata adaptation/serialization is pending. Inconsistent values are preserved and invented correction citations rejected, not semantically validated as producer records. Controlled identity tests do not establish real producer associations.
- [x] Audit the separate analysis HTTP adapter's request/response/failure checks implemented in milestone 6; reuse its 59 cases and add only meaningful gaps. The class now runs 71 cases: six additional full-fixture projection invocations, five invalid envelope cases and explicit-null diagnostic acceptance. No tools or live API evaluation added.
- [x] Run the complete offline suite without an API key or live OpenAI access and document its command and expected scope.

**Verified September 20, 2026:** the initial focused run passed 135 cases. Final `mvn -o verify` passed **566 tests across 30 classes**, zero failures/errors/skips, and packaged the JAR: 498 existing + 50 metric + six CLI + 12 HTTP cases. The final focused selection contains 140 cases, included in the full run. Commands:

```powershell
$env:JAVA_HOME = (Get-Content .vscode/settings.json -Raw | ConvertFrom-Json).'java.configuration.runtimes'[0].path
$env:OPENAI_API_KEY = $null
mvn -o '-Dtest=AcceptedMetricRegressionTest,ResponsesExplanationClientTest,TransferCliExplanationTest' test
mvn -o verify
```

The first full sandbox run had only the known Windows temporary-file ACL error; the approved rerun outside the sandbox passed all checks. Tests used scripted fixtures and loopback HTTP/UDP, with no live calls or new experiments. `clean` was avoided to preserve earlier hash evidence. The generated POM remains excluded. Existing uncommitted production code, engine/adapter and `.gitignore` were preserved.

**Remaining dependencies:** producer logs and finalized summaries; concrete serialization/types/null encoding, events and finalization rules; verified experiment/application/wire association and endpoint attribution; actual metadata/outcome reconciliation and semantic consistency checks; shared engine/metrics observation and identity integration coordinated with Person 1, potentially instrumented by Person 2 during Stage 11. These are not implemented by test metadata or synthetic fixtures. Person 2's complete accepted field scope is not reopened.

**Historical milestone 7 review stop:** its independent offline scope is complete. Milestone 6 HTTP and milestone 7 were subsequently committed; the old milestone 7 staging instructions describe that earlier working tree. Current milestone 8 work is below. Real explanations still return `EVIDENCE_UNAVAILABLE` before provider/client calls. Offline checks establish interface regression behavior, not live model quality, whole-engine correctness or measured network performance.

## 8. Evaluate live GPT before the full impaired demo

**Infrastructure committed at `ca5990c`; September 21 batches and September 22's v4 retest are reviewed. The original targeted faults improved in that sample, with remaining findings. Current v5 refinements are implemented/offline-checked, NOT live-tested. Milestone 8 remains incomplete.** The separate Java main and [PowerShell launcher](../scripts/milestone-8-eval.ps1) default to preview. Live execution requires `-Live` and an explicit `-MaxCalls`; an API key alone cannot enable it. The model remains `gpt-5-mini`. Read the [audit, exact corrections and review guide](person-3-milestone-8.md) and [handoff results/artifact locations](repository-and-milestone-handoff.md). Paid acceptance is deferred until the delegated genuine-evidence integration is ready and checked; this work does not complete real measured explanation acceptance.

- [x] Audit and reuse the existing validator, services, HTTP clients, evidence gate, synthetic accepted-metric fixtures and offline coverage.
- [x] Add an explicitly enabled live path excluded from normal offline tests, with bounded calls/output and one HTTP attempt per request.
- [x] Define prompts and semantic expectations covering starts, paraphrases, units, optional/essential omissions, current/last ambiguity, status, unsupported operations and explanation questions.
- [x] Implement capture of configured/returned model, UTC date, prompts, proposals, Java decisions, expectations, actual results, failures and available token usage. API latency is separate from transfer timing; credentials are excluded.
- [x] Include accepted metric names/units and explicitly SYNTHETIC cases distinguishing configured loss from observed drops, ACK arrivals from delivered bytes, missing metrics, and protocol success from verified integrity.
- [x] Provide the bounded smoke batch and independent deterministic Java rejection demonstration; keep real demo outputs and synthetic explanations separately labelled.
- [x] Tighten optional-default, unsupported-operation and explanation instructions; fix native-console versus redirected UTF-8 handling separately from model-quality evaluation.
- [x] Run focused offline checks: runner 13 passing tests, telemetry/client 149 passing tests, prompt/console/explanation 180 passing tests. These selections overlap; they are not an additive suite total.
- [x] Complete approved `mvn -o verify`: **603 tests across 32 classes**, zero failures/errors/skips, and successful JAR packaging. The initial sandbox run's only error was the known existing Windows file-ACL restriction; the approved rerun passed. No `clean` or paid calls were used.
- [x] Run and review the formal small live evaluation with `gpt-5-mini`; preserve actual failures and token/latency records without secrets. The smoke run attempted five calls: four completed and one timed out; its last case did not run. The follow-up explanations batch completed all four calls. Eight completed responses report `gpt-5-mini-2025-08-07`.
- [x] Review the four smoke command cases for commands/arguments, clarification and Java decisions. Real start/status, missing essentials and unsupported deletion passed their recorded boundaries, with minor wording issues retained. Wider command coverage is still pending.
- [x] Review all four September 21 follow-up SYNTHETIC explanation answers and record unsupported causal claims and field-meaning errors. All passed automatic reference checks, but none received an unconditional manual PASS. Missing values and numeric references were preserved. The later v4 correction/retest is recorded separately below; these original findings remain unchanged.
- [x] Review recorded real natural-language start/basic status, synthetic explanation responses and deterministic Java rejection. The real 36-byte transfer completed with verified matching SHA-256; rejection started no transfer. Reviewing explanation responses does not approve their prose. The September 20 user-reported smoke remains separate history.
- [x] Implement `explanations-v4` in `ExplanationRequest.java`: separate delivery/integrity and ACK arrivals/distinct progress, forbid causal conclusions from aggregate counts or unexplained outcomes, require explicitly uncertain hypotheses (or none), preserve state/integrity as nonnumeric facts, and reinforce synthetic/endpoint attribution. Keep numeric citations, missing reasons, tool-free explanations and Java validation.
- [x] Run fresh focused offline regression checks on September 22: **151 tests across five classes**, zero failures/errors/skips. Save uniquely suffixed reports and verify all 71 earlier evaluation/report files unchanged. This is not a new 603-test full run or JAR packaging; see [verification artifacts](../target/milestone-8-offline/explanations-v4-20260922-101422-8f350a37/).
- [x] Verify the existing `explanations` launcher in preview mode with `gpt-5-mini`: all four expected cases listed, no API calls or UDP transfers. The user also previewed before their live retest.
- [x] Review the user-run September 22 `explanations-v4` retest against actual `analysis_input`, excluding hidden fixture metadata: all four calls completed with `gpt-5-mini` / returned `gpt-5-mini-2025-08-07`, one attempt each, a 90-second terminal-only deadline, and valid numerical references. The original four target errors were not repeated in this sample. Preserve the [new report and completed review](../target/milestone-8-eval/2026-09-22T06-58-35.590646700Z-9150417386280831162/review.md) separately from the September 21 failures.
- [x] Implement the authorized `explanations-v5` refinement: request diagnostic evidence capable of testing the claim; answer what evidence establishes even when causes are unknown; retain reported FAILED verification without inventing a mismatch; explicitly label provenance in limitations; preserve counter meanings and per-field endpoint attribution throughout the prose. These are general instructions, not fixture-specific answers or a producer-contract change.
- [x] Run a separate fresh v5 offline check: **151 tests across five classes**, zero failures/errors/skips; confirm the compiled prompt version and hash-check all 98 earlier live/offline evaluation and Surefire files unchanged. Save [new logs and reports](../target/milestone-8-offline/explanations-v5-20260922-114920-fb08af01/). No full-suite rerun, new packaging or paid call was performed.
- [ ] Verify v5 model adherence with actual returned prose. Pause further paid acceptance until Person 2's merge/real-log connection is available and actual field definitions, identities, endpoints and outcomes are checked. Then deliberately choose a small `gpt-5-mini` test with bounded calls/deadlines and save/review new evidence. Current v5 is NOT live-tested; the existing four-call synthetic batch alone cannot validate real integration.
- [ ] Complete remaining prompt categories in deliberate follow-up batches before marking milestone 8 complete. Do not interpret automatic semantic pass results as prose review.

**Pending work through milestone 8:** the v4 correction was live retested and reviewed, with remaining findings above. The v5 instructions address those findings, but their actual model adherence remains unverified; broader command coverage is also pending independently of real-log integration. Real detailed status and measured explanations still require verification of shared observations, finalized summaries/events, concrete serialization/lifecycle/endpoint semantics and experiment/application/wire identity in the combined implementation. Person 2 is taking over the merge, real-provider connection and semantic/outcome reconciliation using her reported completed work; those changes have not been inspected or merged here. Person 1's Stage 1-10.5 engine is complete; instrumentation/identity is shared integration work. No teammate is contacted automatically.

**Artifact preservation:** no `clean` is run. The Maven Shade option `createDependencyReducedPom=false` prevents packaging from rewriting the pre-existing untracked generated POM. Preview prints the case questions and expectations and makes no API call. Rejected explanation drafts remain explicitly untrusted review records beside Java's rejection and the original evidence.

**Done when:** actual live GPT outputs have been reviewed against explicit expectations, failures and limitations are recorded, and every synthetic demonstration is labelled. Offline success alone is insufficient. Stop for review before milestone 9.

## 9. Complete observer, metrics, and fault-scenario integration

- [ ] Implement against the concrete producer handoff: verified `experiment_id`/application/wire association, schema/definition versions, timing/outcome/counter semantics and finalized serialized records. Use the accepted metric set; resolve remaining technical details rather than requesting field-scope approval again.
- [ ] Verify that actual producer summaries and linked logs supply the accepted assignment evidence (including delivered bytes, acknowledged/timeout/duplicate counts, ratios, overhead, event logs and applicable RTT statistics); record which artifact provides each item.
- [ ] Adopt the shared engine observation/identity integration and verify associations across the adapter, wire transfer, engine events and Person 2's summaries; supplying or exposing identity are both options.
- [ ] Connect real observer snapshots and Person 2's saved summaries; expose detailed states/progress only when supported by actual observations.
- [ ] Preserve supplied seconds/Mbps values and configured/observed meanings; reconcile success and nullable integrity with the selected engine outcome. Retain missing evidence and reject incorrect identity associations. Do not substitute DATA/ACK counts for unique progress.
- [ ] With Person 1, verify bounded START/FINISH recovery, peer/transfer-ID checks, receiver no-overwrite behavior, and receiver restart expectations for the selected scenarios.
- [ ] Run real success/failure transfers, query observed progress while active, and verify that completion follows integrity confirmation; a rejected command must start no network activity.
- [ ] With Person 2, run a loss/delay scenario and explain its actual collected metrics beside the underlying evidence.
- [ ] Keep GPT latency separate from engine transfer timing and rerun the relevant offline regression checks after integration changes.

**Done when:** the same CLI works with real transfers and measured summaries, with clear failure reporting and verified completion.

## 10. Prepare documentation and the final demonstration

Documentation and a draft demo script can advance independently when authorized. The final measured examples and rehearsal wait for milestone 9's real integration; synthetic results do not replace them.

- [ ] Write README instructions for Java/Maven setup, configuration, credentials, building, offline tests, opt-in live tests, fake mode, and real mode.
- [ ] Document supported commands, byte-to-packet window conversion, retry-round semantics, ID resolution, receiver restart/output policy, and unavailable metrics.
- [ ] Give Person 1 the validation/dispatch boundary description and interaction flow for the protocol specification and sequence diagram.
- [ ] Give Person 2 the agreed metric/display requirements and ensure their evaluation uses actual experiment outputs rather than fixtures.
- [ ] Document the actual agreed summary/event format and verify full assignment evidence coverage; the proposed summary alone and its illustrative JSON are not experiment results.
- [ ] Write a repeatable demo script covering natural-language transfer, live status, loss/delay metrics, an explanation beside evidence, and deterministic rejection by Java.
- [ ] Rehearse the script from a fresh terminal using the documented commands and record the required configuration and sample file names.
- [ ] Review the final diff for credentials and misleading synthetic results; update the handoff note to the final implemented contract and mark only verified tasks complete.

**Done when:** another teammate can follow the README and run the demo, and the documents describe the actual tested implementation.
