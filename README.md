# udp-file-transfer — Stage 1: Basic UDP Loopback

Status: Stage 1 of 15 (see project handoff doc). This proves raw UDP
send/receive works before any protocol logic is built on top of it.

## Prerequisites

- **JDK 17+** (not just a JRE — you need `javac`). Check with:
  ```
  java -version
  javac -version
  ```
  If `javac` is missing, install a JDK (e.g. Eclipse Temurin 17 or 21) and
  make sure VS Code's Java extension points at it (Command Palette →
  "Java: Configure Java Runtime").
- **Maven** — VS Code's "Extension Pack for Java" bundles Maven support, or
  install standalone and check with `mvn -version`.
- **VS Code extensions**: "Extension Pack for Java" (includes Maven, Test
  Runner, Debugger).

## Project layout so far

```
udp-file-transfer/
├── pom.xml
├── src/main/java/nettransfer/
│   ├── Main.java              <- Stage 1 throwaway sender/receiver
│   └── net/UdpChannel.java    <- thin DatagramSocket wrapper (permanent)
├── src/test/java/nettransfer/net/UdpChannelTest.java
└── experiments/               <- empty for now, used from Stage 14
```

Every other package (`protocol`, `integrity`, `transfer`, `metrics`,
`control`, `llm`, `cli`) exists as an empty folder, ready for later stages.

## How to build

From the `udp-file-transfer/` folder:

```
mvn compile
```

This downloads Gson + JUnit 5 (needs internet the first time; Maven caches
them in `~/.m2` after that) and compiles everything under `src/main/java`.

In VS Code you can instead just open the folder — the Java extension will
auto-detect the Maven project and compile on save.

## How to run a file transfer

Open two terminals in the `udp-file-transfer/` folder. Put a file such as
`payload.bin` in `storage/outgoing/` first. The application creates
`storage/outgoing/` and `storage/incoming/` if needed. CLI arguments are
filenames only; paths and existing incoming filenames are rejected.

Receiver terminal (start this first):

```powershell
mvn exec:java "-Dexec.mainClass=nettransfer.Main" "-Dexec.args=receiver 9000 received.bin"
```

Sender terminal:

```powershell
mvn exec:java "-Dexec.mainClass=nettransfer.Main" "-Dexec.args=sender 9000 payload.bin"
```

After successful SHA-256 verification, the receiver publishes the file as
`storage/incoming/received.bin`. Incomplete transfers remain unpublished.
The receiver uses the validated local filename argument; it does not trust
the filename sent in START metadata as a destination path.

### Timeout settings

The commands above use these defaults. The receiver continues immediately when
a sender starts; it does not wait for the entire initial timeout. Once START is
accepted, the initial waiting deadline no longer applies and the separate
mid-transfer inactivity timer takes over. After verification, the receiver
closes the output file and remains in a bounded completion recovery loop before
returning and publishing the staging file.

| Setting | Default | Purpose |
|---|---:|---|
| `nettransfer.startHandshakeTimeoutMs` | 1000 ms | Wait per START attempt for a matching START_ACK. |
| `nettransfer.startRetryLimit` | 5 retries | Additional START attempts after the first; 6 attempts total by default. |
| `nettransfer.finishHandshakeTimeoutMs` | 1000 ms | Wait per FINISH attempt for an attributable FINISH_ACK. |
| `nettransfer.finishRetryLimit` | 5 retries | Additional FINISH transmissions after the first; 6 attempts total by default. |
| `nettransfer.receiverInitialTimeoutMs` | 300000 ms (5 minutes) | Overall receiver wait for a valid START. |
| `nettransfer.receiverInactivityTimeoutMs` | 60000 ms (60 seconds) | Maximum time after START without a newly accepted and written DATA chunk. |
| Receiver completion grace | 6250 ms by default | Derived as `(FINISH timeout × total attempts) + 250 ms`; supports duplicate FINISH recovery. |
| DATA retransmission timeout | 200 ms | Existing sender deadline for the oldest unacknowledged DATA packet. |
| DATA retry limit | 5 rounds | Existing maximum consecutive Go-Back-N rounds without ACK progress. |

Only a matching START_ACK completes the sender handshake. A duplicate START
for the active transfer is acknowledged again without resetting receiver
progress. A FINISH_ACK must parse, come from the expected receiver IP and port,
match the active transfer UUID, have type FINISH_ACK, and include an explicit
verification result. During the fixed completion grace period, duplicate
FINISH messages from the same sender with the same UUID and hash receive the
cached FINISH_ACK without another file write, hash calculation, or publication.
On receiver timeout, its socket and file close and the temporary file created
by this execution is removed. The receiver handles one transfer and exits
after completion recovery; it does not stay running for another transfer.

### Receiver metrics snapshots

`ReceiverEngine.getMetricsSnapshot()` returns an immutable, provisional or
terminal receiver snapshot. After START acceptance, `packets_received` counts
valid DATA arrivals from the established peer and protocol UUID, including
duplicates and ahead-of-gap packets. `packets_duplicated` uses the existing
receiver sequence classification. `payload_bytes_delivered` increases only
after a unique payload write succeeds.

The receiver records `integrity_verified` only when it actually performs the
whole-file SHA-256 comparison, with evidence source `RECEIVER`. Sender-only
fields, including `transfer_success`, retransmissions, ACK arrivals, DATA
timeouts, and `transfer_time_sec`, remain unavailable. Receiver-local terminal
status and supporting protocol observations are available through
`ReceiverEngine.getReceiverObservations()`.

The existing transfer protocol represents an empty file as one valid
zero-length DATA packet. Its receiver metrics therefore show one DATA arrival
and zero delivered payload bytes.

### RTT and UDP emission measurements

Sender RTT starts immediately after a successful original DATA socket send and
ends when a valid attributable ACK advances the sender window by exactly one
sequence. Retransmitted sequences, duplicate or invalid ACKs, and cumulative
ACKs that advance more than one sequence are excluded. Values use the sender's
monotonic clock, milliseconds, arithmetic mean, and nearest-rank p95. Active
sampling with no eligible observations reports sample count zero with mean and
p95 unavailable.

`UdpChannel` reports the actual encoded application datagram length after each
successful socket send. This includes START, START_ACK, original and repeated
DATA, DATA ACKs, FINISH, FINISH_ACK, and their retries or recovery resends. It
does not include incoming traffic or UDP, IP, and link headers. Failed sends do
not contribute bytes.

`getEndpointEmissionObservations()` exposes each engine's local byte total and
whether that endpoint reached its accounting boundary. These local totals are
kept separate in endpoint records. After both endpoints finalize,
`MetricsExporter` can verify their protocol UUID and supporting evidence, then
calculate combined `udp_payload_bytes_emitted` and protocol overhead.

### Live metrics snapshots

Both engines implement `LiveMetricsProvider`. Retain the engine, or a reference
to that interface, and call `getLiveMetricsSnapshot()` before, during, or after
the blocking transfer method. Each immutable snapshot atomically contains the
current `TransferContext`, `TransferMetrics`, endpoint-local emission evidence,
lifecycle state, supporting endpoint observations, and capture time.

Lifecycle states are `NOT_STARTED`, `AWAITING_START`, `TRANSFERRING`,
`AWAITING_FINISH`, `VERIFYING`, `COMPLETION_RECOVERY`, `SUCCEEDED`, and
`FAILED`. Sender and receiver use only the states that correspond to their
actual work. All nonterminal states are provisional. Terminal snapshots remain
available after the transfer method returns or throws.

Sender snapshots expose live monotonic elapsed time from the first START
attempt. At termination this freezes to the agreed `transfer_time_sec`. They
also expose exact sender-confirmed acknowledged payload bytes and a separately
named ACK-based rate based on actual per-sequence payload lengths. These values
do not populate receiver `payload_bytes_delivered` or the agreed delivered-byte
throughput field. Receiver snapshots expose written bytes and integrity only
after those events actually occur; receiver-local completion remains separate
from sender-confirmed `transfer_success`.

Snapshot construction uses the collector's synchronization and immutable
copies, so another thread may read progress without parsing logs. Unknown
values remain null with unavailable reasons. Endpoint records preserve the
final trustworthy snapshot for later reconciliation.

### Persistent event logs

The CLI enables endpoint-local JSONL evidence under `logs/`. Sender and receiver
processes create separate collision-protected run directories containing
`events-sender.jsonl` or `events-receiver.jsonl` plus an atomically updated
`run-state.json`. Programmatic callers can opt in before transfer with
`engine.enableEventLogging(logsRoot)`.

Events record actual protocol decisions and socket emissions without DATA payload
contents. Attempts and successful emissions are separate records. Handled terminal
outcomes flush and close the writer before the run is marked finalized; interrupted
or failed logging sessions cannot claim complete evidence. Successful and handled
failed terminal runs also contain `endpoint-sender.json` or `endpoint-receiver.json`.

Reconcile two explicit run directories after both endpoints finish:

```powershell
mvn exec:java "-Dexec.mainClass=nettransfer.Main" "-Dexec.args=reconcile logs/standalone/<sender-run-id> logs/standalone/<receiver-run-id>"
```

The command verifies the endpoint records, run states, JSONL streams, roles, UUID,
identities, configuration, counters, and emission totals. It publishes
`logs/standalone/reconciled/<protocol-uuid>/summary.jsonl` and `manifest.json`.
Existing finalized output is never overwritten. See [LOGGING.md](LOGGING.md) for
the schemas, formulas, completeness rules, and verification procedure.

For a short manual or automated initial wait, override the default on the
receiver command:

```powershell
mvn "-Dnettransfer.receiverInitialTimeoutMs=5000" exec:java "-Dexec.mainClass=nettransfer.Main" "-Dexec.args=receiver 9000 timeout-test.txt"
```

## How to run the automated test

```
mvn test
```

The complete suite includes transfer, storage, and timeout tests. Maven
reports the total test count and any failures at the end of the run.

What each test proves, and why it matters later:
- `receiverGetsExactBytesSenderSent` — UDP delivers byte-exact payloads on
  loopback (no corruption at this layer under normal conditions).
- `receiveTimesOutWhenNothingArrives` — `setSoTimeout` works. This is the
  exact mechanism Stage 7 (timeout detection) depends on.
- `twoChannelsCanBindDifferentEphemeralPortsSimultaneously` — confirms
  ephemeral port allocation, so sender and receiver never collide.

## Common errors and fixes

| Symptom | Cause | Fix |
|---|---|---|
| Windows Firewall popup on first run | Java is opening a UDP socket and Windows is asking permission | Click "Allow access" (private networks is enough). This is expected, not a bug. |
| `BindException: Address already in use` on the receiver | Port 9000 already held by another process (maybe a receiver you forgot to stop) | Stop the other process, or change `PORT` in `Main.java` temporarily |
| Receiver hangs forever, sender says "Sent" but nothing prints | Ran sender before receiver was ready, or a firewall silently dropped the packet | Always start the receiver first; check the firewall prompt was accepted |
| `mvn: command not found` | Maven not installed / not on PATH | Install Maven or open the folder in VS Code and let the Java extension manage it |
| `package nettransfer.net does not exist` type errors | Wrong working directory, or `src/main/java` layout broken | Run `mvn compile` from inside `udp-file-transfer/` (the folder containing `pom.xml`) |

## What Stage 1 deliberately does NOT do

No headers, no chunking, no ACKs, no retries, no integrity checks. `Main.java`
here is throwaway scaffolding — it will be replaced by `cli.Cli` and
`control.TransferController` from Stage 12 onward. Don't over-build this file.

## Viva-readiness notes for this stage

**Q: Why does the receiver have to start before the sender?**
UDP is connectionless — there's no three-way handshake like TCP that would
let the OS queue up an incoming SYN. If nothing is bound to port 9000 and
listening, an incoming datagram is simply dropped (or, on some OSes,
triggers an ICMP Port Unreachable back to the sender). `DatagramSocket.send()`
does not know or care whether anyone is listening; it fires and forgets.

**Q: What's the difference between `DatagramSocket` and `DatagramPacket`?**
`DatagramSocket` is the endpoint (like a mailbox) — it's what you bind to a
port and call `send`/`receive` on. `DatagramPacket` is one message — it
carries the byte buffer plus destination (when sending) or source (when
receiving) address/port. One socket sends/receives many packets over its
lifetime.

**Q: Why wrap `DatagramSocket` in `UdpChannel` instead of using it directly
in `Main`?**
Separation of concerns: everything above this layer (sender/receiver logic,
later the impairment shim) should only depend on "send bytes, receive bytes,
maybe with a timeout" — not on raw socket API details. This also gives us a
single seam to insert the `ImpairmentShim` later without touching
`SenderEngine`/`ReceiverEngine`.

**Q: What happens if you send a datagram larger than the receiver's buffer?**
It gets silently truncated to the buffer size, with data loss and no error.
That's part of why we've deliberately capped the application payload at
1024 bytes and used a 2048-byte receive buffer — comfortable headroom for
header + payload, well under the ~65507-byte theoretical UDP datagram limit
and well under typical path MTU (~1500 bytes for Ethernet), which is also
part of *why* we chose a small chunk size (avoids IP fragmentation — see the
protocol design doc for Stage 3).
