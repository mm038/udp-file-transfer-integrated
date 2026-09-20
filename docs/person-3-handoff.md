# Person 3 handoff: LLM command and analysis interface

Updated September 20, 2026 against engine commit `15a3fc36643a70b54c2fc767038ff4cc547cf31b` (Stage 10.5); milestones 2-4 were committed as `1571613`, milestone 5 as `d9cc443`, and the independent milestone 6 boundary as `ab3c4c0`. The original boundary passed 437 tests; its HTTP follow-up passed 498. Milestone 7's independent offline regression scope is now complete: **566 tests across 30 classes**, zero failures/errors/skips, and a built JAR with `mvn -o verify`. The existing uncommitted HTTP implementation was preserved. See the [milestone 7 audit/walkthrough](person-3-milestone-7.md) and [checklist](person-3-task-checklist.md). Earlier milestone 4 evidence includes matching SHA-256 hashes. The engine remains unchanged. Actual metrics/logging, concrete identity/event/storage integration and live model evaluation remain pending. Stop for review before milestone 8; no live call, commit or push was performed.

**Latest agreement:** the user confirms Person 2 has accepted the complete [revised metric set](Metrics_Summary_Revised.md), including its supporting fields. Use that set; do not request scope approval again. It supersedes the original metrics proposal discussed in the [review history](person-2-metrics-review.md). Milestone 7 now tests all 26 numerical fields through the existing envelope, HTTP projection and CLI display, with typed supporting metadata kept in test-only records. These synthetic compatibility cases are not an implemented producer schema or measured evidence. Actual logging/summary implementation, concrete serialization/events, metadata/outcome reconciliation and verified identity/hooks integration remain pending.

Person 3 will build a Java command-line interface that translates natural-language requests into validated transfer commands, reports current status, and explains recorded results. A working real engine is now available. Develop a real adapter early, and retain a simulated service for predictable tests and for status/analysis work while measurement hooks are being added.

The PDF is the source of assignment requirements. `Reliable_UDP_Implementation_Plan.docx` is the team's proposed workflow. Choices below are recommendations for this implementation, not additional instructor requirements. Follow the [task checklist](person-3-task-checklist.md) to implement them.

## Verified starting point

- Local branch: `person-3/llm-integration`; engine revision: `15a3fc3`.
- Java 17 and standalone Maven 3.9.16 are configured. A Maven Wrapper is still a recommended future convenience, not a blocker for development.
- The user's September 20 `mvn clean verify` run rebuilt the project and reported 78 tests across 13 classes, with no failures, errors or skipped tests, and `BUILD SUCCESS`.
- The user ran the packaged sender and receiver in separate terminals. Both reported `SUCCESS: Transfer completed and verified`. The source and destination each contain 5,405 bytes and have SHA-256 `50AEF3550D0C6311A8A1F616CFAE1E5DB2ADFF5CE78B44F833F822139DD1A93F`.
- The manual files are `target/manual-input.txt` and `target/manual-received.txt`; they are temporary build-directory artifacts and will be removed by `mvn clean`.

This establishes a successful local transfer baseline. It does not establish whole-engine recovery from every loss, malformed packet or unreachable-peer scenario. No additional test run was needed for this documentation update.

## 1. Scope and ownership

| Area | Primary owner | Handoff to Person 3 |
| --- | --- | --- |
| UDP sender/receiver, framing, handshake, ACKs, recovery, integrity, completion | Person 1 | Engine adapter accepting a validated request; current state; explicit completion/failure result |
| Impairment simulation, event logging, metric calculations, experiments | Person 2, with engine hooks from Person 1 | Timestamped snapshots, saved summaries and selected event evidence with documented units and meanings |
| CLI, GPT wrapper, command schemas, Java validation, dispatch, explanations | Person 3 | Working interface, independent tests, integration contract, README and demo script |
| Shared request/status/result types and configuration rules | All three; Person 3 drafts | One agreed contract used by both the simulated and real services |
| Protocol specification and evaluation report | Persons 1 and 2 respectively | Person 3 supplies the validation-boundary description and one checked explanation example |

Required interactions: start a transfer from natural language; ask for live status; request an explanation using recorded measurements. The demo must include a real transfer, impairment with metrics, an explanation beside its evidence, and rejection by the Java validator.

This is a custom application protocol over UDP, not standard FTP compatibility. The initial interface handles one file per request and one active transfer per application instance. Pause/resume, cancellation, directory transfer, remote shell commands, a web UI and automatic tuning are outside the initial scope.

## 2. Recommended decisions

| Decision | Selected approach | Reason |
| --- | --- | --- |
| Language/build | Java 17 and working standalone Maven 3.9.16; add a pinned wrapper later | Matches the verified setup; wrapper adoption need not delay the adapter |
| Runtime dependencies | JDK `HttpClient` and existing Gson 2.11.0 | Fits the repository's decision to use Gson as its only external runtime dependency |
| Testing | Existing JUnit 5; stub model client; simulated service plus real-adapter checks | Preserve the 78-test baseline and isolate interface failures from networking behavior |
| User interface | Java terminal loop with local help and direct status display | Small scope that satisfies the handout and is easy to demonstrate |
| Integration | An in-process adapter around the existing blocking `SenderEngine.sendFile` | Reuses Person 1's engine without requiring an asynchronous engine rewrite |
| GPT access | Responses API, initially `gpt-5-mini`, configurable through `OPENAI_MODEL` | Suitable candidate for bounded command interpretation; verify quality with the prompt set before freezing the demo configuration |
| Execution-affecting output | Three strict function tools: `start_transfer`, `status`, `explain` | Makes the permitted operations explicit and independently checkable in Java |
| Tool execution | Zero or one proposed call per user turn; validate before dispatch | Keeps execution bounded and easy to audit |
| Analysis | A separate request containing a frozen Java-produced evidence snapshot, with no executable tools | Keeps explanations tied to a selected run |
| Resource selection | Approved file and receiver IDs | Java retains control of real paths, addresses and ports |
| Concurrency | Transfer worker separate from CLI/API work; one active transfer | Status remains available during transfer and API failures do not stop UDP recovery |
| Measurements | Java computes metrics; GPT interprets supplied values | Preserves the assignment's measured-evidence requirement |
| Development order | Contract, validator and test doubles, early real adapter/CLI, GPT, measured status and explanations, impaired integration | Uses the available engine immediately while metrics work proceeds in parallel |

The Responses API supports strict function schemas and restricting parallel calls. We will explicitly enable strict mode and disable parallel tool calls, then enforce the same restrictions in Java. These are API format controls, not substitutes for application validation. See the [official function-calling guide](https://developers.openai.com/api/docs/guides/function-calling). `gpt-5-mini` supports Responses, function calling and structured outputs; account access and suitability still need a live check. See the [model reference](https://developers.openai.com/api/docs/models/gpt-5-mini).

## 3. Engine facts and remaining assumptions

The source references below describe the current implementation, not promised extensions:

| Area | Verified engine behavior | Consequence for Person 3 |
| --- | --- | --- |
| Sender | [`SenderEngine.sendFile(String)`](../src/main/java/nettransfer/transfer/SenderEngine.java) blocks until it returns a result or throws | Run it on a worker and keep console/API work separate |
| Receiver | [`ReceiverEngine.receiveFile(String)`](../src/main/java/nettransfer/transfer/ReceiverEngine.java) accepts a caller-selected output path and processes one transfer | Restart the receiver for each initial demo; coordinate a reusable receiver loop later |
| Reliability | Cumulative ACKs, Go-Back-N retransmission and discarded out-of-order DATA | Use these semantics in status, metrics and explanations; the earlier individual-ACK proposal is superseded |
| Window | [`SenderWindow`](../src/main/java/nettransfer/transfer/SenderWindow.java) takes a count of packets/chunks | Convert validated byte windows to whole packet slots in the adapter |
| Defaults | [`Main`](../src/main/java/nettransfer/Main.java) uses 1,024-byte chunks, 1-packet window, 200 ms timeout and retry limit 5 | Preserve these values for initial integration; tune only in documented experiments |
| Retry limit | [`RetransmissionController`](../src/main/java/nettransfer/transfer/RetransmissionController.java) counts consecutive retransmission rounds without progress and resets on progress | Its counter is not the total retry/packet count required for analysis |
| Identity | [`ControlMessage.createStart`](../src/main/java/nettransfer/protocol/ControlMessage.java) generates a wire UUID inside the sender | Add an agreed identity hook; do not assume the application can currently read that UUID |
| Outcome | [`TransferResult`](../src/main/java/nettransfer/transfer/TransferResult.java) exposes success, message and a chunk count; engines currently return `-1` for the count even on success | Treat that count as unavailable; enrich or supplement the result for actual metrics |
| Monitoring | No observer, live snapshot, event logger or saved metric summary is implemented | Only coarse real status is available to a wrapper until hooks exist |
| CLI documentation | Existing `Main` still requires `<sender|receiver> <port> <filePath>`; milestone 4 adds `nettransfer.cli.TransferCliMain` | README now documents the legacy entry point and the validated responsive console |

| Assumption | How we proceed now | What must be checked at integration |
| --- | --- | --- |
| OpenAI is an approved API route | Build the API-neutral interface and offline tests first | Course approval and working API access before live model tests |
| The sender/client and CLI run in one JVM | Person 3 makes service start nonblocking by wrapping the existing engine in a worker | Persons 1 and 2 agree lightweight observation hooks; no engine concurrency rewrite required |
| Receiver is started separately | Alias `receiver-a` maps to the tested `127.0.0.1:9000` endpoint | Coordinate receiver restart/reuse, permitted output roots and no-overwrite behavior |
| The existing reliability design is retained | Adopt cumulative ACKs, Go-Back-N, CRC32 and SHA-256 | Person 1 owns wire behavior and recovery fixes; keep them separate from the GPT layer |
| Detailed progress will become observable | Show only honest coarse status for the early adapter; use labelled fixtures for richer UI tests | Persons 1 and 2 supply phase, ACK-progress, timing and packet-attempt observations |
| Recorded summaries will be available | Keep fixtures and a summary-provider interface ready | Person 2 supplies persisted real summaries, including failures, before real metric explanations |
| A run is linked to its wire transfer | Prefer a backward-compatible sender overload accepting the controller-generated UUID | Until that exists, keep application IDs distinct and the unknown wire ID nullable; never fabricate a mapping |
| All measurements have defined units and availability | Use explicit fields and `null` for unavailable values | Person 2 confirms timing boundaries, counters, formulas and evidence attribution |
| Initial operation is on localhost | Configure a fixed receiver allowlist | Additional hosts require configuration changes in Java, not model-supplied addresses |

The team should review the missing hooks early. The validator, worker adapter, coarse status and tests can proceed while detailed observations and summary storage are being built.

## 4. Shared Java contract

Milestone 2 implements the draft types and `start`/`status`/`summary` interface below under `nettransfer.control`. Both `simulation.FakeTransferService` and milestone 4's `engine.RealTransferService` now implement it. The real adapter owns a worker and per-run UDP channel; the proposed engine UUID/observer extensions remain unimplemented. The initial metric record is deliberately a small draft subset, not Person 2's agreed or implemented experiment schema.

Construct the existing engine with `SenderEngine(channel, destinationAddress, destinationPort, chunkSize, windowPackets, timeoutMillis, retryLimit)`. The adapter owns and closes its `UdpChannel`, resolves validated paths/endpoints and calls `sendFile` on a worker. It must apply a finite receive timeout before calling the engine: the current engine sets its timeout only after START_ACK. A future/task timeout by itself is insufficient if the blocking socket remains open.

| Operation | Input | Result and responsibility |
| --- | --- | --- |
| `start(validatedRequest)` | Java-resolved source, endpoint and effective settings, plus Java-generated request identity | Reserve the active slot atomically and return `transferId`/`runId` promptly; start the engine in a worker |
| `status(transferId)` | Existing transfer ID resolved by Java | Return an immutable, timestamped `TransferSnapshot`; do not wait for completion |
| `summary(runId)` | Known run ID | Return a frozen `TransferSummary` or a typed unavailable/not-ready response |

Initially expose `RUNNING`, `COMPLETED` and `FAILED`, plus nullable measurements: these are observable from worker submission and the engine outcome. Once phase hooks exist, expose `STARTING -> TRANSFERRING -> VERIFYING -> COMPLETED`, with `FAILED` reachable from active states. Do not infer phase or progress from elapsed time. No transfer is represented by an empty selection, rather than a fabricated transfer with zero metrics. `COMPLETED` follows the sender's successful result after FINISH_ACK; robust matching of the acknowledgement to the expected peer/transfer remains part of Person 1's protocol work.

The controller creates an application transfer UUID and initially uses it as the run ID. Recommended engine extension: accept that UUID through an overload while retaining the existing method for existing tests/callers. With that extension, application and wire IDs can be identical. Before it exists, expose a separate nullable `protocol_transfer_id`; the internal engine UUID is unknown. Keep the same distinction in fixtures, logs and summaries.

If final confirmation is missing, the sender must report failure or unconfirmed integrity, even if the receiver may have the file. Record receiver verification evidence separately when available. Allow a later transfer after completion or failure, preserving previous summaries.

### Engine work to coordinate before fault demonstrations

- Person 1: bounded START/FINISH waiting, retransmissions and duplicate-control handling, including retained completion state to answer a repeated FINISH after FINISH_ACK loss. The current engine has no retry loops for these control exchanges.
- Person 1: validate expected peer address/port and transfer UUID on relevant DATA, ACK and control messages; these checks are not consistently enforced in the current engine.
- Person 1/receiver launcher: permitted output root, existing-file policy and partial-file handling. Current `RandomAccessFile(..., "rw")` behavior can overwrite an existing destination; our proposed no-overwrite policy is not implemented yet.
- Persons 1 and 2: a no-op-by-default observer for phase changes, validated cumulative ACK progress, packet send/receive attempts, retransmissions, timeouts and terminal results. It must never wait for GPT. Person 2 uses those observations for counters, timing, event records and summaries; Person 3 uses them for immutable snapshots.

These are focused integration/reliability tasks, not reasons to replace the engine or block independent validator development. Agree ownership before changing shared engine files.

Use typed errors such as `INVALID_COMMAND`, `UNKNOWN_FILE`, `UNKNOWN_RECEIVER`, `INVALID_PARAMETER`, `TRANSFER_BUSY`, `UNKNOWN_TRANSFER`, `SUMMARY_NOT_READY`, `EVIDENCE_UNAVAILABLE`, `MODEL_UNAVAILABLE` and `TRANSFER_FAILED`. Do not turn an API error into a transfer failure if the engine is already running.

One logical CLI request may cause at most one engine start. Java assigns a request ID and records whether it has been dispatched; retrying an API request or receiving repeated model call output must not dispatch that request again. A new explicit user request after completion may legitimately start another transfer.

## 5. Commands, context and validation

Milestone 3 implements the Java parser, validator and dispatcher for the three operations below. It checks configured resources and settings, rejects invalid or multiple proposed calls before dispatch, and prevents repeated start attempts for the same Java request ID. Milestone 5 adds GPT response interpretation and strict tool schemas in front of that same boundary. Milestone 6 cross-checks `explain` selection against the service's run/transfer mapping and passes the frozen outcome to a separate analysis flow. Explicitly injected synthetic fixtures exercise scripted drafts and the tool-free HTTP adapter against a local test server; real recorded evidence remains unavailable.

| Tool | Proposed model arguments | Java behavior |
| --- | --- | --- |
| `start_transfer` | `file_id`, `receiver_id`, `window_bytes`, `timeout_ms` | Resolve IDs, apply defaults for nullable settings, check access/bounds/lifecycle, then start |
| `status` | `transfer_id` | A concrete ID selects that transfer; `null` selects the current transfer, or the last transfer if none is active |
| `explain` | `run_id`, `question` | A concrete ID selects that run; `null` selects the current run or, if none is active, the most recent terminal run; a selected active run returns `SUMMARY_NOT_READY` |

Required file/receiver IDs cannot be guessed when the user has not identified them. The prompt may map a filename or alias only when the configured catalogue gives an unambiguous match. Missing essential information produces a clarification question and no execution. Retain a small amount of local conversational context for the clarification reply; Java remains responsible for selecting existing IDs.

In strict tool schemas, every declared property is required and additional properties are prohibited. A configurable setting may have a nullable type to mean "use the Java default". Omission of a required JSON field is different from an allowed explicit `null`. Tool names are the allowed action names; convert them into one canonical Java command representation.

Treat all tool arguments as untrusted, including output from a strict-schema model. Reject malformed JSON, duplicate keys, unknown or extra fields, wrong types, fractional/overflowing integer settings, unknown IDs, unsupported settings and out-of-range values. Do not rely on Gson coercion or silently ignore unknown fields. Reject multiple executable calls before dispatching any of them.

Do not add generic file-reading, HTTP, socket, code-execution or shell tools. Receiver output-path validation remains Person 1's responsibility, even though Person 3 validates sender requests.

### Proposed settings

Retain the engine's verified defaults for the first adapter. Proposed application bounds and file policies below still need team review; they are not handout requirements or measured optimal values. Centralize them in Java configuration rather than duplicating them in prompts.

| Setting | Proposed value | Meaning |
| --- | --- | --- |
| Chunk payload | 1,024 bytes | Current receiver accepts or rejects the proposal within 1-1,024 bytes; it does not return an adjusted size |
| Default window | 1,024 bytes, converted to 1 packet | Matches the current `Main` baseline; explicit larger windows remain supported by the engine |
| Allowed window | Proposed 1,024 to 1,048,576 bytes | Convert with `floor(window_bytes / chunk_size)` and require at least one slot; record the requested budget and effective packet capacity |
| Default retransmission timeout | 200 ms | Matches current `Main`; the earlier 300 ms proposal is superseded for baseline integration |
| Allowed timeout | 50 to 5,000 ms | Includes the plan's suggested 80 ms comparison |
| Retry default | 5 consecutive Go-Back-N retransmission rounds without progress | Matches `Main`; one round may resend several packets, and progress resets the consecutive-round counter |
| Rate requests | Unsupported initially | Reject explicitly until the engine actually implements pacing and its bounds |
| Source/output policy | Approved files under `data/input`; received files under `data/received` | Java resolves real paths, checks containment including links, and applies a no-overwrite policy |
| Run evidence | Person 2 now proposes one summary record per transfer in JSONL or CSV; event logging remains a separate requirement | Final format, location, identity association and finalization rules are pending. Earlier JSON-per-run storage was a recommendation, not an implemented contract. |

Example: a 64 KiB request becomes 65,536 bytes, then 64 packet slots at 1,024 bytes per chunk. A non-multiple byte budget rounds down to whole slots; display the effective capacity. The original plan's 32 KiB/300 ms settings may be evaluated later as explicit experimental configurations.

The engine owner defines the complete recovery deadlines, inactivity policy and duplicate-completion retention period. The current retry limit applies to DATA recovery and does not itself bound the whole transfer or specify control-message retry behavior.

Use bytes and milliseconds in contracts. Define KB as 1,000 bytes and KiB as 1,024 bytes; show normalized settings to the user. Java performs bounds checks on the resulting integer values. Do not silently reinterpret explicit units. Configuration paths resolve against a documented application/project root; never depend on a model-generated path.

## 6. Status and metric handoff

**Accepted scope:** use the complete [revised metric set and supporting fields](Metrics_Summary_Revised.md). The original `Metrics_Summary.docx` supplied terminal-summary names and an illustrative example; the revised document adds assignment coverage, corrects the DATA-count illustration and records the user's confirmation of Person 2's agreement. The [review history](person-2-metrics-review.md) explains that progression. Keep `packet_loss_rate` and `delay_ms` labelled configured; do not treat their values as observed loss or RTT. Preserve units and explicit missing evidence. Illustrative numbers remain synthetic.

The accepted field scope does not supply an implemented summary/event schema or establish the engine/application identity mapping. The live snapshot suggestions below remain separate integration recommendations. The revised summary's definitions are the working baseline for the accepted set and supersede earlier accounting recommendations where they differ; remaining implementation details must be checked against Person 2's actual outputs. The existing fixture envelope is still local test data, not a producer-schema implementation.

Recommended `TransferSnapshot` fields: `schema_version`, `transfer_id`, nullable `protocol_transfer_id`, `run_id`, `state`, `snapshot_at`, `file_size_bytes`, `unique_payload_bytes_acked`, `progress_percent`, `elapsed_ms`, `recent_goodput_bytes_per_second`, `recent_interval_ms`, `retransmissions`, and nullable error information. Until observations exist, detailed real measurements remain unavailable. Any wrapper elapsed time must be labelled as operation elapsed time; it does not supply the engine timing boundaries required for protocol goodput.

On the sender, live progress uses unique payload bytes acknowledged by the receiver. Label it that way: bytes submitted to a socket are not delivery, and acknowledgement is not final whole-file verification. If Person 2 can provide an actual receiver snapshot, expose it separately with its observation time. Never silently substitute one meaning for another.

A validated cumulative ACK can acknowledge several chunks at once. For contiguous acknowledgement through sequence `k`, acknowledged payload is `min(file_size_bytes, (k + 1) * chunk_size)` using overflow-safe arithmetic. Apply this only to accepted ACK progress for the active transfer; repeated or stale ACKs must not add delivered bytes. Count ACK datagrams separately from newly acknowledged chunks. The resetting consecutive-round getter must never be used as the lifetime retransmitted-packet count.

Use a nominal one-second recent-goodput interval; when less history exists, report the actual interval used. Avoid division by zero. A zero-byte file has no meaningful byte-percentage progress while active; display its state and show completion only after verified completion.

The following groups explain the original handoff's evidence needs. Use the revised summary for the accepted field list and working definitions; this grouping does not add further agreed fields. Keep a metric-definition version so fixtures and actual summaries can be checked for compatibility:

| Evidence group | Required contents and interpretation |
| --- | --- |
| Identity/outcome | Run and transfer IDs, file ID, file size, state, integrity result, and failure reason if any |
| Time/data | Unique payload bytes, elapsed duration and exact timing boundaries; duration from Java's monotonic clock, wall-clock timestamps separately |
| Throughput | Useful payload goodput and its units; traffic throughput separately if reported |
| Packet activity | Sent, received, acknowledged, timed-out, duplicate and retransmitted counts, with endpoint and message-type attribution |
| Ratios/overhead | Retransmission ratio with its denominator; protocol overhead with its byte-accounting boundary |
| Latency | RTT sample count, mean and p95; defined RTT variation/jitter if available |
| Configuration | Requested and accepted chunk size, requested byte window and effective packet window, timeout, consecutive-round retry policy, scenario and impairment seed |
| Impairment observations | Configured loss/delay/jitter separately from actual injected drops/delays where measured |

Accounting guidance, with the accepted revised summary taking precedence:

- The revised `throughput_mbps` uses observed unique receiver-delivered payload bytes and the supplied transfer duration. Its proposed timing interval runs from the first START send attempt to the sender's terminal decision, excluding GPT time. Preserve receiver delivery, sender ACK progress and final integrity as distinct evidence. On failure, label the result a failed-run delivered-payload rate; unavailable inputs remain null.
- DATA retransmission ratio is retransmitted DATA attempts divided by all DATA attempts, including retransmissions. Return `null` when the denominator is zero. Control-message retries have separate counts.
- Count logical send attempts separately from datagrams actually emitted after the impairment shim. The revised application-protocol overhead uses emitted UDP payload bytes from both endpoints, including controls and retransmissions, minus unique receiver-delivered payload bytes. Divide by emitted bytes for its fraction, excluding IP/UDP/link headers. Incomplete compatible endpoint evidence makes this metric unavailable.
- Do not count sender and receiver observations of the same datagram as two transmissions. Keep role-specific counters in the summary.
- Use unambiguous RTT samples; exclude samples whose ACK cannot be associated confidently with a send attempt. Person 2 documents the percentile calculation and sampling rule.
- Represent unavailable metrics as `null` plus a reason, rather than zero. Retransmissions never inflate unique payload counters.

Person 2 owns the calculation and logging implementation for the accepted metric set. Record its concrete definitions, types, null representation and observation points in the producer handoff. If a definition changes, update the contract, fixtures, displays and explanation prompts together.

## 7. GPT request and explanation behavior

The command request contains the user's words, the permitted tools, approved identifiers, and the small amount of Java-selected context needed to resolve the request. It contains no file contents or credentials. Treat filenames, user text and event strings as data, not as instructions overriding the permitted operations.

The wrapper reads `OPENAI_API_KEY` from the environment and accepts a configurable model ID. Use a fixed configured OpenAI endpoint, finite connect/request deadlines and bounded retries for transient API failures. API deadlines and UDP retransmission timeouts are separate settings. Authentication errors, refusals, incomplete responses, missing tools and invalid arguments must have explicit handling; none authorizes an engine action.

Milestone 5 implements this boundary with Java `HttpClient` and Gson, an injectable `GptClient`, and a scripted offline stub. Defaults are `gpt-5-mini`, five seconds to connect, 30 seconds per complete request and two attempts. Optional `OPENAI_CONNECT_TIMEOUT_MS` and `OPENAI_REQUEST_TIMEOUT_MS` accept 100-120,000 ms. Clarification retains up to two exchanges under one Java request UUID; direct commands and completed/rejected/failed interpretation clear it. The console waits during a bounded API request while the UDP worker continues independently. See the [milestone 5 walkthrough](person-3-milestone-5.md) for configuration and limitations. No live model evaluation or measurement-based explanation is claimed by these offline checks.

Start responses and factual status displays are rendered from Java results. Plain model text without a validated tool call can request clarification or describe supported usage, but must not be treated as evidence that a transfer started or completed. Keep a local direct status command usable even when model access fails. A normal local `exit` during an active transfer reports that it is still active and keeps the session open; after a terminal state it closes resources. A process shutdown hook should close resources and record interruption on a best-effort basis, without claiming successful completion.

For eventual measured explanations, Java will load the selected frozen summary and, when needed and available, a bounded relevant event excerpt before making a separate analysis request with no execution tools. Request an answer, references to supplied run/field identifiers, and limitations. Java checks that referenced evidence exists and renders original numeric values beside the prose. Preserve the evidence snapshot identity/time so later progress cannot change the basis of the answer.

The independent milestone 6 implementation provides that boundary with bounded synthetic summaries, a scripted client and the separate `ResponsesExplanationClient`; no event excerpts are needed for its cases. It checks run/transfer/protocol/provenance and fixture version before analysis, then checks returned numerical references. The HTTP adapter uses prompt `explanations-v2`, requests a strict JSON explanation, exposes no tools and rejects tool-call output. It and command interpretation share `ResponsesTransport` for deadlines, bounded retries/body size, strict UTF-8 decoding and credential safeguards, while keeping their request/response handling separate.

The launcher constructs the explanation client with the existing environment settings and `SummaryProvider.unavailable()`. Construction does not call HTTP; REAL outcomes still return `EVIDENCE_UNAVAILABLE` before provider/client invocation. There is no real persistence adapter or fixture fallback. Source values and missing reasons remain visible if analysis fails. The fixture contract is local test data, not Person 2's serialized summary schema. This follow-up used synthetic responses from a local HTTP server only; no live OpenAI calls or live-quality claims are part of it. See the [HTTP walkthrough](person-3-milestone-6-http.md).

An explanation must distinguish measured observations from hypotheses. Retransmissions do not directly measure packet-loss percentage; timeout events do not prove congestion; an injected loss probability is not an observed loss rate. A single run does not establish that one setting is faster than another. Comparisons require separately supplied comparable runs, and are optional beyond the initial single-run explanation.

Checks on evidence references cannot prove every prose claim true. Evaluate explanations against known fixtures and manually check the saved real demo example. If the model fails, show the measurements with an explanation-unavailable message. Never overwrite source measurements with model output.

Record the configured model ID, prompt/schema version, validation decision and related run ID for reproducibility. Keep keys, authorization headers, raw secrets and file contents out of logs. Save a redacted example interaction for the README/demo; do not log every raw API exchange by default.

## 8. Independent testing and real integration

| Stage | Model side | Transfer side | Evidence |
| --- | --- | --- | --- |
| Offline tests | Stub client and local HTTP response fixtures | Deterministic simulated service | Command validation, HTTP handling, dispatch, lifecycle and correct evidence selection |
| Optional live model evaluation | Actual configured GPT model | Simulated service | Interpretation of paraphrases/ambiguity and quality of explanations from known evidence |
| Early adapter check | Direct validated Java commands; no GPT required | Existing real UDP engine | Start/terminal outcome, worker responsiveness, settings conversion and honest coarse status |
| Full integrated check | Actual GPT model | Real UDP engine with observations and actual metrics | Assignment interactions, integrity, live progress, responsiveness and measured explanations |

Use synthetic baseline, loss, delay, missing-metric and failure fixtures, clearly labelled. Simulated state advancement should be explicit in automated tests, without long sleeps. At minimum prove that one valid start causes one service call and every invalid proposal causes zero service calls. Exercise status before/during/after a run, summary-not-ready, unknown IDs, conflicting starts, repeated dispatch, API outages and evidence from the wrong run.

Live API tests are opt-in and separate from the normal `test` command. Judge parsed intent, arguments, execution decision and evidence use rather than exact phrasing. Record model/configuration and preserve failures. Synthetic evidence is not a substitute for the required real UDP experiment logs.

Keep real and simulated service implementations selectable behind the same contract. Bring the real adapter in before GPT, then add real snapshots and Person 2's summary provider as hooks become available. After related changes, run the relevant contract checks and verify real natural-language start, live status during an impaired transfer, final integrity, explanation beside metrics and deterministic rejection. Verify that an API failure leaves an already-running transfer under Java control.

## 9. Team review and first milestone

Person 1 should review ID exposure, event hooks, receiver behavior, parameter bounds and control-handshake recovery. Person 2 should review the event data needed for current snapshots, cumulative metrics, timing boundaries and saved summaries. Person 3 owns the asynchronous wrapper, validator and this contract; update fixtures when agreed definitions change.

Milestones 2-5 provide the shared contract, fake, strict validator/dispatcher, real sender adapter, CLI and injectable GPT command interpretation. The path `validated command -> real transfer -> truthful result` has been verified, and GPT interpretation has been checked offline using a stub and local HTTP fixtures. The independent part of milestone 6 supplies the summary-provider boundary, labelled fixture analysis and a separate explanation HTTP adapter tested offline. Milestone 7 audits and reuses that coverage, adding accepted-metric synthetic cases and missing HTTP-envelope checks. Stop for review before milestone 8. Detailed real status/explanations still depend on implemented observation/identity hooks and Person 2's actual summaries/events for the accepted metric set; absent real evidence remains unavailable. The generic envelope checks identity/citations, not cross-field counter consistency; real producer acceptance and outcome reconciliation remain integration work.

### Historical note from the original engine handoff

This original draft is retained for context. Its request to agree metric scope is superseded by the accepted revised set above. Concrete events, storage and identity/hooks integration remain open.

> I pulled Stage 10.5, passed all 78 tests, and verified a real transfer with matching SHA-256 hashes. I'll build the Java validator and an adapter that runs the existing blocking sender in a worker. I'll retain cumulative ACKs/Go-Back-N and initially use your defaults: 1,024-byte chunks, one packet in flight, 200 ms timeout and five consecutive retry rounds.
>
> Person 1: let's agree a backward-compatible way to supply/expose the transfer UUID and a lightweight observer for phase/progress/packet events. We also need bounded START/FINISH recovery, peer/transfer matching, and receiver output/restart behavior for failure demos and repeated transfers. You can keep `sendFile` blocking; I'll handle the responsive CLI.
>
> Person 2: let's agree the event fields, metric definitions, live snapshot and saved summary format. A cumulative ACK may acknowledge several chunks, and the engine's retry-round counter resets, so neither should be mistaken for a lifetime packet count. I'll show unavailable metrics honestly until instrumentation is ready.

No message has been sent to teammates by the assistant. The user has since confirmed Person 2's agreement on the complete revised metric set and supporting fields; that agreement does not imply implemented logs or verified integration hooks.

For setup findings and recommended Maven installation steps, see [Maven setup](maven-setup.md).
