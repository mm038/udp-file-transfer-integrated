# Person 3 implementation checklist

Updated September 20, 2026 for the stage 10.5 baseline on branch `person-3/llm-integration`, commit `15a3fc36643a70b54c2fc767038ff4cc547cf31b`. Milestones 2-4 are implemented and verified: the latest `mvn -o verify` passed 259 tests (78 baseline + 15 milestone 2 + 123 milestone 3 + 43 milestone 4), with zero failures/errors/skips, and built the JAR. The existing engine is unchanged. Teammate agreement, Person 2's logging/metrics, and milestones 5 onward remain pending. The user's earlier baseline transfer remains recorded below; a separate packaged-CLI transfer with matching hashes is recorded under milestone 4.

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
- [ ] Agree Person 2's event/metric/summary contract and keep unresolved assumptions visible; Person 2 retains ownership of those implementations.
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
- Milestone 3 itself did not add a real adapter or CLI; those follow in milestone 4 below. The engine remains unchanged. GPT integration, wire-ID/observer extensions, and Person 2's metric/logging implementations are still pending. Draft configuration bounds remain subject to team review.

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

**Review stop:** milestone 4 only is complete in this step. Next, after review, milestone 5 adds the injectable GPT client and command interpretation through the existing validator/dispatcher. Real metric explanations continue to depend on Person 2's measurements and the agreed hooks.

## 5. Add the GPT API wrapper and command interpretation

- [ ] Add an injectable GPT client interface with a stub implementation for offline use.
- [ ] Implement the OpenAI Responses API adapter using Java 17 `HttpClient` and Gson, with configurable model and request timeouts.
- [ ] Describe the approved commands with strict function schemas; allow at most one proposed command per user request and disable parallel tool calls.
- [ ] Build prompts from the available command vocabulary and necessary approved metadata; keep file contents and credentials out of prompts.
- [ ] Parse expected responses and handle clarification, refusal, incomplete output, unexpected output, and malformed arguments without executing a transfer.
- [ ] Handle missing credentials, authentication failures, rate limits, and transport errors; ensure retries never dispatch the same start twice.
- [ ] Connect natural-language input to GPT interpretation and then to the existing Java validator; load credentials outside source control and redact them from logs.

**Done when:** the stub GPT client drives the CLI through the same service boundary as the real adapter, and every proposed command still crosses deterministic validation.

## 6. Ground explanations in recorded measurements

- [ ] Define and test a summary-provider boundary using labelled fixtures until Person 2 supplies real saved summaries; return evidence-unavailable for real runs without summaries.
- [ ] Load a summary for a Java-resolved transfer/run ID and verify its identity before explanation; never substitute fixture evidence for a real run.
- [ ] Consume Person 2's agreed metric values and definitions; treat missing fields and engine `-1` counters as unavailable rather than zero.
- [ ] Keep configured impairment settings distinct from observed drops, and retransmissions distinct from proven packet loss.
- [ ] Build a bounded explanation input from the summary and any necessary event excerpts; label synthetic test inputs explicitly.
- [ ] Ask for explanations that cite supplied fields and values, separate observations from possible causes, and acknowledge insufficient evidence.
- [ ] Validate structured evidence references where practical and display the source measurements beside the explanation; retain a metrics-only fallback when GPT fails.

**Done when:** an explanation can be traced to its run and displayed evidence, including cases where the available measurements cannot establish a cause.

## 7. Complete the offline regression suite

- [ ] Cover valid commands, wrong JSON types, unknown/extra fields, unknown files/receivers, invalid bounds, missing essentials, and unsupported requests.
- [ ] Supply an invalid model-generated proposal through the stub GPT client and prove that Java rejects it with no engine side effect.
- [ ] Cover status before, during, and after a transfer, current/last ID resolution, conflicting starts, and integrity-dependent completion.
- [ ] Cover byte-to-packet window conversion, worker responsiveness, unavailable engine counters, and honest real-state mapping without fabricating observer data.
- [ ] Use a local HTTP test endpoint to verify API request construction and parsing of representative success, refusal, incomplete, malformed, timeout, and error responses.
- [ ] Verify that API failures/retries cannot duplicate transfer starts and that credentials are absent from captured logs.
- [ ] Cover explanation fixtures with missing metrics, engine failure, and insufficient causal evidence; avoid assertions that require exact prose.
- [ ] Run the complete offline suite without an API key or live OpenAI access and document its command and expected scope.

**Done when:** offline checks pass reproducibly and show which behavior belongs to Java validation, API handling, and the simulated engine. These checks do not claim UDP correctness or real network performance.

## 8. Evaluate live GPT before the full impaired demo

- [ ] Add an explicitly enabled live-test path that is excluded from the normal offline test run.
- [ ] Create a small prompt set covering clear starts, paraphrases, units, omissions, ambiguous references, status, unsupported operations, and explanation questions.
- [ ] Run the prompt set with the configurable recommended model and record model ID, test date, prompts, proposed commands, and Java decisions without secrets.
- [ ] Evaluate command/argument correctness and whether clarification was appropriate rather than comparing exact response wording.
- [ ] Check explanations against known fixture measurements for unsupported numbers or causal claims; record failures and adjust prompts/schemas as needed.
- [ ] Demonstrate natural-language start with the real adapter, basic observable status, fixture-grounded explanation in clearly labelled test mode, and deterministic Java rejection.

**Done when:** live GPT behavior has been reviewed against explicit expectations, and every demonstration using fake data is labelled synthetic.

## 9. Complete observer, metrics, and fault-scenario integration

- [ ] Adopt Person 1's backward-compatible caller-UUID/observer hooks and verify ID mapping across the adapter, engine events, and Person 2's summaries.
- [ ] Connect real observer snapshots and Person 2's saved summaries; expose detailed states/progress only when supported by actual observations.
- [ ] With Person 1, verify bounded START/FINISH recovery, peer/transfer-ID checks, receiver no-overwrite behavior, and receiver restart expectations for the selected scenarios.
- [ ] Run real success/failure transfers, query observed progress while active, and verify that completion follows integrity confirmation; a rejected command must start no network activity.
- [ ] With Person 2, run a loss/delay scenario and explain its actual collected metrics beside the underlying evidence.
- [ ] Keep GPT latency separate from engine transfer timing and rerun the relevant offline regression checks after integration changes.

**Done when:** the same CLI works with real transfers and measured summaries, with clear failure reporting and verified completion.

## 10. Prepare documentation and the final demonstration

- [ ] Write README instructions for Java/Maven setup, configuration, credentials, building, offline tests, opt-in live tests, fake mode, and real mode.
- [ ] Document supported commands, byte-to-packet window conversion, retry-round semantics, ID resolution, receiver restart/output policy, and unavailable metrics.
- [ ] Give Person 1 the validation/dispatch boundary description and interaction flow for the protocol specification and sequence diagram.
- [ ] Give Person 2 the agreed metric/display requirements and ensure their evaluation uses actual experiment outputs rather than fixtures.
- [ ] Write a repeatable demo script covering natural-language transfer, live status, loss/delay metrics, an explanation beside evidence, and deterministic rejection by Java.
- [ ] Rehearse the script from a fresh terminal using the documented commands and record the required configuration and sample file names.
- [ ] Review the final diff for credentials and misleading synthetic results; update the handoff note to the final implemented contract and mark only verified tasks complete.

**Done when:** another teammate can follow the README and run the demo, and the documents describe the actual tested implementation.
