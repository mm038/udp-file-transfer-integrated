# Java UDP file transfer

The Stage 10.5 sender/receiver implements file transfer over UDP using cumulative ACKs, Go-Back-N retransmission, CRC32 and final SHA-256 verification. Person 3 milestones 2-5 add shared types, deterministic command validation, an asynchronous real-engine adapter, a terminal interface and GPT command interpretation.

GPT interpretation is implemented and tested offline; live model evaluation remains pending. Milestone 6 adds an explanation boundary and a separate HTTP adapter, checked offline with explicitly synthetic fixtures, identity checks and evidence references. The complete revised metric set is accepted, but engine observation/identity hooks and Person 2's measured metrics/logging are still pending. The CLI reports coarse real outcomes and `EVIDENCE_UNAVAILABLE` for real explanations without recorded summaries.

## Build and test

Use Java 17 and a standalone Maven installation:

```powershell
java -version
mvn -version
mvn verify
```

The runnable JAR is `target/udp-file-transfer.jar`. With dependencies already cached, `mvn -o verify` runs offline. The tests require no OpenAI credentials. See [Maven setup](docs/maven-setup.md) for the local setup history.

## Run the receiver and new CLI

Create an input file under `data/input`, for example `data/input/report.txt`, and create `data/received` for the output. The CLI approves only the file IDs supplied at startup.

In terminal 1, start the existing receiver with a **fresh output filename**:

```powershell
java -jar target/udp-file-transfer.jar receiver 9000 data/received/report-01.txt
```

The current receiver handles one transfer per process and can overwrite an existing destination. Restart it for each transfer and choose an unused output path. Receiver output protection remains Person 1's follow-up work.

In terminal 2, from the project directory:

```powershell
java -cp target/udp-file-transfer.jar nettransfer.cli.TransferCliMain . "report=data/input/report.txt"
```

The first argument is the application root. Remaining arguments explicitly map file IDs to paths relative to that root; quote an argument containing spaces. Files must pass validation under `<root>/data/input`. `receiver-a` is configured in Java as `127.0.0.1:9000`; commands cannot supply arbitrary addresses or ports.

Type these commands **inside the transfer console**:

```text
catalog
start_transfer {"file_id":"report","receiver_id":"receiver-a","window_bytes":null,"timeout_ms":null}
status
```

Start returns an application transfer/run ID promptly. The worker continues sending while the console accepts status and other commands. Status reports `RUNNING`, `COMPLETED` or `FAILED`; a successful terminal state follows the engine's verified-success result. Protocol UUID, ACK progress, percentage, throughput and protocol duration remain unavailable until real observations exist.

The Java defaults are 1,024-byte chunks, a 1,024-byte window (one packet), a 200 ms DATA retransmission timeout and five consecutive retransmission rounds without progress. Window budgets round down to packet slots. The adapter applies a separate 2,000 ms initial START-response wait so an absent receiver does not leave that receive blocking indefinitely. It does not add handshake retries or a whole-transfer deadline.

## Console commands

| Command | Behavior |
| --- | --- |
| `help`, `catalog` | Show direct commands and approved resources |
| `start_transfer <JSON>` | Strictly parse and validate a proposal before calling the service |
| `status` / `status this transfer` | Select the active run, otherwise the most recent terminal run |
| `status current` | Select only an active run |
| `status last` / `status last transfer` | Select the most recent terminal run, even while another runs |
| `status <UUID>` | Select that exact historical/current application transfer ID |
| `status {"transfer_id":null}` | Structured status with Java's default selection |
| `explain {"run_id":null,"question":"What happened?"}` | Verify the selected frozen outcome's identity; real recorded measurements remain `EVIDENCE_UNAVAILABLE` |
| `ask <sentence>` or an ordinary sentence | Ask the configured GPT client to propose one command, then independently validate it in Java |
| `exit` | Refuse while active; leave after completion/failure |

Every declared JSON field is required. Nullable settings select Java defaults; omitted fields, extra fields, duplicate keys, incorrect types and invalid bounds are rejected. Input numbers are normalized integer bytes/milliseconds. Unknown IDs and ambiguous references do not choose a resource automatically.

Direct commands remain available without GPT credentials. Natural language reads `OPENAI_API_KEY` from the environment and uses `OPENAI_MODEL` (default `gpt-5-mini`). Optional `OPENAI_CONNECT_TIMEOUT_MS` and `OPENAI_REQUEST_TIMEOUT_MS` configure API deadlines; their defaults are 5,000 and 30,000 ms. Values must be 100-120,000 ms. These are separate from the UDP timeout. See the [milestone 5 walkthrough](docs/person-3-milestone-5.md) for configuration and the offline test approach.

For example, `Send report to receiver-a with a 64 KiB window` goes through GPT and then Java validation. Use `ask status of my last transfer` to send a sentence that begins with a reserved direct command to GPT. `explain why the last transfer failed` uses interpretation to select the run, then enters the separate evidence flow. For a real run it shows the coarse outcome and reports missing recorded measurements; it never inserts fixture values. A direct JSON `explain` needs no GPT request.

The explanation tests inject a `SyntheticSummaryProvider` with either a scripted `StubExplanationClient` or the separate `ResponsesExplanationClient` pointed at a local HTTP server. Java verifies run, transfer, provenance and nullable wire identity before analysis, then checks cited field values and units. The HTTP adapter sends the frozen evidence with prompt `explanations-v2`, requests a strict JSON explanation, supplies no execution tools, and rejects tool-call output. It shares bounded HTTP transport with command interpretation while keeping its request and response handling separate. Original evidence and missing reasons remain visible when analysis fails.

The launcher constructs this explanation client using the existing environment settings, with `SummaryProvider.unavailable()`. Client construction makes no HTTP call, and the REAL-evidence gate returns `EVIDENCE_UNAVAILABLE` before invoking it. There is no production fixture switch. The accepted [metric set](docs/Metrics_Summary_Revised.md) still needs actual producer output, serialization/events and verified identity mapping before real integration. Offline HTTP checks do not establish live model quality. See the [original milestone 6 walkthrough](docs/person-3-milestone-6.md) and its [HTTP adapter follow-up](docs/person-3-milestone-6-http.md) for the code, checks and remaining dependencies.

The CLI remembers at most two clarification exchanges. A direct command, API failure, rejection or executed command clears that context. Model text is labelled as non-execution; only Java reports an accepted start or a transfer outcome. The UDP worker continues during an API request, but the console waits for that bounded request before accepting its next line (up to about 60.1 seconds with default retries/deadlines).

End-of-input or process shutdown closes the sender's socket and records interruption in memory on a best-effort basis. It does not claim completion or create Person 2's future logs. There is no user cancellation command.

## Existing direct sender

The original entry point and packaged-JAR behavior remain available:

```powershell
java -jar target/udp-file-transfer.jar sender 9000 data/input/report.txt
```

This legacy command calls the blocking engine directly. The new CLI uses the validator and background adapter instead.

## Review notes

- [Milestone 6 HTTP adapter follow-up](docs/person-3-milestone-6-http.md): separate structured explanations, shared HTTP safeguards, offline verification and review stop.
- [Original milestone 6 walkthrough](docs/person-3-milestone-6.md): provider/explanation flow, identity checks and synthetic fixtures at the committed 437-test checkpoint.
- [Milestone 5 walkthrough](docs/person-3-milestone-5.md): GPT interface, Responses API wrapper, clarification context, validation and offline checks.
- [Milestone 4 walkthrough](docs/person-3-milestone-4.md): adapter, resource ownership, status meanings, CLI selection and limitations.
- [Milestone 3 walkthrough](docs/person-3-milestone-3.md): strict parsing, approved resources and dispatch.
- [Person 3 checklist](docs/person-3-task-checklist.md): verified test counts and transfer evidence.
- [Person 3 handoff](docs/person-3-handoff.md): agreed direction and pending teammate contracts.
- [Protocol specification](PROTOCOL.md): existing wire protocol.

The simulated service remains available behind the same interface for deterministic tests. Its snapshots are labelled `SYNTHETIC` and never fill gaps in real transfer evidence.
