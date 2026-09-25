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

## Current evaluation checkpoint

Documentation updated after the September 25, 2026 Section 7 assessment.
**READY FOR PERSON 4'S EXPERIMENTS, with the limitations below.** Sections 3–6
and the Section 7 coverage assessment are complete; the experiment matrix and
final submission/demonstration remain **PENDING PERSON 4 / TEAM**.

The evaluated implementation is the working tree on branch
`feature/metrics-llm-integration`, based on commit
`985b1a816fd772088b4dcc04d8796a759850feef`, including its local repairs. The
commit alone does not contain those repairs. Before this documentation update,
all 48 files in the latest saved working-tree snapshot matched their hashes;
the only additional Git entry was the copied evaluation plan (49 entries:
33 tracked modifications and 16 untracked files). Those counts describe the
pre-cleanup snapshot, not the current status. Preserve/share the actual source
snapshot and ignored evidence when preparing the handoff.

The subsequent approved source-directory change makes the console and standalone
sender both read `storage/outgoing`; the receiver still publishes under
`storage/incoming`. Current examples use that updated implementation. Saved
A–D and Section 6 evidence used the earlier `data/input` console root and remains
unchanged. Use the new source-root build identified in the
[walkthrough](docs/live-demo-walkthrough.md#1-identify-the-repository-and-java--powershell),
not the older acceptance JAR, for the current mappings.

The [source-root validation](target/evaluation/storage-outgoing-20260925-231608-232001/validation.json)
records 315 focused tests across 14 classes passing, with no failures/errors/skips
in their latest results. The updated working JAR is also saved as `application.jar`
in that evidence folder; `application-before.jar` preserves the previous build.
All 1,843 checked prior evaluation/report files retained their hashes. This is
focused verification of the follow-up change, not a repeat of the paid A–D runs.

Fresh offline verification at that commit already passed **762 tests across 56
classes, with zero failures/errors/skips**, and packaged the JAR on September 25.
The saved log is
`target/evaluation/integrated-retry-20260925-120422-068/maven-verify.log`, with
matching suffixed reports under `target/surefire-reports`. These are local ignored
artifacts and must be shared separately. Do not repeat the full suite just to
resume the evaluation, and do not use `mvn clean` on preserved evidence.

The four Section 3 findings were repaired and verified before manual acceptance:

| Repaired capability | Saved passing validation |
| --- | --- |
| Preserve reasons for unavailable RTT statistics through reconciliation | [74 focused tests and packaging](target/evaluation/metrics-null-reasons-20260925-131319-640/validation.json) |
| Controlled receive-side loss/delay, applied settings and observations | [320 final focused tests and real-transfer checks](target/evaluation/controlled-impairment-20260925-134232-676/validation.json) |
| Opt-in exact API evidence and Java-decision capture | [341 focused tests and packaging](target/evaluation/api-evidence-capture-20260925-151307-190/validation.json) |
| Exact DATA framing and accepted chunk/final-payload lengths | [160 focused tests and raw-UDP checks](target/evaluation/data-framing-20260925-161314-533/validation.json) |

These later focused results cover subsequent repairs; the 762-test full suite
above was not rerun after every change. The explanation output cap was separately
[checked and packaged at 32768](target/evaluation/explanation-limit-32768-20260925-204413-531872/validation.json).

| Integrated acceptance evidence | Result |
| --- | --- |
| [A: baseline and natural-language start/analysis selection](target/evaluation/section5-run-a-32768-20260925-204658-930498/validation.json) | Transfer and independent hashes PASS; original [prose review](target/evaluation/section5-run-a-32768-20260925-204658-930498/explanation-review.json) remains **REQUIRES_CORRECTION**. |
| [B: configured 2% random receiver DATA loss](target/evaluation/section5-run-b-loss2-20260925-210849-739240/validation.json) | Transfer/hashes PASS; explanation passed assignment-minimum review with minor presentation caveats. |
| [C: 200 ms DATA/ACK delay per direction](target/evaluation/section5-run-c-delay200-20260925-211634-307439/validation.json) | Transfer/hashes PASS; genuinely active natural-language status PASS; explanation passed with minor presentation caveats. |
| [D: unavailable receiver](target/evaluation/section5-run-d-unavailable-20260925-212915-115426/validation.json) | Expected bounded failure PASS; transfer **FAILED**, integrity **UNCONFIRMED**; explanation passed with minor presentation caveats. |
| [Clarification and retained context](target/evaluation/section6-clarification-prep-20260925-215444-525297/validation.json) | PASS: missing receiver requested, then exactly one bounded successful transfer. |
| [Unsupported operation](target/evaluation/section6-unsupported-prep-20260925-221110-460830/validation.json) | No execution/deletion; model refusal is separate from the saved offline deterministic Java-rejection evidence. |

A's combined sender/receiver emission bytes were misattributed to the sender,
and its prose included unsupported claims. Preserve the original failed review;
use verified measurements for comparisons and reviewed explanations for
presentation. No regeneration is required to begin experiments. Live integrity
**FAILED** remains **NOT EXERCISED**; D does not exercise it.

A–C used one 262,267-byte file and the same window/timeout. Person 4 still needs
small and large files in baseline, at least 2% random loss, and meaningful delay
or jitter, plus a timeout or window comparison. These are six file/scenario
combinations; the plan's eight-run design is a team choice, not an instructor
count. The 2–4-page protocol specification/sequence diagram, 4–6-page report,
at least three shared raw logs with reproduction commands, final demonstration
and disclosure remain pending. See the [current plan](docs/integrated-prototype-evaluation-plan.md)
and [walkthrough](docs/live-demo-walkthrough.md). Historical milestone records
and preparation/session statuses do not override final validations and reviews.

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

Both ordinary launchers target loopback. The direct sender uses `127.0.0.1` and
the supplied port; the console's approved receiver is `127.0.0.1:9000`.
Sender success can precede receiver exit and final destination publication, so
wait for the receiver before hashing the destination. A handled direct-transfer
failure can still return normally from Main; inspect the result and artifacts
instead of treating a zero process exit code as proof of transfer success.

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

The properties above apply to the direct `nettransfer.Main` entry point. The
console adapter uses its separately documented START and DATA settings. These
phase/retry limits are not an independent overall wall-clock transfer deadline.

For a shorter receiver wait during a manual test:

```powershell
mvn "-Dnettransfer.receiverInitialTimeoutMs=5000" exec:java "-Dexec.mainClass=nettransfer.Main" "-Dexec.args=receiver 9000 timeout-test.txt"
```

## Controlled loss and delay

Both launchers support the tested local `RECEIVE_DELIVERY_V1` shim. Supply the
same profile to receiver and sender/console processes as Java `-D` properties
before `-jar` or `-cp`:

| Property | Meaning |
| --- | --- |
| `nettransfer.impairment.enabled=true` | Enable the explicit experimental profile. |
| `nettransfer.impairment.lossPercent=2` | Random drop probability, percent, for eligible DATA at the receiver; no ACK loss. |
| `nettransfer.impairment.delayMs=0` | Fixed receive-delivery delay per direction for receiver DATA and sender ACK; milliseconds, 0–5000. |
| `nettransfer.impairment.seed=42` | Seed the receiver's random decisions; OS timing can still vary. |
| `nettransfer.impairment.scenario=loss2` | Matching nonblank scenario label for both endpoints. |

All four profile values are required when enabled. An explicit baseline uses
loss `0` and delay `0`; a delay-only profile uses loss `0` and, for example,
delay `200` on both endpoints. START/FINISH control exchanges bypass impairment.
Loss occurs after socket emission, so dropped DATA still contributes to emitted
UDP payload bytes. Configured loss is distinct from the observed finite-run drop
count; configured delay is distinct from measured RTT. Do not interpret a
disabled/unobserved impairment field as a measured zero. Full launch examples
and per-run output rules are in the [walkthrough](docs/live-demo-walkthrough.md).

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

See [Metrics Summary](docs/Metrics_Summary_Revised.md) for the accepted field
inventory and its current implementation notes, [LOGGING](LOGGING.md) for the
persisted contract, and
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
receiver-observed integrity remain separate. Unavailable values should retain
their reasons, including unavailable RTT statistics after the verified repair.
See [LOGGING.md](LOGGING.md) for schemas, formulas, completeness
rules, and the full verification procedure.

The command console uses the same `<projectRoot>/logs` root for sender logging
and validated evidence lookup. After a terminal sender result, `explain` may
initially report `EVIDENCE_PENDING` while the receiver finishes its intentional
completion-recovery period. The repository retrieves exact application,
sender-run, and protocol identities; when both endpoints are final it validates
and, if needed, creates the reconciled summary through the existing exporter.

## Run the validated command console

The console uses an explicit source catalogue under the same `storage/outgoing`
directory as the standalone sender. Create `storage/outgoing/report.txt`.
Start the ordinary receiver
with a fresh filename in terminal 1:

```powershell
java -jar target/udp-file-transfer.jar receiver 9000 report-cli.txt
```

In terminal 2, map one or more approved IDs to relative paths under the project
root's `storage/outgoing` directory:

```powershell
java -cp target/udp-file-transfer.jar nettransfer.cli.TransferCliMain . "report=storage/outgoing/report.txt"
```

The first argument is the application root. The remaining arguments are
`file-id=relative-path` mappings; quote mappings containing spaces. Java
resolves real paths and accepts only readable regular files that remain inside
`<projectRoot>/storage/outgoing`, including after symbolic-link resolution. The only
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

Run selection is retained in memory. Restarting the console does not reload its
previous selectable runs, even though their logs remain on disk.

## GPT configuration and explanation flow

Direct start, status, help, and catalogue commands need no API key. An API key
is required only when natural-language interpretation is requested or when an
AVAILABLE evidence set is sent for explanation:

```powershell
$env:OPENAI_API_KEY = '<set privately>'
$env:OPENAI_MODEL = 'gpt-5-mini'                 # optional; this is the default
$env:OPENAI_CONNECT_TIMEOUT_MS = '5000'          # optional
$env:OPENAI_REQUEST_TIMEOUT_MS = '90000'         # evaluation setting; ordinary default is 30000
```

Both API timeouts must be 100-120,000 ms and are independent of UDP transfer
timeouts. The ordinary clients allow up to **two HTTP attempts per logical
request**; environment settings do not expose a one-attempt override. Command
interpretation requests at most **4096** output tokens; explanation requests at
most **32768**. Command interpretation uses
`commands-v2`; explanation uses `explanations-v6`, which retains the v5 grounding
rules and adds REAL evidence-scope/source-reference instructions. An absent or invalid
GPT configuration does not disable direct transfer, status, help, or catalogue
commands.

For example, `Send report to receiver-a with a 64 KiB window` is interpreted as
a proposal and then subjected to the same Java parser and validator. Use `ask`
when a natural-language sentence begins with a reserved direct command, such as
`ask status of my last transfer`.

The explanation flow selects a frozen transfer outcome, then uses
`PersistedEvidenceRepository` and `RealMetricsSummaryProvider` to require exact
application, sender-run, and protocol identities. Only complete, final,
validated REAL evidence can reach the existing explanation client. The ordinary
provider supplies `SENDER_FINAL` for an eligible finalized sender failure, or
`RECONCILED` after both endpoints are validated. `RECEIVER_FINAL` is a scope
recognized by the wider contract, not a route supplied by this provider. The
HTTP client requests strict structured JSON and exposes no execution tools;
Java's explanation flow checks cited field values and units and preserves the
original evidence if model analysis fails. Actual prose still needs review.

GPT receives the projected summary fields, outcome/identity metadata and source
reference identifiers. It does not receive raw JSONL events or an event timeline.
Direct structured explanation makes one logical explanation request when
evidence is available; natural-language explanation first needs an interpretation
request, so retries can produce up to four HTTP attempts in that two-call route.

For any later approved API evaluation, enable the implemented recorder when
launching the console:

```powershell
java "-Dnettransfer.evaluation.record=true" -cp target/udp-file-transfer.jar nettransfer.cli.TransferCliMain . "report=storage/outgoing/report.txt"
```

It creates a fresh `<projectRoot>/target/evaluation/llm-...` directory containing
request/response bodies, per-attempt usage/timing and failures, Java decisions,
and session-completion records. Rejected model drafts remain untrusted evidence;
recording does not make them accepted answers. Missing or omitted bodies are
identified explicitly; check `session-end.json` before claiming a complete
capture. Without the flag, API recording is disabled. Preserve this folder
alongside transfer logs, summaries, hashes and prose reviews. Keep API timing
separate from UDP timing: `OPENAI_REQUEST_TIMEOUT_MS=90000` is an HTTP-attempt
timeout, not a transfer deadline. Saved acceptance runs used separate external
transfer supervision; the commands above do not create an overall watchdog.

New paid calls require explicit approval. The saved latest
[usage ledger](target/evaluation/section6-unsupported-prep-20260925-221110-460830/api-usage-and-cost.json)
estimates project spending at **$0.13104675 of $10**, including the original
user-reported $0.08, leaving **$9.86895325**. The console has no automatic dollar
cutoff; retain the model, prompt, attempt limits and caps above.

Non-available evidence never triggers the explanation API request. A preceding
natural-language interpretation can still have made its own request.
`EVIDENCE_PENDING` means the record still says RECORDING, including normal receiver
recovery; a crashed process can also leave that state. `EVIDENCE_INCOMPLETE` means a
final evidence boundary was not reached; `EVIDENCE_UNAVAILABLE` means no
applicable validated evidence exists; and `EVIDENCE_REJECTED` means identity,
schema, provenance, ambiguity, integrity, or other validation failed. The CLI
prints the repository reason and never falls back to synthetic evidence. A
transfer failure remains distinct from an evidence or explanation failure.

See the [milestone 5 walkthrough](docs/person-3-milestone-5.md) for command
interpretation and [milestone 6](docs/person-3-milestone-6.md) plus its
[HTTP follow-up](docs/person-3-milestone-6-http.md) for the explanation boundary.

## Automated integrated acceptance status

The Stage 7 offline acceptance campaign exercises the complete CLI-to-real-UDP-to-logging-to-
reconciliation-to-explanation path with a deterministic explanation stub. It verifies real
loopback bytes and SHA-256, live and retained sender status, receiver completion-recovery
`PENDING`, finalized endpoint records, exact identity association, reconciliation, metric
invariants, unavailable reasons, and REAL evidence presentation.

The earlier Stage 7 run on September 23, 2026 reported:

```text
Focused integration/regression set: 223 tests, 0 failures, 0 errors, 0 skipped
Full mvn verify:                    762 tests, 0 failures, 0 errors, 0 skipped
JAR packaging:                     SUCCESS (target/udp-file-transfer.jar)
```

The fresh September 25 full verification is recorded in the
[current checkpoint](#current-evaluation-checkpoint). These results cover the
tested offline integration and real loopback behavior; they do not establish live
GPT prose quality, cross-host behavior or the absence of uncovered defects. No paid
API call was made by these test runs. See
[Stage 7 integrated acceptance](docs/stage-7-acceptance.md) for the genuine-versus-targeted test
matrix, subsequent saved live results, and the remaining final-demonstration work.

## Deliberate evaluation and historical results

The runner below is retained for historical reference; it is not the current
integrated evaluation route. Later approved API work uses the captured console
route above. Previewing the four-case synthetic explanation batch makes no API calls:

```powershell
$env:OPENAI_MODEL = 'gpt-5-mini'
.\scripts\milestone-8-eval.ps1 -Batch explanations
```

Historical live invocations wrote a new `report.jsonl` and `review.md` under
`target/milestone-8-eval/<unique-run>/`. The explanation batch uses SYNTHETIC
evidence; it does not prove the real-metrics connection or validate every claim
in model prose. Review both automated checks and the actual text before spending
credit on further `commands`, `explanations`, or `real` batches. The historical
`real` batch covers start/status and installs an unavailable explanation flow;
it is not a real-evidence explanation campaign. The runner limits each logical
call to one HTTP attempt, unlike the ordinary console defaults.

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

These results describe Raida's pre-merge evaluation artifacts in the original
repository. Fresh verification of the integrated version has since passed as
recorded above; do not rerun the unchanged full suite merely because these older
results appear here. Integrated A–D acceptance and v6 prose review are now complete,
with A's original **REQUIRES_CORRECTION** retained as described in the checkpoint.
Details of the historical results and manual
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
