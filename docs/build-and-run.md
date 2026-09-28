# Build and run

Run the commands below from the repository root. Examples use PowerShell;
the single-line Java and Maven commands also work in other shells. The supplied
launchers demonstrate transfers on one machine: the direct sender targets
`127.0.0.1`, and the console's approved receiver is `receiver-a` at
`127.0.0.1:9000`.

## Prerequisites and build

Install JDK 17 or later and Maven, with their commands available in the terminal:

```powershell
java -version
javac -version
mvn -version
mvn verify
```

`mvn verify` compiles the source, runs the ordinary JUnit tests, and packages
`target/udp-file-transfer.jar` with its runtime dependency included. The JAR's
main class is `nettransfer.Main`. The tests do not make OpenAI API calls or start
the optional live evaluation. The first build needs access to Maven
dependencies; with dependencies and plugins already cached, use `mvn -o verify`
for an offline build.

The generated JAR, runtime files, and logs are local build/run artifacts.
Submitted experiment evidence is preserved separately under
[`submission-evidence/`](../submission-evidence/).

## Direct transfer in two terminals

Prepare a source file in `storage/outgoing`. For a small example:

```powershell
New-Item -ItemType Directory -Force -Path storage/outgoing | Out-Null
New-Item -ItemType File -Path storage/outgoing/report.txt -Value 'UDP file transfer demonstration.'
```

If `report.txt` already exists, use that file or choose a different filename.
The direct launcher's filename arguments must be plain filenames, not paths.
Each destination must be new: the receiver refuses to overwrite an existing
file in `storage/incoming`.

Start the receiver in terminal 1:

```powershell
java -jar target/udp-file-transfer.jar receiver 9000 received-direct.txt
```

Start the sender in terminal 2:

```powershell
java -jar target/udp-file-transfer.jar sender 9000 report.txt
```

The sender reads `storage/outgoing/report.txt`. After successful whole-file
SHA-256 verification and completion recovery, the receiver publishes
`storage/incoming/received-direct.txt`. Incomplete transfers remain unpublished.
The application creates its storage directories when needed.

Wait for the receiver to print its saved destination and finish before checking
the hashes; sender success can precede receiver publication by several seconds:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'storage/outgoing/report.txt','storage/incoming/received-direct.txt'
```

The two hashes must match. Inspect the transfer result and destination as well:
a handled direct-transfer failure can return normally from `Main`, so a zero
process exit code alone does not establish success. One receiver process handles
one transfer. Start a new receiver with a fresh destination for each later run.

Equivalent Maven commands, still in separate terminals with the receiver first:

```powershell
mvn exec:java "-Dexec.mainClass=nettransfer.Main" "-Dexec.args=receiver 9000 received-maven.txt"
mvn exec:java "-Dexec.mainClass=nettransfer.Main" "-Dexec.args=sender 9000 report.txt"
```

## Command console

The console uses the real sender and an explicit catalogue of permitted source
files. With `storage/outgoing/report.txt` prepared, start a new receiver in
terminal 1:

```powershell
java -jar target/udp-file-transfer.jar receiver 9000 received-console.txt
```

In terminal 2, launch the console:

```powershell
java -cp target/udp-file-transfer.jar nettransfer.cli.TransferCliMain . "report=storage/outgoing/report.txt"
```

The first argument is the project root. Further arguments map each approved
`file-id` to a relative path under that root's `storage/outgoing` directory.
Quote arguments containing spaces. Java checks that the selected file is readable
and stays within the permitted directory after resolving links. Model output
cannot supply arbitrary paths, hosts, or ports.

At the `transfer>` prompt:

```text
catalog
start_transfer {"file_id":"report","receiver_id":"receiver-a","window_bytes":8192,"timeout_ms":500}
status
```

This example uses eight 1,024-byte packets in flight and a 500 ms DATA timeout.
The start command promptly returns an application transfer ID while the sender
runs on a worker. Only one transfer can be active at a time. Poll `status` to
see `RUNNING`, `COMPLETED`, or `FAILED`; small local files may finish before
the first status query.

| Command | Use |
| --- | --- |
| `help`, `catalog` | List commands and permitted files/receivers. |
| `start_transfer <JSON>` | Validate and start a transfer; supply all four fields shown above. |
| `status`, `status this transfer` | Select the active transfer, or the latest terminal transfer. |
| `status current` | Select an active transfer; request clarification if there is none. |
| `status last`, `status last transfer` | Select the latest terminal transfer. |
| `status <UUID>` | Select an exact application transfer ID retained by this console. |
| `explain {"run_id":null,"question":"What happened?"}` | Select the current/latest outcome and request an explanation from validated persisted evidence. |
| `ask <sentence>` or an ordinary sentence | Request a structured model proposal, then validate it in Java. |
| `exit` | Exit when idle; refused while a transfer is active. |

All fields in structured commands are required. `null` window and timeout values
select the Java defaults. Missing/extra fields, duplicate keys, incorrect types,
out-of-range values, unknown IDs, ambiguous references, and multiple proposed
tool calls are rejected. Run selection is retained only in memory: restarting
the console does not reload previous selectable runs, although their logs remain.

Status shows live sender observations, including acknowledged bytes, elapsed
time, ACK-based rate, retransmissions, timeouts, RTT observations, and emitted UDP
payload bytes. ACK progress does not establish receiver integrity, and the
sender ACK-based rate is separate from reconciled throughput. Unavailable values
retain their reasons. See the [implemented metric definitions](../LOGGING.md).

## Natural-language requests and explanations

Set the following environment variables in the console terminal **before
launching the console**. Substitute your own API key and keep it out of submitted
files and recordings:

```powershell
$env:OPENAI_API_KEY = '<your API key>'
$env:OPENAI_MODEL = 'gpt-5-mini'
$env:OPENAI_CONNECT_TIMEOUT_MS = '5000'
$env:OPENAI_REQUEST_TIMEOUT_MS = '90000'
```

Only the key is required. The project's defaults are `gpt-5-mini`, a 5,000 ms
connection timeout, and a 30,000 ms request timeout; 90,000 ms was used for the
evaluation. Both API timeout settings accept 100-120,000 ms. The ordinary client
allows up to two HTTP attempts per logical request. These API settings are
independent of UDP transfer timeouts.

Direct transfer, status, help, and catalogue commands work without an API key.
Natural-language interpretation makes an API request. For example, with a new
receiver waiting, use this as an alternative to the direct start command:

```text
Send report to receiver-a with a 64 KiB window and a 500 ms timeout.
```

Use `ask` when a sentence begins with a reserved command:

```text
ask status of my last transfer
```

The model proposes a structured operation; Java resolves the approved resources,
checks the parameters, and decides whether to execute it. Clarification can retain
up to two exchanges. Direct commands, failures, rejections, and execution clear
that clarification context.

After transfer completion, wait for the receiver to finish, then request an
explanation in the same console session:

```text
explain {"run_id":null,"question":"Summarize the outcome, throughput, retransmissions, RTT and integrity result."}
```

The explanation flow validates exact application, sender-run, and protocol
identities against the project's trusted log root. It supplies reconciled
sender/receiver evidence after both endpoints finalize, or eligible finalized
sender failure evidence. The model receives projected summary fields and source
references; it does not receive raw packet-event logs. Java checks cited field
values and units. Model prose should still be reviewed against the evidence.

| Evidence response | Meaning |
| --- | --- |
| `EVIDENCE_PENDING` | A record is still recording, including during normal receiver completion recovery; retry after the receiver finishes. An interrupted process can also leave this state. |
| `EVIDENCE_INCOMPLETE` | A final evidence boundary was not reached. |
| `EVIDENCE_UNAVAILABLE` | No applicable validated evidence exists. |
| `EVIDENCE_REJECTED` | Evidence failed identity, schema, provenance, integrity, or other validation. |

Unavailable evidence prevents the explanation API call. A preceding
natural-language interpretation may already have made its own call. Transfer
failure, evidence failure, and explanation failure are reported separately.

## Controlled loss and delay

The built-in `RECEIVE_DELIVERY_V1` simulator applies random loss to eligible DATA
at the receiver and fixed delivery delay to receiver DATA and sender ACKs.
START/FINISH exchanges bypass impairment. Set the same profile on the receiver
and sender/console processes, with JVM properties before `-jar` or `-cp`.

For a 2% loss example, define this array separately in both PowerShell terminals:

```powershell
$impairmentProfile = @(
    '-Dnettransfer.impairment.enabled=true'
    '-Dnettransfer.impairment.lossPercent=2'
    '-Dnettransfer.impairment.delayMs=0'
    '-Dnettransfer.impairment.seed=42'
    '-Dnettransfer.impairment.scenario=loss-2pct'
)
```

Terminal 1:

```powershell
java @impairmentProfile -jar target/udp-file-transfer.jar receiver 9000 received-loss.txt
```

Terminal 2:

```powershell
java @impairmentProfile -cp target/udp-file-transfer.jar nettransfer.cli.TransferCliMain . "report=storage/outgoing/report.txt"
```

Then use the structured start command with `window_bytes:8192` and
`timeout_ms:500` shown above. All four profile values (`lossPercent`, `delayMs`,
`seed`, and `scenario`) are required when impairment is enabled. For an explicit
baseline, set loss and delay to zero. For the delay experiment, set loss to zero,
delay to `200`, and use a matching scenario label such as `delay-200ms` on both
endpoints. Delay accepts 0-5,000 ms per direction; loss accepts 0-100 percent.

Configured loss differs from the finite run's observed drop percentage, and
configured delay differs from measured RTT. The seed controls random decisions,
but operating-system timing can still vary. Dropped DATA was already emitted by
the sender and still contributes to emitted UDP payload bytes.

The [recorded reproduction commands](../submission-evidence/reproduction-commands.md)
provide Bash commands for the representative baseline, 2% loss, and 200 ms delay
experiments. Set that document's shell variables in each terminal, prepare its
source file under `storage/outgoing`, and use a fresh receiver destination for
each run. Its Homebrew JDK path is specific to the original recording machine.
Preserved raw logs are under
[`submission-evidence/raw-logs/`](../submission-evidence/raw-logs/).

## Reproduce all seven experiments

The seven cases below match the file sizes and transport settings in the
[results CSV](../person4-experiment-results.csv). They use direct console
commands and need no API key. Generate the two inputs once from the project root:

```powershell
New-Item -ItemType Directory -Force -Path storage/outgoing | Out-Null
$experimentInputs = @(
    [pscustomobject]@{ Name = 'person4-small.bin'; Bytes = 262267 }
    [pscustomobject]@{ Name = 'person4-large.bin'; Bytes = 2097275 }
)
foreach ($inputSpec in $experimentInputs) {
    $inputPath = Join-Path (Get-Location).Path ('storage/outgoing/' + $inputSpec.Name)
    if (Test-Path -LiteralPath $inputPath) {
        if ((Get-Item -LiteralPath $inputPath).Length -ne $inputSpec.Bytes) {
            throw "Existing input has the wrong size: $inputPath"
        }
        Write-Host "Keeping existing input: $inputPath"
        continue
    }
    $inputBytes = [byte[]]::new($inputSpec.Bytes)
    [System.Random]::new(42).NextBytes($inputBytes)
    $inputStream = [System.IO.File]::Open($inputPath, [System.IO.FileMode]::CreateNew)
    try { $inputStream.Write($inputBytes, 0, $inputBytes.Length) }
    finally { $inputStream.Dispose() }
}
Get-FileHash -Algorithm SHA256 -LiteralPath 'storage/outgoing/person4-small.bin','storage/outgoing/person4-large.bin'
```

Existing inputs of the expected size are retained. Newly generated inputs use
seed 42 and both sizes include a partial final chunk. Record their actual hashes
for comparison with the received files.

| Case | File | Bytes | Scenario | Loss | Delay per direction | Window bytes (packets) | DATA timeout |
| --- | --- | ---: | --- | ---: | ---: | ---: | ---: |
| 1 | Small | 262,267 | `baseline` | 0% | 0 ms | 8,192 (8) | 500 ms |
| 2 | Small | 262,267 | `loss-2` | 2% | 0 ms | 8,192 (8) | 500 ms |
| 3 | Small | 262,267 | `delay-200` | 0% | 200 ms | 8,192 (8) | 500 ms |
| 4 | Large | 2,097,275 | `baseline` | 0% | 0 ms | 8,192 (8) | 500 ms |
| 5 | Large | 2,097,275 | `loss-2` | 2% | 0 ms | 8,192 (8) | 500 ms |
| 6 | Large | 2,097,275 | `delay-200` | 0% | 200 ms | 8,192 (8) | 500 ms |
| 7 | Large | 2,097,275 | `delay-200-window16` | 0% | 200 ms | 16,384 (16) | 500 ms |

For each case, run the following setup in **both terminals**, setting `$caseId`
to the same number in each. Repeat with values 1 through 7 for the full matrix:

```powershell
$caseId = 1
$experimentCases = @(
    @('small', 'baseline', 0, 0, 8192),
    @('small', 'loss-2', 2, 0, 8192),
    @('small', 'delay-200', 0, 200, 8192),
    @('large', 'baseline', 0, 0, 8192),
    @('large', 'loss-2', 2, 0, 8192),
    @('large', 'delay-200', 0, 200, 8192),
    @('large', 'delay-200-window16', 0, 200, 16384)
)
if ($caseId -lt 1 -or $caseId -gt 7) { throw 'Choose case 1 through 7.' }
$inputKind, $scenario, $lossPercent, $delayMs, $windowBytes = $experimentCases[$caseId - 1]
$sourceRelative = "storage/outgoing/person4-$inputKind.bin"
$experimentProfile = @(
    '-Dnettransfer.impairment.enabled=true'
    "-Dnettransfer.impairment.lossPercent=$lossPercent"
    "-Dnettransfer.impairment.delayMs=$delayMs"
    '-Dnettransfer.impairment.seed=42'
    "-Dnettransfer.impairment.scenario=$scenario"
)
```

Start a fresh receiver in terminal 1. The timestamp gives each run a new
destination filename:

```powershell
$runStamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$destination = "received-$inputKind-$scenario-$runStamp.bin"
java @experimentProfile "-Dnettransfer.receiverInitialTimeoutMs=240000" "-Dnettransfer.receiverInactivityTimeoutMs=15000" -jar target/udp-file-transfer.jar receiver 9000 $destination
```

Start a fresh console in terminal 2. The first command prints the exact
structured start command for the selected case; paste it at `transfer>`:

```powershell
'start_transfer {"file_id":"sample","receiver_id":"receiver-a","window_bytes":' + $windowBytes + ',"timeout_ms":500}'
java @experimentProfile -cp target/udp-file-transfer.jar nettransfer.cli.TransferCliMain . "sample=$sourceRelative"
```

Use `status` while the transfer runs and `status last` after it completes. Wait
for the receiver to exit; in terminal 1 compare the hashes:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath $sourceRelative,('storage/incoming/' + $destination)
```

Reconcile that case's two printed run directories using the next section's
command. Retain both complete endpoint directories, the resulting summary and
manifest, the chosen case/settings, and the hash output. Exit the console before
starting the next case. Use fresh receiver and console processes for every run;
their run IDs separate the generated evidence. Timings and observed drops may
differ from the saved results even with the same configuration and seed.

## Runtime logging and reconciliation

New runs automatically create `logs/` even though the submission does not include
that runtime directory. Both ordinary launchers use the project root's `logs/`
when started as above. The direct launcher can override it with
`-Dnettransfer.logsRoot=<directory>`; the console uses `<projectRoot>/logs` for
both sender logging and evidence lookup. Keep the receiver on that same root
when using console explanations.

Each endpoint prints its event-log location and creates a run directory under
`logs/standalone/`, containing:

- `events-sender.jsonl` or `events-receiver.jsonl`: protocol decisions and
  successful socket emissions, without DATA contents.
- `run-state.json`: evidence-recording/finalization state.
- `endpoint-sender.json` or `endpoint-receiver.json`: endpoint metrics after a
  handled terminal run.

After both endpoints finish, replace the placeholders below with their actual
run directory names to reconcile them:

```powershell
java -jar target/udp-file-transfer.jar reconcile "logs/standalone/<sender-run-id>" "logs/standalone/<receiver-run-id>"
```

Reconciliation validates endpoint roles, identities, configurations, finalized
records, event streams, counters, and emission totals. It writes `summary.jsonl`
and `manifest.json` under `logs/standalone/reconciled/<protocol-uuid>/` and refuses
to overwrite existing finalized output. Console explanations can create this
reconciliation automatically once both endpoints are final. Preserve the complete
endpoint directories to rerun validation; the representative submitted raw-log
extracts contain event files and summaries rather than complete runtime folders.

See [LOGGING.md](../LOGGING.md) for the schemas, formulas, and validation rules.

## Defaults and timeout settings

Both senders use 1,024-byte chunks and five consecutive DATA recovery rounds
without ACK progress. The direct sender fixes the window at one packet and its
DATA timeout at 200 ms. Use the console to vary those parameters:

| Console setting | Default | Accepted values |
| --- | ---: | --- |
| `window_bytes` | 1,024 | 1,024-1,048,576 bytes, rounded down to whole 1,024-byte packet slots. |
| `timeout_ms` | 200 | 50-5,000 ms for DATA retransmission. |

The direct launcher's additional JVM properties are:

| Property | Default | Purpose |
| --- | ---: | --- |
| `nettransfer.startHandshakeTimeoutMs` | 1,000 ms | Wait per START attempt. |
| `nettransfer.startRetryLimit` | 5 | Additional START attempts after the first. |
| `nettransfer.finishHandshakeTimeoutMs` | 1,000 ms | Wait per FINISH attempt. |
| `nettransfer.finishRetryLimit` | 5 | Additional FINISH attempts after the first. |
| `nettransfer.receiverInitialTimeoutMs` | 300,000 ms | Receiver wait for a valid START. |
| `nettransfer.receiverInactivityTimeoutMs` | 60,000 ms | Receiver wait without a newly accepted and written DATA chunk. |

The receiver completion grace is `(FINISH timeout * total FINISH attempts) +
250 ms`, or 6,250 ms by default. During this period it can answer duplicate
FINISH messages with the cached result. The console sender has a separate
2,000 ms timeout per START attempt and five retries, so an unavailable receiver
usually takes about 12 seconds to exhaust START attempts. Direct-entry-point
START properties do not reconfigure that console adapter.

For a shorter receiver initial wait:

```powershell
java "-Dnettransfer.receiverInitialTimeoutMs=5000" -jar target/udp-file-transfer.jar receiver 9000 timeout-test.txt
```

These settings bound individual phases or periods without progress. They do not
establish an independent overall wall-clock transfer deadline. API request
timeouts likewise apply only to HTTP attempts.

## Optional API evidence capture

To record a console demonstration's model requests/responses and Java decisions,
add the following flag when launching the console:

```powershell
java "-Dnettransfer.evaluation.record=true" -cp target/udp-file-transfer.jar nettransfer.cli.TransferCliMain . "report=storage/outgoing/report.txt"
```

The recorder creates a unique `target/evaluation/llm-...` directory with
request/response bodies, attempt timing and usage, failures, Java decisions, and
session-completion records. Recording is disabled by default and enabling it
does not itself invoke the model. Check `session-end.json` before treating a
capture as complete, and preserve the folder together with the transfer logs and
hash checks. Rejected model responses remain untrusted recorded evidence.

Return to the [submission README](../README.md) for the deliverable index.
