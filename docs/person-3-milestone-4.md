# Milestone 4: real engine adapter and responsive CLI

This milestone connects the validated commands to Person 1's existing blocking sender. It adds no GPT client, protocol recovery logic, engine observer, wire-ID hook or Person 2 metric/logger implementation.

## The adapter

[`RealTransferService`](../src/main/java/nettransfer/control/engine/RealTransferService.java) implements the same `TransferService` used by the fake, plus `AutoCloseable` for resource cleanup.

1. `start(request)` atomically reserves the single active slot, records a `RUNNING` snapshot and queues the work. It returns an application transfer/run ID without waiting for the transfer.
2. A named background worker reads source-file size metadata, opens a fresh `UdpChannel`, and constructs the unchanged `SenderEngine` using the validated destination and settings. Source-file size is metadata, not a measurement of bytes delivered.
3. The channel receives a finite **2,000 ms initial response timeout** before `sendFile` is called. This is an adapter setting, separate from the default **200 ms DATA retransmission timeout**. The existing engine switches to the requested timeout after START_ACK and also uses it while waiting for FINISH_ACK. The adapter does not add control-handshake retries.
4. When the engine returns, the adapter closes the channel and freezes the outcome. Only `TransferResult.isSuccess()` yields `COMPLETED` with `VERIFIED` integrity. Returned failures, exceptions and missing confirmation yield `FAILED` with `UNCONFIRMED` integrity. Failure text is retained, but not parsed to invent a typed integrity cause.
5. A new transfer may start after the previous one finishes or fails. Previous snapshots and final summaries remain readable for the lifetime of the service.

The worker never holds the state lock while sending, receiving, hashing or reading file metadata. `status` and `summary` only read published immutable records, so console work does not wait for the engine to finish. Tests use a package-local session factory to hold the worker at a known point; production sessions always call the real sender.

`SERVICE_CLOSED` is a new typed error for a start attempted after cleanup. Worker-submission failure also produces an explicit failure and releases the active slot.

## What real status means now

| Field | Available meaning |
| --- | --- |
| State | `RUNNING`, `COMPLETED`, or `FAILED`; no inferred engine phases |
| Evidence source | `REAL` for the real adapter, `SYNTHETIC` for the fake |
| Application transfer/run IDs | Java-generated; initially identical |
| Protocol transfer ID | Unavailable (`null`); the engine still generates its own UUID internally |
| File size | Metadata read by the worker, when available |
| Unique ACKed bytes, progress, throughput, protocol duration | Unavailable; there are no agreed observation hooks yet |
| Engine chunk count | Existing `-1` sentinel maps to `null`, not zero or a derived estimate |
| Final integrity | Verified only by a successful sender result after its FINISH_ACK handling |

Wall-clock snapshot timestamps describe when a state or metadata observation was recorded. They are not protocol duration and are not used to calculate goodput. Completed runs still have unavailable detailed measurements. A summary is a frozen in-memory outcome, not a persisted experiment report.

The timeout error says that an engine control response was not received; without phase hooks, the adapter cannot reliably identify START versus FINISH from that exception alone. A bounded initial receive does not establish complete recovery or a universal whole-transfer deadline. Peer/UUID validation, control retries and duplicate-control handling remain Person 1's agreed follow-up work.

## CLI and selection

The new entry point is [`TransferCliMain`](../src/main/java/nettransfer/cli/TransferCliMain.java). [`TransferCli`](../src/main/java/nettransfer/cli/TransferCli.java) accepts an injected service, configuration, reader and writer, allowing the same console behavior to be tested with the fake. The original `nettransfer.Main` sender/receiver and JAR manifest remain available.

Launch arguments are trusted operator configuration: an application root and explicit `file-id=path` entries. They become `TransferConfiguration` maps. They are not proposed command arguments, and commands cannot alter those maps. `receiver-a` uses the verified localhost endpoint `127.0.0.1:9000`. The CLI does not automatically approve every file in a directory.

The console supports local help/catalogue display, direct `start_transfer` JSON, local or structured status, structured `explain`, and `exit`. Direct starts cross the same parser, validator and dispatcher built in milestone 3. Every console command receives a Java request UUID.

- Bare `status`, `status this transfer`, or a null structured ID selects the active run, otherwise the most recent terminal run.
- `status current` selects only an active run.
- `status last` / `status last transfer` selects the most recent terminal run, including while another run is active.
- `status <UUID>` selects that exact transfer. Unknown IDs are reported; ambiguous/unrecognized references request clarification.
- Before a first accepted start, there is no selected transfer. A rejected start leaves the previous selection intact.
- Structured `explain` selects an existing frozen summary and displays the question and available evidence. Active runs return `SUMMARY_NOT_READY`. It produces no model explanation and assumes no real saved metric summary.

Natural-language interpretation is still absent. The short status references above are explicit local commands, not a GPT parser.

## Exit and resource ownership

Normal `exit` while a transfer is active reports that it is still running and keeps the console open. After a terminal outcome, exit closes the service. End-of-input is different: the console cannot accept further commands, so it reports interruption when necessary and the launcher closes resources instead of spinning on EOF.

The launcher also installs a shutdown hook. `close()` marks an active run `FAILED`/`UNCONFIRMED`, closes its socket to unblock receive, and interrupts the worker. A channel created concurrently with shutdown is closed before use. A later successful result cannot overwrite that interruption outcome. Socket closure is necessary because thread interruption by itself does not release a blocking UDP receive.

Interruption state is best-effort, in memory; there is no claim that it is saved to disk during process termination. This is resource cleanup, not a newly supported user cancellation command.

## Running and reviewing

See the [README](../README.md) for build commands and the two-terminal receiver/CLI example. Start a fresh receiver for each transfer and choose a fresh output path: the existing receiver processes one file and can overwrite an existing destination. Receiver output protection is not implemented by this sender CLI.

Read the adapter's `start`, `runTransfer`, `finish` and `close` methods first, then the CLI's selection and command handling. The new tests cover worker responsiveness and ownership, unavailable measurements, error mapping, CLI selection/exit behavior, actual UDP timeout/shutdown cases, and a transfer with matching hashes. Exact verification totals and the separate manual CLI-driven transfer are recorded in the [checklist](person-3-task-checklist.md#4-connect-the-real-engine-and-a-responsive-cli-before-gpt).

After review, milestone 5 adds an injectable model client and the Responses API wrapper. It will send proposed commands through the same Java boundary. Real measured explanations continue to wait for Person 2's logging/metrics and the agreed hooks.
