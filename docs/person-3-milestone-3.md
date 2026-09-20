# Milestone 3: deterministic validation and dispatch

This milestone adds a Java library boundary between proposed commands and the existing `TransferService`. Its tests use the simulated service. The UDP engine and its entry point are unchanged; the real adapter and responsive CLI were added separately in [milestone 4](person-3-milestone-4.md). Person 2's logging and metrics are still pending.

The flow is **proposal -> strict parser -> Java validator -> dispatcher -> service**. A proposal is not permission to execute: malformed arguments, unapproved resources and invalid settings produce a typed result before `service.start` is called.

## What changed and where

All seven new production files are under [`src/main/java/nettransfer/control/command`](../src/main/java/nettransfer/control/command):

| File | Responsibility |
| --- | --- |
| [CommandProposal.java](../src/main/java/nettransfer/control/command/CommandProposal.java) | Carries raw tool calls, a clarification question, or an unsupported-request message. The whole call list is inspected before dispatch. |
| [TransferCommand.java](../src/main/java/nettransfer/control/command/TransferCommand.java) | Defines the three parsed commands: `Start`, `Status`, and `Explain`. Parsed commands still need Java checks. |
| [CommandParser.java](../src/main/java/nettransfer/control/command/CommandParser.java) | Reads strict JSON using the existing Gson streaming reader, checking field names, duplicates, required fields, exact types, integer overflow and UUID syntax. |
| [TransferConfiguration.java](../src/main/java/nettransfer/control/command/TransferConfiguration.java) | Holds immutable approved-file and receiver maps, an explicit application root, and the draft settings/defaults. `localhost(...)` supplies `receiver-a` as `127.0.0.1:9000`; the caller supplies approved files. |
| [CommandValidator.java](../src/main/java/nettransfer/control/command/CommandValidator.java) | Resolves approved IDs, checks readable regular files and real-path containment, applies settings bounds/defaults, converts the byte window to packet slots, and generates an application transfer UUID. |
| [CommandDispatcher.java](../src/main/java/nettransfer/control/command/CommandDispatcher.java) | Accepts zero or one proposed call, validates it, and invokes the appropriate service operation. It atomically records start attempts by Java request ID to prevent duplicate starts. |
| [DispatchResult.java](../src/main/java/nettransfer/control/command/DispatchResult.java) | Returns typed acceptance, status, selected summary, clarification, unsupported or rejected outcomes. Start results include a Java-generated settings description for the later CLI. |

The existing [TransferError.java](../src/main/java/nettransfer/control/TransferError.java) gains command/resource/clarification/replay error codes. Its earlier engine/service codes remain intact. No runtime dependencies were added.

## A start request, step by step

Java configures the available resources, for example:

```java
TransferConfiguration configuration = TransferConfiguration.localhost(
        projectRoot, // explicit absolute Path, selected by Java
        Map.of("report", Path.of("data/input/report.txt")));
```

This does not create the file or approve arbitrary model-supplied paths. The file must already exist and pass validation when a start is requested. The application root does not depend on a model-generated path or the location of a source file.

For tool name `start_transfer`, the argument JSON might be:

```json
{
  "file_id": "report",
  "receiver_id": "receiver-a",
  "window_bytes": 65536,
  "timeout_ms": null
}
```

1. The parser requires exactly these four fields and checks their types. `null` for a setting means use Java's default; an omitted field is a schema error.
2. The validator resolves `report` and `receiver-a` through the configured maps. IDs are exact and case-sensitive; it never guesses from a similar filename or accepts an arbitrary destination.
3. The source must be a readable regular file under the application's `data/input`. Java resolves real paths: source links may stay within that directory, but cannot escape it. Redirecting `data/input` itself is rejected, even if its target is another directory inside the project.
4. Java applies the 200 ms timeout default, uses 1,024-byte chunks, and converts 65,536 bytes to 64 packet slots. The retry policy remains five consecutive Go-Back-N rounds without progress.
5. The dispatcher records the caller-generated request UUID and calls `service.start` once. The service atomically reserves the single active slot. The returned application transfer/run IDs remain distinct from the unknown wire ID.

The request ID belongs to one logical user request and is created in Java, outside the proposed JSON. Reusing it after an attempted start returns `REQUEST_ALREADY_DISPATCHED`, including after completion or service rejection. A new explicit user request gets a new UUID. The attempt record is in memory for one dispatcher instance; it is not persistent logging or cross-process deduplication. A proposal rejected before start can be corrected without consuming a start attempt.

The source checks describe the file at validation time. They do not lock it against later edits or replacement. The future adapter must handle file-access failures when the engine opens the source; this milestone opens no transfer socket.

## Settings and rejected inputs

The policy values are centralized in `TransferConfiguration` and remain the handoff's draft choices, pending team review:

| Setting | Default | Permitted values |
| --- | --- | --- |
| Chunk payload | 1,024 bytes | Fixed by Java |
| Requested window | 1,024 bytes | 1,024 through 1,048,576 bytes |
| Retransmission timeout | 200 ms | 50 through 5,000 ms |
| Consecutive no-progress retry rounds | 5 | Fixed by Java |

Window conversion rounds down: `2047 / 1024` gives one packet, or 1,024 effective bytes. `DispatchResult.Started.settingsDescription()` displays both 2,047 requested bytes and one packet/1,024 effective bytes. It does not call that unused remainder delivered data or measured overhead.

The structured contract accepts integer literals in bytes and milliseconds. Unit strings such as `"64 KiB"`, numeric strings such as `"65536"`, decimal literals such as `1024.0`, and exponent notation such as `1e3` are rejected. Later natural-language interpretation must produce normalized integer values: KB means 1,000 bytes and KiB means 1,024 bytes. Java does not silently reinterpret explicit units.

The parser also rejects comments, trailing JSON, non-object arguments, duplicate keys (including escaped spellings), unknown fields, wrong types, overflowing integers, and malformed UUIDs. Argument text is bounded to 16,384 Java characters. Raw paths, addresses, ports, rate limits, pause and cancellation are outside these command schemas. An unknown tool returns an unsupported outcome; extra arguments produce a schema rejection.

Missing or ambiguous information from an interpreter has a non-executing `CommandProposal.Clarification` form. Blank required selections also return clarification. Required file/receiver fields omitted from JSON or set to `null` fail the strict schema rather than being silently defaulted. Unknown nonblank IDs are rejected. Conversation memory and natural-language ambiguity resolution remain later work.

## Status and explanation selection

- `status` accepts exactly `{"transfer_id": null}` or a complete UUID string. It returns a service snapshot.
- `explain` accepts exactly `{"run_id": null, "question": "Why did it fail?"}` or a complete UUID string. It retrieves the selected frozen service summary and retains the question. It generates no prose and loads no experiment logs.
- A concrete unknown ID returns `UNKNOWN_TRANSFER`. An active run's summary returns `SUMMARY_NOT_READY`; the dispatcher does not substitute an earlier completed run.
- A null ID uses a trusted `CommandDispatcher.Selection` supplied by Java. Without one, the result requests selection and makes no service call. Milestone 4's CLI now tracks current/last runs and supplies that context; this dispatcher never guesses it.

The fake's evidence remains `SYNTHETIC`; unavailable metrics and wire IDs remain null. No metric or logger implementation is assumed.

## Reading and testing order

Read `CommandParser`, then `CommandValidator`, then `CommandDispatcher`. Follow the accepted-start test in [CommandDispatcherTest](../src/test/java/nettransfer/control/command/CommandDispatcherTest.java) to see all three connected to the fake. [CommandParserTest](../src/test/java/nettransfer/control/command/CommandParserTest.java) exercises malformed and deceptive JSON; [CommandValidatorTest](../src/test/java/nettransfer/control/command/CommandValidatorTest.java) checks approved resources, paths and settings.

Run the focused checks with:

```powershell
mvn -o '-Dtest=nettransfer.control.command.*Test' test
```

Use `mvn -o verify` for the complete suite and JAR build. Offline mode uses the already-installed Maven dependency cache. The new tests need no GPT credentials or real transfers. Verification totals and any platform limitations are recorded in the [checklist](person-3-task-checklist.md#3-build-deterministic-command-validation-and-dispatch).

Milestone 4 now wraps the blocking sender on a worker and adds a responsive CLI. It reports coarse real outcomes with unavailable measurements until the agreed observation hooks and Person 2's metrics/logging exist.
