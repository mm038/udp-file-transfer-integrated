# Java UDP file transfer

This project implements a real file transfer over UDP with cumulative ACKs,
Go-Back-N retransmission, CRC32 packet checks, and final SHA-256 verification. It
also includes live metrics, endpoint-local event logs and summaries, verified
sender/receiver reconciliation, and an opt-in command console with deterministic
validation and GPT-assisted natural-language interpretation.

The metrics/logging and LLM/CLI implementations are connected through one
Java-configured trusted logging root. The command console starts the real
sender, reports its immutable live snapshots, persists sender evidence, and can
load validated sender-final or reconciled evidence for explanation. Synthetic
fixtures remain limited to tests and deliberate evaluation; they are never
substituted for missing real measurements.

## Prerequisites, build, and tests

- JDK 17 or later (`java -version` and `javac -version`)
- Maven (`mvn -version`)

From the project root:

```powershell
mvn verify
```

This compiles the project, runs the ordinary JUnit suite, and creates the
runnable shaded JAR at `target/udp-file-transfer.jar`. The JAR's main class is
`nettransfer.Main`. Tests do not make OpenAI API calls or launch the opt-in live
evaluation merely because an API key exists. With dependencies already cached,
`mvn -o verify` can run offline. See [Maven setup](docs/maven-setup.md) for the
recorded local setup notes.

## Run a direct file transfer

The direct entry point uses `storage/outgoing` and `storage/incoming`. Put the
source in `storage/outgoing` first. Both CLI filename arguments must be plain,
safe filenames, not paths, and the destination filename must not already exist.
The application creates the storage directories if needed.

Start the receiver first:

```powershell
java -jar target/udp-file-transfer.jar receiver 9000 received.bin
```

Then start the sender in a second terminal:

```powershell
java -jar target/udp-file-transfer.jar sender 9000 payload.bin
```

The sender reads `storage/outgoing/payload.bin`. After successful SHA-256
verification, the receiver publishes `storage/incoming/received.bin` without
overwriting an existing file. Incomplete transfers remain unpublished, and the
receiver does not trust the remote START filename as a destination path. One
receiver process handles one transfer and exits after its bounded completion
recovery period.

The equivalent Maven commands are:

```powershell
mvn exec:java "-Dexec.mainClass=nettransfer.Main" "-Dexec.args=receiver 9000 received.bin"
mvn exec:java "-Dexec.mainClass=nettransfer.Main" "-Dexec.args=sender 9000 payload.bin"
```

### Reliability and timeout settings

| Setting | Default | Purpose |
| --- | ---: | --- |
| `nettransfer.startHandshakeTimeoutMs` | 1000 ms | Wait per START attempt for a matching START_ACK. |
| `nettransfer.startRetryLimit` | 5 retries | Additional START attempts after the first. |
| `nettransfer.finishHandshakeTimeoutMs` | 1000 ms | Wait per FINISH attempt for an attributable FINISH_ACK. |
| `nettransfer.finishRetryLimit` | 5 retries | Additional FINISH attempts after the first. |
| `nettransfer.receiverInitialTimeoutMs` | 300000 ms | Overall receiver wait for a valid START. |
| `nettransfer.receiverInactivityTimeoutMs` | 60000 ms | Maximum time after START without a newly accepted and written DATA chunk. |
| Receiver completion grace | 6250 ms | Derived as `(FINISH timeout × total attempts) + 250 ms`. |
| DATA retransmission timeout | 200 ms | Deadline for the oldest unacknowledged DATA packet. |
| DATA retry limit | 5 rounds | Maximum consecutive Go-Back-N recovery rounds without ACK progress. |

Only a matching START_ACK completes the sender handshake. Duplicate STARTs for
the active transfer are acknowledged without resetting receiver progress. A
FINISH_ACK must come from the expected peer, match the transfer UUID and type,
parse correctly, and contain an explicit verification result. During completion
grace, duplicate matching FINISH messages receive the cached FINISH_ACK without
another file write, hash calculation, or publication.

For a shorter receiver wait during a manual test:

```powershell
mvn "-Dnettransfer.receiverInitialTimeoutMs=5000" exec:java "-Dexec.mainClass=nettransfer.Main" "-Dexec.args=receiver 9000 timeout-test.txt"
```

## Metrics and live snapshots

Both real engines implement `LiveMetricsProvider`. Programmatic callers can
retain an engine and call `getLiveMetricsSnapshot()` before, during, or after the
blocking transfer method. Each immutable snapshot atomically contains the
current transfer context, metrics, endpoint-local emission evidence, lifecycle
state, supporting observations, and capture time. Terminal snapshots remain
available after return or failure; unknown fields stay null with an unavailable
reason.

Lifecycle states are `NOT_STARTED`, `AWAITING_START`, `TRANSFERRING`,
`AWAITING_FINISH`, `VERIFYING`, `COMPLETION_RECOVERY`, `SUCCEEDED`, and
`FAILED`. Sender and receiver use only the states relevant to their own work.

Receiver snapshots count valid DATA arrivals from the established peer and
protocol UUID, including duplicate and ahead-of-gap arrivals. Delivered payload
bytes increase only after a unique write succeeds. Receiver integrity is set
only after the whole-file SHA-256 comparison and is labelled with receiver
provenance. Sender-only facts such as confirmed transfer success,
retransmissions, ACK arrivals, DATA timeouts, and protocol duration are not
invented on the receiver. The protocol represents an empty file with one valid
zero-length DATA packet, so its receiver metrics show one DATA arrival and zero
delivered payload bytes.

Sender RTT starts after a successful original DATA emission and ends only when
a valid attributable ACK advances the window by exactly one sequence.
Retransmitted sequences, invalid or duplicate ACKs, and cumulative ACKs that
advance more than one sequence are excluded. RTT uses a monotonic clock,
milliseconds, arithmetic mean, and nearest-rank p95.

`UdpChannel` records the encoded application datagram length after each
successful send, including control packets, DATA, ACKs, retries, and recovery
resends. It excludes incoming traffic and UDP/IP/link headers. Sender and
receiver emission totals remain endpoint-local until reconciliation verifies
their shared protocol identity and evidence boundaries.

Sender live snapshots also expose elapsed time and exact sender-confirmed ACKed
payload bytes with a separately named ACK-based rate. Those values do not
masquerade as receiver-delivered bytes or reconciled throughput.

See [Metrics Summary](docs/Metrics_Summary_Revised.md) for field definitions and
[the live demo walkthrough](docs/live-demo-walkthrough.md) for inspection
examples.

## Persistent event logs and endpoint summaries

The direct `nettransfer.Main` sender and receiver enable endpoint-local JSONL
logging under `logs/` by default. Programmatic callers can opt in before a
transfer with `engine.enableEventLogging(logsRoot)`. The root can be changed for
the direct entry point with `-Dnettransfer.logsRoot=<directory>`.

Each process creates a collision-protected run directory containing
`events-sender.jsonl` or `events-receiver.jsonl`, an atomically updated
`run-state.json`, and, for a handled terminal run, `endpoint-sender.json` or
`endpoint-receiver.json`. Events record actual protocol decisions and successful
socket emissions without DATA contents. Attempts and successful emissions are
separate events. A logging failure, interrupted writer, or incomplete run cannot
claim finalized evidence.

After both endpoints finish, reconcile two explicit run directories:

```powershell
mvn exec:java "-Dexec.mainClass=nettransfer.Main" "-Dexec.args=reconcile logs/standalone/<sender-run-id> logs/standalone/<receiver-run-id>"
```

Reconciliation verifies schemas, finalization, roles, protocol UUID, available
application/experiment identities, configurations, event streams, counters,
and emission totals. It then publishes
`logs/standalone/reconciled/<protocol-uuid>/summary.jsonl` and `manifest.json`.
Existing finalized output is not overwritten. Sender-confirmed success and
receiver-observed integrity remain separate, and unavailable values remain null
with reasons. See [LOGGING.md](LOGGING.md) for schemas, formulas, completeness
rules, and the full verification procedure.

The command console uses the same `<projectRoot>/logs` root for sender logging
and validated evidence lookup. After a terminal sender result, `explain` may
initially report `EVIDENCE_PENDING` while the receiver finishes its intentional
completion-recovery period. The repository retrieves exact application,
sender-run, and protocol identities; when both endpoints are final it validates
and, if needed, creates the reconciled summary through the existing exporter.

## Run the validated command console

The new console uses a different, explicit source catalogue. Create a file under
`data/input`, for example `data/input/report.txt`. Start the ordinary receiver
with a fresh filename in terminal 1:

```powershell
java -jar target/udp-file-transfer.jar receiver 9000 report-cli.txt
```

In terminal 2, map one or more approved IDs to relative paths under the project
root's `data/input` directory:

```powershell
java -cp target/udp-file-transfer.jar nettransfer.cli.TransferCliMain . "report=data/input/report.txt"
```

The first argument is the application root. The remaining arguments are
`file-id=relative-path` mappings; quote mappings containing spaces. Java
resolves real paths and accepts only readable regular files that remain inside
`<projectRoot>/data/input`, including after symbolic-link resolution. The only
configured receiver ID is `receiver-a` at `127.0.0.1:9000`; model output cannot
supply arbitrary paths, hosts, or ports.

Inside the console:

```text
catalog
start_transfer {"file_id":"report","receiver_id":"receiver-a","window_bytes":null,"timeout_ms":null}
status
```

The asynchronous adapter returns an application transfer/run ID promptly while
the real sender continues on one worker. It allows only one active transfer.
Status reports `RUNNING`, `COMPLETED`, or `FAILED`. The adapter uses 1,024-byte
chunks, a default 1,024-byte window, a 200 ms DATA timeout, five retry rounds,
and a separate 2,000 ms timeout for each START attempt. The existing five-retry
policy is preserved, so the approximate maximum START handshake duration is
`START timeout × (START retry limit + 1)` (about 12 seconds with adapter
defaults). Window bytes must be 1,024-1,048,576 and are rounded down to packet
slots; timeout milliseconds must be 50-5,000.

The console writes real sender evidence beneath
`<projectRoot>/logs/standalone/<application-transfer-id>/`. Status reads the
adapter's retained `LiveMetricsSnapshot` and displays the protocol UUID, sender
lifecycle, provisional/final endpoint scope, file size, sender-confirmed ACKed
bytes and display-only progress percentage, elapsed time, ACK-based sender
rate, packet/ACK/timeout counters, RTT observations, and endpoint-local UDP
emissions. A missing value is printed as unavailable with its reason; an
observed zero remains zero.

These status values are sender observations. In particular,
`sender_ack_based_rate_mbps` is not receiver-delivered or reconciled
throughput, and ACK progress does not establish receiver SHA-256 integrity.
Reconciled `throughput_mbps` appears only in validated explanation evidence
after receiver delivery evidence is available.

### Console commands and validation

| Command | Behavior |
| --- | --- |
| `help`, `catalog` | Show commands and approved resources. |
| `start_transfer <JSON>` | Strictly parse and validate one start before calling the service. |
| `status`, `status this transfer` | Select the active run, otherwise the latest terminal run. |
| `status current` | Require an active run. |
| `status last`, `status last transfer` | Select the latest terminal run. |
| `status <UUID>` | Select an exact in-memory application transfer ID. |
| `explain {"run_id":null,"question":"What happened?"}` | Select a frozen result and retrieve exact validated REAL evidence from the trusted log root. |
| `ask <sentence>` or an ordinary sentence | Ask GPT for one structured proposal, then validate it independently in Java. |
| `exit` | Refuse while a transfer is active; exit when idle. |

Every structured JSON field is required. Omitted or extra fields, duplicate
keys, wrong types, invalid bounds, unknown IDs, multiple proposed tool calls,
and ambiguous references are rejected. Null settings select Java defaults.
Java, not the model, resolves resources, enforces bounds, prevents duplicate
dispatch, and reports whether a transfer was accepted. Model prose is labelled
as non-execution. The console retains at most two clarification exchanges and
clears that context after direct commands, failures, rejections, or execution.

## GPT configuration and explanation flow

Direct start, status, help, and catalogue commands need no API key. An API key
is required only when natural-language interpretation is requested or when an
AVAILABLE evidence set is sent for explanation:

```powershell
$env:OPENAI_API_KEY = '<set privately>'
$env:OPENAI_MODEL = 'gpt-5-mini'                 # optional; this is the default
$env:OPENAI_CONNECT_TIMEOUT_MS = '5000'          # optional
$env:OPENAI_REQUEST_TIMEOUT_MS = '30000'         # optional
```

Both API timeouts must be 100-120,000 ms and are independent of UDP transfer
timeouts. The shared HTTP transport makes bounded attempts. An absent or invalid
GPT configuration does not disable direct transfer, status, help, or catalogue
commands.

For example, `Send report to receiver-a with a 64 KiB window` is interpreted as
a proposal and then subjected to the same Java parser and validator. Use `ask`
when a natural-language sentence begins with a reserved direct command, such as
`ask status of my last transfer`.

The explanation flow selects a frozen transfer outcome, then uses
`PersistedEvidenceRepository` and `RealMetricsSummaryProvider` to require exact
application, sender-run, and protocol identities. Only complete, final,
validated `SENDER_FINAL`, `RECEIVER_FINAL`, or `RECONCILED` REAL evidence can
reach the existing explanation client. The HTTP client requests strict
structured JSON, exposes no execution tools, verifies cited field values and
units, and preserves the original evidence if model analysis fails.

Non-available evidence never triggers an API request. `EVIDENCE_PENDING` means
recording or receiver recovery is still active; `EVIDENCE_INCOMPLETE` means a
final evidence boundary was not reached; `EVIDENCE_UNAVAILABLE` means no
applicable validated evidence exists; and `EVIDENCE_REJECTED` means identity,
schema, provenance, ambiguity, integrity, or other validation failed. The CLI
prints the repository reason and never falls back to synthetic evidence. A
transfer failure remains distinct from an evidence or explanation failure.

See the [milestone 5 walkthrough](docs/person-3-milestone-5.md) for command
interpretation and [milestone 6](docs/person-3-milestone-6.md) plus its
[HTTP follow-up](docs/person-3-milestone-6-http.md) for the explanation boundary.

## Final integrated acceptance status

The Stage 7 offline acceptance campaign exercises the complete CLI-to-real-UDP-to-logging-to-
reconciliation-to-explanation path with a deterministic explanation stub. It verifies real
loopback bytes and SHA-256, live and retained sender status, receiver completion-recovery
`PENDING`, finalized endpoint records, exact identity association, reconciliation, metric
invariants, unavailable reasons, and REAL evidence presentation.

The final run on September 23, 2026 reported:

```text
Focused integration/regression set: 223 tests, 0 failures, 0 errors, 0 skipped
Full mvn verify:                    762 tests, 0 failures, 0 errors, 0 skipped
JAR packaging:                     SUCCESS (target/udp-file-transfer.jar)
```

This establishes offline integration and real loopback UDP behavior. It does not establish live
GPT prose quality or cross-host network behavior. No paid API call was made. See
[Stage 7 integrated acceptance](docs/stage-7-acceptance.md) for the genuine-versus-targeted test
matrix and the optional, explicitly authorized one-call live-GPT procedure.

## Deliberate evaluation and historical results

The live evaluation is opt-in because it can spend API credit. Previewing the
four-case synthetic explanation batch makes no API calls:

```powershell
$env:OPENAI_MODEL = 'gpt-5-mini'
.\scripts\milestone-8-eval.ps1 -Batch explanations
```

After reviewing the preview, a deliberately authorized live run can be bounded
to four calls:

```powershell
$env:OPENAI_MODEL = 'gpt-5-mini'
$env:OPENAI_REQUEST_TIMEOUT_MS = '90000'
.\scripts\milestone-8-eval.ps1 -Batch explanations -Live -MaxCalls 4
```

Each run writes a new `report.jsonl` and `review.md` under
`target/milestone-8-eval/<unique-run>/`. The explanation batch uses SYNTHETIC
evidence; it does not prove the real-metrics connection or validate every claim
in model prose. Review both automated checks and the actual text before spending
credit on further `commands`, `explanations`, or `real` batches.

The following are preserved historical branch results, not verification of this
newly merged version:

- On September 21, the bounded `smoke` and `explanations` batches exercised real
  start/status and command safety. The first explanation timed out; later calls
  completed but had semantic/causal findings.
- On September 22, a four-call `explanations-v4` retest did not repeat the
  original targeted errors in that sample, but diagnostic-evidence, conclusion,
  and presentation findings remained.
- The later `explanations-v5` refinement was offline-checked but was not
  live-tested.
- A historical full offline milestone-8 run reported 603 tests across 32
  classes with zero failures, errors, or skips and packaged the JAR. Separate v4
  and v5 focused runs each reported 151 repeated tests across five classes with
  zero failures, errors, or skips; they were not additive suite totals. Full
  packaging was not repeated after v5. The earlier milestone-7 baseline was 566
  tests across 30 classes.

These results describe Raida's pre-merge evaluation artifacts only. Run the
current merged suite and perform a new, explicitly authorized integration
evaluation before treating the combined version as verified. Details and manual
review guidance are in [milestone 8](docs/person-3-milestone-8.md), the
[milestone 7 audit](docs/person-3-milestone-7.md), and the
[repository handoff](docs/repository-and-milestone-handoff.md).

## Further documentation

- [Protocol specification](PROTOCOL.md)
- [Persistent logging and reconciliation](LOGGING.md)
- [Accepted metric definitions](docs/Metrics_Summary_Revised.md)
- [Metrics integration notes](docs/person-2-integration-note.md)
- [CLI and LLM milestone checklist](docs/person-3-task-checklist.md)

The simulated transfer service and synthetic summary provider remain available
for deterministic tests. Their evidence is labelled `SYNTHETIC` and must never
fill gaps in a real run.
