# Person 3 implementation checklist

Updated September 20, 2026 on branch `person-3/llm-integration`. Engine baseline: `15a3fc36643a70b54c2fc767038ff4cc547cf31b` (Stage 10.5); milestones 2-4 were committed as `1571613`, milestone 5 as `d9cc443`, and the original independent milestone 6 boundary as `ab3c4c0`. Its separate explanation HTTP adapter is now implemented and verified offline; the latest full verification passed **498 tests across 29 classes** and built the JAR. The user has confirmed that Person 2 agrees to the complete [revised metric set](Metrics_Summary_Revised.md), including supporting fields. Treat that scope as accepted; actual metrics/logs and concrete event/storage/identity integration are still unavailable. Earlier real transfer/hash evidence remains under milestones 1 and 4. This follow-up is limited to milestone 6; milestone 7, live API calls, commits and pushes have not started.

Work through the milestones in order; add tests alongside each component rather than postponing them until milestone 7. The engine now exists, so connect a real adapter before GPT. Keep the simulated service for deterministic tests and cases the real engine cannot yet expose.

Use [Person 3 handoff and design note](./person-3-handoff.md) for the proposed contract, recommended choices, ownership, and assumptions to discuss with teammates. If the team changes that contract, update this checklist and its examples to match.

The assignment outcomes are natural-language control, live status, evidence-based explanations, deterministic Java validation, and a working integrated demonstration. The named classes, fake services, test structure, and implementation sequence below are our recommended way to deliver those outcomes, rather than additional assignment requirements. Person 1 owns the UDP engine; Person 2 owns networking experiments and their measured results.

## 1. Preserve the working baseline and agree the team contract

- [x] Establish working Java 17 and standalone Maven 3.9.16 for this workspace.
- [x] Pull and identify the stage 10.5 baseline on `person-3/llm-integration` at the commit above.
- [x] Establish the baseline build: the supplied `mvn clean verify` output reports 78 tests across 13 classes, zero failures/errors, and a built JAR.
- [x] Verify one manual sender/receiver transfer: both CLI processes reported `SUCCESS`; input/output are each 5,405 bytes and the supplied SHA-256 values match.
- [ ] Optionally add a pinned project Maven Wrapper during implementation setup; standalone Maven already works, so this is a reproducibility improvement rather than a prerequisite.
- [ ] Share the brief teammate note in section 9 of the [handoff](person-3-handoff.md), then record their feedback on the proposed hooks, IDs, lifecycle, settings, metrics, and ownership.
- [ ] Agree a backward-compatible engine overload accepting a caller-supplied UUID and an optional/no-op observer, preserving existing engine callers.
- [ ] Coordinate bounded control-handshake recovery, peer/transfer-ID validation, and receiver output protection with Person 1 before impaired integrated tests.
- [x] Record the user's confirmation that Person 2 agrees to the complete revised metric set, including supporting measurement, configuration and metadata fields.
- [ ] Finalize the implementable event/storage/identity contract around the accepted metric set (concrete serialization, event records, ID association, hooks and record finalization). Person 2 retains ownership of measurement/logging; field-scope agreement alone does not provide those implementations.
- [x] Record and analyze Person 2's proposed fields from `Metrics_Summary.docx`; preserve the distinction between her proposal, illustrative JSON and real measured outputs. See the [review and open decisions](person-2-metrics-review.md).
- [x] Prepare a [revised summary](Metrics_Summary_Revised.md) and [Word copy](Metrics_Summary_Revised.docx), separating assignment requirements from design choices and correcting the illustrative packet-count example. The user subsequently confirmed Person 2's agreement to the complete set; milestone 7 implementation remains paused.
- [ ] Confirm the approved OpenAI API route and arrange credentials for later live tests; keep offline development independent of credentials.

The matching baseline SHA-256 is `50AEF3550D0C6311A8A1F616CFAE1E5DB2ADFF5CE78B44F833F822139DD1A93F`. This proves the recorded happy-path transfer and test run, not fault recovery or later Person 3 integration. The existing receiver handles one transfer per process; restart it for each manual run and use fresh output paths until Person 1 confirms no-overwrite behavior.

**Done when:** the working baseline is recorded and the team has reviewed the integration proposal. Optional wrapper work does not block implementation; unavailable hooks remain explicit limitations.

## 2. Define shared types and a simulated engine

- [x] Define shared request, start acknowledgement, status snapshot, final result, and error types around the existing engine contract and the handoff.
- [x] Define the `TransferService` operations for starting a transfer, reading status, and retrieving a result.
- [x] Make start return Java-generated application `transfer_id`/`run_id` values promptly; keep `protocol_transfer_id` null until the agreed caller-UUID overload provides a verified mapping.
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
- [x] Configure a finite initial START-response wait on the existing UDP channel and report timeout honestly; leave handshake retries/idempotency to Person 1's engine work.
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
4. [Milestone 5 walkthrough](person-3-milestone-5.md), [README](../README.md), and [handoff](person-3-handoff.md): file map, example flow, setup and practical limits. The model/API format choices were checked against official OpenAI documentation.

The default API request can occupy the console for about 60.1 seconds across two attempts; the separate UDP worker keeps running. API deadlines are configurable and separate from UDP settings. `explain` still selects a frozen outcome/question only. Person 1's engine, the shared service/command contracts, deterministic validator/dispatcher, wire protocol, existing entry point and dependencies are unchanged. Person 2's metrics/logging and proposed engine hooks remain pending.

**Review stop:** milestone 5 only is complete in this step. Next, after review, milestone 6 would add a summary-provider boundary and evidence-grounded explanations using labelled fixtures until real summaries are supplied by Person 2. No milestone 6 implementation was started.

## 6. Ground explanations in recorded measurements

**Current status:** the original provider/fixture/explanation boundary is committed, and the separate Person 3 explanation HTTP adapter is now implemented and verified using synthetic evidence and a loopback HTTP endpoint. Actual measured integration still waits for Person 2's outputs and verified identity/hooks connection. Live model evaluation remains separate milestone 8 work. This finishes the currently scoped independent milestone 6 implementation; it does not complete real measured integration or start milestone 7.

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

The new client explicitly projects frozen evidence into a strict structured-output request with no tools; `ResponsesTransport` reuses the established retry/deadline/body-limit/credential safeguards. The launcher installs the client with an unavailable provider; the existing REAL gate still returns `EVIDENCE_UNAVAILABLE` before HTTP. Exact decimal/null preservation, input/output identity, unsupported citations, HTTP failures and evidence retention are covered. The complete agreed producer field-set audit remains milestone 7 work, and live prose quality remains unverified. See the [follow-up walkthrough](person-3-milestone-6-http.md).

**Review stop:** only the independent milestone 6 work is included. Person 1's engine and identity/observer extensions, Person 2's metrics/logging, live API calls, commits and pushes are outside this step. Leave the dependent items above pending. Milestones 7 onward are not started or marked complete here.

## 7. Complete the offline regression suite

**Planning update after metric-set agreement:** this milestone has not started. Its offline scope can proceed without actual logs: audit the existing 498-test coverage first, then add meaningful gaps using the accepted field set and explicitly synthetic fixtures. Do not recreate tests already covered by milestones 2-6 or assume the generic fixtures already implement the complete accepted set. The independent explanation HTTP adapter and its offline checks are now available from milestone 6. Concrete producer parsing and real-output acceptance checks remain dependent integration work under milestones 6 and 9; missing real logs need not prevent completion of a clearly scoped offline milestone 7. Keep the REAL-evidence gate in place.

- [ ] Cover valid commands, wrong JSON types, unknown/extra fields, unknown files/receivers, invalid bounds, missing essentials, and unsupported requests.
- [ ] Supply an invalid model-generated proposal through the stub GPT client and prove that Java rejects it with no engine side effect.
- [ ] Cover status before, during, and after a transfer, current/last ID resolution, conflicting starts, and integrity-dependent completion.
- [ ] Cover byte-to-packet window conversion, worker responsiveness, unavailable engine counters, and honest real-state mapping without fabricating observer data.
- [ ] Use a local HTTP test endpoint to verify API request construction and parsing of representative success, refusal, incomplete, malformed, timeout, and error responses.
- [ ] Verify that API failures/retries cannot duplicate transfer starts and that credentials are absent from captured logs.
- [ ] Cover explanation fixtures with missing metrics, engine failure, and insufficient causal evidence; avoid assertions that require exact prose.
- [ ] Add explicitly synthetic coverage for the accepted complete metric set: units/precision, configured versus observed fields, typed success/integrity/null handling, unavailable measurements and inconsistent counters. Use controlled identity mappings for boundary tests; actual producer identity association and serialized output acceptance remain later integration checks. Keep earlier generic fixtures as boundary tests.
- [ ] Audit the separate analysis HTTP adapter's request/response/failure checks implemented in milestone 6; reuse its 59 cases and add only meaningful gaps. Keep analysis separate from command tools and live API evaluation.
- [ ] Run the complete offline suite without an API key or live OpenAI access and document its command and expected scope.

**Done when:** offline checks pass reproducibly and show which behavior belongs to Java validation, API handling, and the simulated engine. These checks do not claim UDP correctness or real network performance.

## 8. Evaluate live GPT before the full impaired demo

Agreement on the metric set does not supply measured evidence. Live model evaluation can use explicitly synthetic summaries without Person 2's logs; the separate analysis HTTP adapter is now implemented and tested offline. Working API access and an explicitly enabled live evaluation path remain pending. This does not complete real measured explanation acceptance.

- [ ] Add an explicitly enabled live-test path that is excluded from the normal offline test run.
- [ ] Create a small prompt set covering clear starts, paraphrases, units, omissions, ambiguous references, status, unsupported operations, and explanation questions.
- [ ] Run the prompt set with the configurable recommended model and record model ID, test date, prompts, proposed commands, and Java decisions without secrets.
- [ ] Evaluate command/argument correctness and whether clarification was appropriate rather than comparing exact response wording.
- [ ] Check explanations against known fixture measurements for unsupported numbers or causal claims; record failures and adjust prompts/schemas as needed.
- [ ] Include agreed producer field names/units and questions that distinguish configured loss from observed drops, ACK arrivals from delivered bytes, and protocol success from verified integrity; label all fixture results synthetic.
- [ ] Demonstrate natural-language start with the real adapter, basic observable status, fixture-grounded explanation in clearly labelled test mode, and deterministic Java rejection.

**Done when:** live GPT behavior has been reviewed against explicit expectations, and every demonstration using fake data is labelled synthetic.

## 9. Complete observer, metrics, and fault-scenario integration

- [ ] Implement against the concrete producer handoff: verified `experiment_id`/application/wire association, schema/definition versions, timing/outcome/counter semantics and finalized serialized records. Use the accepted metric set; resolve remaining technical details rather than requesting field-scope approval again.
- [ ] Verify that actual producer summaries and linked logs supply the accepted assignment evidence (including delivered bytes, acknowledged/timeout/duplicate counts, ratios, overhead, event logs and applicable RTT statistics); record which artifact provides each item.
- [ ] Adopt Person 1's backward-compatible caller-UUID/observer hooks and verify ID mapping across the adapter, engine events, and Person 2's summaries.
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
