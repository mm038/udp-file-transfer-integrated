# Person 4: complete demo and experiment walkthrough

Updated 25 September 2026 for the evaluated working tree on `feature/metrics-llm-integration`, based on `985b1a816fd772088b4dcc04d8796a759850feef` **plus the local repairs**. The [evaluation plan](integrated-prototype-evaluation-plan.md) was copied from the original unmerged repository so Person 4 can see the evaluation work performed that day. Its updated checkpoint and the [final acceptance evidence](stage-7-acceptance.md#final-live-evidence) distinguish completed checks from remaining work.

The system is ready for Person 4's experiments with documented limitations. A's original explanation remains `REQUIRES_CORRECTION`; its measurements are valid. B–D passed the assignment-minimum explanation review with minor presentation caveats. The experiment matrix, report, complete protocol specification and final demonstration are still pending.

The later approved source-directory change makes both sender routes read `storage/outgoing`. This guide uses that updated implementation. The saved A–D and Section 6 runs used the earlier `data/input` console root; their files, commands, reviews and build identity remain historical evidence and are unchanged. Use the new source-root build identified below for this guide.

This guide supplies commands; editing it does not run them. New paid calls need explicit approval. For measurements alone, use the direct console start/status commands and skip GPT. No repeat of the existing full test suite or A–D acceptance campaign is needed just to prepare the handoff.

## How to use this guide

Use **two PowerShell terminals** on the same Windows computer:

- **Terminal 1:** prepare one run, launch its receiver, then inspect files and metrics after the receiver exits.
- **Terminal 2:** set up the optional API key, launch the console, and keep it open through any explanation.
- Commands labelled **inside the transfer console** are entered at `transfer>`, not at PowerShell's `PS>` prompt.

The **console starts the real Java sender** when Java accepts a start command. Do not also launch the standalone sender for that run. The standalone sender command is included in section 12 as an alternative.

Follow sections 1–10 for one complete run. Section 11 explains which settings to change for Person 4's experiments. Run one transfer per run folder, with fresh receiver and console processes each time.

## 1. Identify the repository and Java — PowerShell

In Terminal 1, enter the full path to the **integrated** repository when prompted:

~~~powershell
$repo = (Resolve-Path -LiteralPath (Read-Host 'Full path to udp-file-transfer-integrated')).Path
Set-Location -LiteralPath $repo
$ErrorActionPreference = 'Stop'
java -version
javac -version
git branch --show-current
git rev-parse HEAD
git status --short
$jar = Join-Path $repo 'target/udp-file-transfer.jar'
$builtHere = $false
~~~

Use JDK 17 or later. The evaluated setup used Java 17.0.8.1 and Maven 3.9.16. If Java is unavailable or points to the wrong installation, run this in **each terminal**, entering that computer's JDK directory, then repeat the version checks:

~~~powershell
$env:JAVA_HOME = (Resolve-Path -LiteralPath (Read-Host 'JDK folder containing bin/java.exe and bin/javac.exe')).Path
$env:PATH = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:PATH
java -version
javac -version
~~~

Person 4 needs the evaluated local source changes and/or matching packaged JAR; cloning the base commit alone omits the repairs. Existing `target/` evidence is ignored by Git and must be shared separately. Do not reset the working tree, run `mvn clean`, or overwrite saved evaluation folders.

**Use the existing verified JAR when it is supplied.** Only if preparing a fresh checkout with the evaluated source and no JAR, build it once from the repository root:

~~~powershell
if (Test-Path -LiteralPath $jar) { throw 'A JAR already exists; preserve and identify it before rebuilding.' }
mvn -version
mvn '-DskipTests' package
if ($LASTEXITCODE -ne 0) { throw 'Packaging failed; stop and inspect the build output.' }
$builtHere = $true
~~~

This optional command packages without rerunning the suite; it does not establish a new test pass. Maven must already be available. Add `-o` only if dependencies are cached. Do not generate a Maven wrapper as part of the demo.

Before continuing:

~~~powershell
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw 'The packaged JAR is missing.' }
$jarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $jar).Hash
$evaluatedJarHash = 'E120B6C389CA1CDFB9900AD4E3031930BC0FD117929A447C85577307B3291587'
if (-not $builtHere -and $jarHash -ne $evaluatedJarHash) {
    throw 'Supplied JAR does not match the saved evaluated artifact. Identify it before continuing.'
}
Write-Output "JAR SHA256: $jarHash"
~~~

That expected checksum identifies the new source-root build for this walkthrough, with [315 focused tests passing across 14 classes](../target/evaluation/storage-outgoing-20260925-231608-232001/validation.json). The updated working JAR is also preserved as `target/evaluation/storage-outgoing-20260925-231608-232001/application.jar`; that folder retains the previous JAR as `application-before.jar`. The older [32768-cap build](../target/evaluation/explanation-limit-32768-20260925-204413-531872/validation.json) remains the artifact used for the saved acceptance; it expects `data/input` and does not implement this guide's shared `storage/outgoing` root. A deliberate rebuild from the updated source can have a different JAR hash; section 3 records its new identity. Skipping the supplied-artifact comparison for that rebuild does not prove its source matches the handoff or claim that new tests passed.

## 2. Prepare small and large source files once — Terminal 1

The assignment does not prescribe file sizes. These practical examples are **262,267 bytes** (256 KiB + 123) and **2,097,275 bytes** (2 MiB + 123). Both exercise a partial final chunk. They are new example inputs, not copies of the original A–D evidence.

Create them once under the repository's `storage/outgoing`. This block refuses to overwrite an existing file:

~~~powershell
Set-Location -LiteralPath $repo
$masterOutgoing = Join-Path $repo 'storage/outgoing'
[System.IO.Directory]::CreateDirectory($masterOutgoing) | Out-Null
$inputSpecs = @(
    [pscustomobject]@{ name = 'person4-small.bin'; bytes = 262267 },
    [pscustomobject]@{ name = 'person4-large.bin'; bytes = 2097275 }
)
foreach ($spec in $inputSpecs) {
    $path = Join-Path $masterOutgoing $spec.name
    if (Test-Path -LiteralPath $path) { throw "Already exists; inspect/reuse it instead of regenerating: $path" }
    $bytes = New-Object byte[] ([int]$spec.bytes)
    $generator = [System.Random]::new(42)
    $generator.NextBytes($bytes)
    $stream = [System.IO.File]::Open($path, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write)
    try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
    Get-FileHash -Algorithm SHA256 -LiteralPath $path
}
~~~

Keep these exact files unchanged across scenarios. If the team already selected real files, use those instead: copy each to a new name under `storage/outgoing`, update `$masterSource` in section 3, and preserve its size/hash. Do not regenerate a different file between comparisons.

## 3. Prepare one fresh run — Terminal 1

For the first baseline, use the settings below. For later rows, change only the values specified in section 11, then repeat this section to create a **new** folder.

~~~powershell
Set-Location -LiteralPath $repo
$fileChoice = 'small'
$scenario = 'baseline'
$lossPercent = 0
$delayMs = 0
$seed = 42
$windowBytes = 8192
$dataTimeoutMs = 500
$senderRoute = 'console'
$manualTransferLimitSeconds = 300
$masterSource = Join-Path $repo ("storage/outgoing/person4-{0}.bin" -f $fileChoice)

if (-not (Test-Path -LiteralPath $masterSource -PathType Leaf)) { throw 'Selected source file is missing.' }
$runName = 'person4-{0}-{1}-{2}-{3}' -f $fileChoice, $scenario, (Get-Date -Format 'yyyyMMdd-HHmmss'), ([guid]::NewGuid().ToString('N'))
$runRoot = Join-Path $repo ("target/evaluation/" + $runName)
if (Test-Path -LiteralPath $runRoot) { throw 'Run directory collision; choose a fresh run.' }
foreach ($folder in @('storage/outgoing', 'storage/incoming', 'logs')) {
    [System.IO.Directory]::CreateDirectory((Join-Path $runRoot $folder)) | Out-Null
}
$sourceCopy = Join-Path $runRoot 'storage/outgoing/sample.bin'
Copy-Item -LiteralPath $masterSource -Destination $sourceCopy
$sourceHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $masterSource).Hash
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $sourceCopy).Hash -ne $sourceHash) { throw 'Outgoing copy differs.' }

$startCommand = 'start_transfer ' + ([ordered]@{
    file_id = 'sample'
    receiver_id = 'receiver-a'
    window_bytes = $windowBytes
    timeout_ms = $dataTimeoutMs
} | ConvertTo-Json -Compress)
$naturalStart = 'ask Send sample to receiver-a using a {0}-byte window and a {1}-millisecond timeout.' -f $windowBytes, $dataTimeoutMs

$config = [ordered]@{
    repository = $repo
    application_root = $runRoot
    jar = $jar
    jar_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $jar).Hash
    sender_route = $senderRoute
    source_choice = $fileChoice
    source_bytes = (Get-Item -LiteralPath $sourceCopy).Length
    source_sha256 = $sourceHash
    receiver_name = 'received.bin'
    port = 9000
    scenario = $scenario
    loss_percent = $lossPercent
    delay_ms_per_direction = $delayMs
    seed = $seed
    window_bytes = $windowBytes
    data_timeout_ms = $dataTimeoutMs
    manual_transfer_limit_seconds = $manualTransferLimitSeconds
    receiver_initial_timeout_ms = 240000
    receiver_inactivity_timeout_ms = 15000
    start_command = $startCommand
    natural_language_start = $naturalStart
}
$configPath = Join-Path $runRoot 'run-config.json'
$config | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $configPath -Encoding UTF8
Write-Output "RUN FOLDER: $runRoot"
Write-Output "Copy this configuration path into Terminal 2: $configPath"
Write-Output $startCommand
Write-Output $naturalStart
~~~

Both sender routes read this run's single `storage/outgoing/sample.bin`. The console maps an approved ID to that relative path; Java validates the ID, path containment and readability. The independent hash check above confirms that this run uses the unchanged master file.

Each run acts as its own application root. Its layout will be:

~~~text
<integrated-repository>/
  storage/outgoing/person4-small.bin        retained master inputs
  storage/outgoing/person4-large.bin
  target/evaluation/person4-<unique-run>/
    run-config.json
    storage/outgoing/sample.bin             single source used by either sender route
    storage/incoming/received.bin           published received file, after verification
    logs/standalone/<sender-run-id>/         sender events, endpoint record, run state
    logs/standalone/<receiver-run-id>/       receiver events, endpoint record, run state
    logs/standalone/reconciled/<protocol-id>/
      summary.jsonl
      manifest.json
    target/evaluation/llm-<unique-session>/  API requests/responses and Java decisions
    receiver-transcript.txt
    console-transcript.txt
~~~

Thus **outgoing and incoming files are stored under `storage`**, inside the selected run folder. No second console input copy is needed. An incomplete incoming transfer is not published as `received.bin`. Normal receiver cleanup removes its temporary file; retain the failure logs even if there is no received file.

Metrics are enabled automatically by these Java entry points. No metrics-enable switch is required. The receiver's explicit log-root option below points to the same `logs` directory that the console derives from its application root.

## 4. Configure Terminal 2 and the optional API key

Open Terminal 2, verify Java as in section 1, and paste the **exact configuration path printed by Terminal 1** when prompted:

~~~powershell
$ErrorActionPreference = 'Stop'
$configPath = (Resolve-Path -LiteralPath (Read-Host 'Paste this run configuration path')).Path
$c = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
$repo = $c.repository
$runRoot = $c.application_root
$jar = $c.jar
Set-Location -LiteralPath $runRoot
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $jar).Hash -ne $c.jar_sha256) { throw 'The JAR changed after preparation.' }
Write-Output "Use ONE start command after the console opens:"
Write-Output $c.start_command
Write-Output $c.natural_language_start
~~~

For **local experiments without GPT**, skip the key block. Direct `start_transfer`, `status`, metrics export and hash checks need no API key.

For approved natural-language or explanation work, set the key **before starting a transcript or recording the screen**. This masked prompt does not put the key in a command literal or print it:

~~~powershell
$apiKeySecure = Read-Host 'OpenAI API key (input hidden)' -AsSecureString
try {
    $env:OPENAI_API_KEY = [System.Net.NetworkCredential]::new('', $apiKeySecure).Password
} finally {
    $apiKeySecure.Dispose()
    Remove-Variable apiKeySecure
}
if ([string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) { throw 'No API key supplied.' }
~~~

Use these model/time settings for the console:

~~~powershell
$env:OPENAI_MODEL = 'gpt-5-mini'
$env:OPENAI_CONNECT_TIMEOUT_MS = '5000'
$env:OPENAI_REQUEST_TIMEOUT_MS = '90000'
~~~

The key is set only in this terminal's process environment and its Java child; do not put it in `run-config.json`, source files or transcripts. The implementation already fixes `commands-v2` interpretation to 4096 output tokens, `explanations-v6` to 32768, and the console to at most two HTTP attempts per operation. There are no extra CLI flags needed for those caps.

**90000 ms is the HTTP-attempt timeout, not the transfer deadline.** The sample run configuration records a separate **manual** 300-second transfer limit; it does not install a watchdog. Start that supervision timer when Java prints `Start accepted`. A large delayed run can take over a minute, so the earlier 60-second small-file limit is inappropriate here.

## 5. Launch the receiver, then the console

### Terminal 1 — receiver

Use the configuration you just created. This explicit profile is enabled even for a zero-loss/zero-delay baseline, allowing observed zero simulator drops:

~~~powershell
$c = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
$runRoot = $c.application_root
$jar = $c.jar
Set-Location -LiteralPath $runRoot
$impairmentArgs = @(
    '-Dnettransfer.impairment.enabled=true'
    ("-Dnettransfer.impairment.lossPercent={0}" -f $c.loss_percent)
    ("-Dnettransfer.impairment.delayMs={0}" -f $c.delay_ms_per_direction)
    ("-Dnettransfer.impairment.seed={0}" -f $c.seed)
    ("-Dnettransfer.impairment.scenario={0}" -f $c.scenario)
)
$receiverArgs = @(
    ("-Dnettransfer.logsRoot={0}" -f (Join-Path $runRoot 'logs'))
    ("-Dnettransfer.receiverInitialTimeoutMs={0}" -f $c.receiver_initial_timeout_ms)
    ("-Dnettransfer.receiverInactivityTimeoutMs={0}" -f $c.receiver_inactivity_timeout_ms)
)
Start-Transcript -LiteralPath (Join-Path $runRoot 'receiver-transcript.txt') -NoClobber
try {
    java @impairmentArgs @receiverArgs -jar $jar receiver $c.port $c.receiver_name
} finally {
    Stop-Transcript
}
~~~

Wait for `Waiting for a transfer...` before launching the sender. The receiver handles **one transfer and exits**. It waits up to 240 seconds for START in this example, then allows 15 seconds without newly written DATA progress. These are phase/inactivity limits, not an overall transfer timer. Default completion recovery adds approximately 6.25 seconds after protocol completion before the published file is available.

`receiver-a` is currently **127.0.0.1:9000**. Both terminals run on the same computer. An arbitrary host/IP typed into natural language cannot change that Java allowlist. If port 9000 is occupied, finish the prior receiver or inspect the owning process; do not start competing runs.

### Terminal 2 — console and real sender

~~~powershell
Set-Location -LiteralPath $runRoot
$impairmentArgs = @(
    '-Dnettransfer.impairment.enabled=true'
    ("-Dnettransfer.impairment.lossPercent={0}" -f $c.loss_percent)
    ("-Dnettransfer.impairment.delayMs={0}" -f $c.delay_ms_per_direction)
    ("-Dnettransfer.impairment.seed={0}" -f $c.seed)
    ("-Dnettransfer.impairment.scenario={0}" -f $c.scenario)
)
Start-Transcript -LiteralPath (Join-Path $runRoot 'console-transcript.txt') -NoClobber
try {
    java @impairmentArgs '-Dnettransfer.evaluation.record=true' -cp $jar nettransfer.cli.TransferCliMain $runRoot 'sample=storage/outgoing/sample.bin'
} finally {
    Stop-Transcript
}
~~~

You should see a simulator description, the fresh `Evaluation records:` path and a `transfer>` prompt. The recorder is enabled even for a direct-only session; zero API requests is a valid result.

The console uses `<applicationRoot>/logs`; `nettransfer.logsRoot` does **not** override its root. Keeping both processes in this run folder, with the explicit console application root, gives compatible log locations.

Transcripts are supplementary; native-program capture varies by PowerShell host. The machine-readable UDP logs and opt-in API recorder are the authoritative saved evidence. Do not rely on a transcript alone.

## 6. Start exactly one transfer — inside the transfer console

Inspect the approved resources:

~~~text
help
catalog
~~~

Choose **one** start route. Do not enter both starts for the same run.

**Free direct start — preferred for the experiment matrix.** Paste the `start_command` printed during preparation. For the baseline settings above it is:

~~~text
start_transfer {"file_id":"sample","receiver_id":"receiver-a","window_bytes":8192,"timeout_ms":500}
~~~

**Natural-language start — for the approved LLM demonstration.** Paste the printed `natural_language_start` or, for the same baseline settings:

~~~text
ask Send sample to receiver-a using an 8192-byte window and a 500-millisecond timeout.
~~~

An ordinary sentence without `ask` also works; the prefix explicitly chooses interpretation. GPT proposes a structured command, then Java validates it. Wait for **`Start accepted [REAL]`**, note the application `run_id`/`transfer_id`, and check the printed window and timeout. Those accepted settings, not just the words requested, determine the experiment.

If the model asks for the missing receiver, answer `receiver-a` in the same console. A clarification is not a start. During that clarification, do not insert direct commands such as `status` or `catalog`: direct commands clear the pending clarification context. Once Java accepts a start, do not submit another start to recover from a slow API response.

## 7. Observe progress — inside the transfer console

Free status commands:

~~~text
status
status current
~~~

`status current` requires an active transfer. Once it has finished, use:

~~~text
status last
~~~

For an approved natural-language status demonstration, while it is still running:

~~~text
ask How much has been acknowledged so far, and what is the current transfer status and measured rate?
~~~

Natural-language status makes an interpretation API call. A small file may finish before the answer returns; do not label a terminal answer as an active-status check. Saved run C already demonstrated genuinely active natural-language status.

Status shows sender observations, including acknowledged payload bytes, elapsed time and ACK-based rate. These are **not receiver-written bytes, reconciled goodput or proof of file integrity**. The final reconciled summary supplies receiver-delivered measurements.

Keep Terminal 2's console open after completion. It retains selectable runs **only in memory**; restarting it does not reload older run IDs for `status` or `explain`.

## 8. Verify files and save final metrics — Terminal 1

Wait until the receiver finishes, prints `Saved ...received.bin` on success, and returns to PowerShell. Sender `COMPLETED` can appear earlier. A zero Java process exit code alone does not establish transfer success.

First save an independent hash check, including an honest unavailable result if no received file was published:

~~~powershell
$sourceCopy = Join-Path $runRoot 'storage/outgoing/sample.bin'
$receivedFile = Join-Path $runRoot ('storage/incoming/' + $c.receiver_name)
$outgoingHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourceCopy).Hash
$receivedHash = $null
$hashStatus = 'UNCONFIRMED'
if (Test-Path -LiteralPath $receivedFile -PathType Leaf) {
    $receivedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $receivedFile).Hash
    $hashStatus = if ($receivedHash -eq $outgoingHash -and $outgoingHash -eq $c.source_sha256) { 'VERIFIED' } else { 'FAILED' }
}
$hashRecord = [pscustomobject]@{
    original_sha256 = $c.source_sha256
    outgoing_sha256 = $outgoingHash
    received_sha256 = $receivedHash
    independent_hash_status = $hashStatus
}
$hashRecord | Format-List
$hashPath = Join-Path $runRoot 'independent-hashes.json'
if (Test-Path -LiteralPath $hashPath) { throw 'Hash evidence already exists; preserve it.' }
$hashRecord | ConvertTo-Json | Set-Content -LiteralPath $hashPath -Encoding UTF8
if ($outgoingHash -ne $c.source_sha256 -or $hashStatus -eq 'FAILED') {
    throw 'Hash disagreement has been saved. Stop the normal workflow and inspect the existing evidence before another transfer or paid analysis.'
}
~~~

This independent check is separate from the engine's integrity outcome. No published received file means `UNCONFIRMED`, not proof of a checksum mismatch. Preserve failed runs and their original artifacts.

For a normal completed pair, locate the two endpoint directories in this fresh run folder and reconcile them **without an API call**:

~~~powershell
$endpointRoot = Join-Path $runRoot 'logs/standalone'
$senderDirs = @(Get-ChildItem -LiteralPath $endpointRoot -Directory | Where-Object {
    Test-Path -LiteralPath (Join-Path $_.FullName 'endpoint-sender.json')
})
$receiverDirs = @(Get-ChildItem -LiteralPath $endpointRoot -Directory | Where-Object {
    Test-Path -LiteralPath (Join-Path $_.FullName 'endpoint-receiver.json')
})
if ($senderDirs.Count -ne 1 -or $receiverDirs.Count -ne 1) {
    throw 'Expected one finalized sender and receiver. Preserve the run and inspect missing/incomplete endpoint evidence.'
}
$senderDir = $senderDirs[0].FullName
$receiverDir = $receiverDirs[0].FullName
$senderRecord = Get-Content -LiteralPath (Join-Path $senderDir 'endpoint-sender.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$receiverRecord = Get-Content -LiteralPath (Join-Path $receiverDir 'endpoint-receiver.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $senderRecord.protocol_transfer_id -or $senderRecord.protocol_transfer_id -ne $receiverRecord.protocol_transfer_id) {
    throw 'Endpoint protocol identities do not match; do not combine these records.'
}
$protocolId = $senderRecord.protocol_transfer_id
$summaryDir = Join-Path $endpointRoot ('reconciled/' + $protocolId)
$summaryPath = Join-Path $summaryDir 'summary.jsonl'
if (-not (Test-Path -LiteralPath $summaryDir)) {
    java -jar $jar reconcile $senderDir $receiverDir
    if ($LASTEXITCODE -ne 0) { throw 'Reconciliation rejected the evidence; inspect the error, do not edit the raw records.' }
} else {
    Write-Output 'Reconciliation output already exists; preserving it rather than overwriting.'
}
if (-not (Test-Path -LiteralPath $summaryPath -PathType Leaf)) { throw 'No finalized summary is available.' }
$summary = Get-Content -LiteralPath $summaryPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($summary.protocol_transfer_id -ne $protocolId -or $summary.sender_run_id -ne $senderRecord.run_id -or $summary.receiver_run_id -ne $receiverRecord.run_id -or $summary.reconciliation_status -ne 'VERIFIED' -or $summary.evidence_completeness -ne 'COMPLETE') {
    throw 'Summary identities/completeness do not match this endpoint pair.'
}
$summary.metrics | Format-List
Write-Output "Summary: $summaryPath"
~~~

The two endpoint directories must share `logs/standalone`. Their local IDs differ; matching filenames or choosing the newest directory is not enough. Fresh reconciliation validates finalization, identity, counters and configuration. An existing reconciliation is never overwritten: this script checks its IDs/status, but does not rerun exporter counter validation. The explanation provider independently validates reused evidence. If only sender failure evidence exists, retain it and its unavailable reasons rather than inventing a receiver or combined summary.

Save a convenient CSV row for later comparisons while retaining the JSON and raw event logs:

~~~powershell
$metricColumns = @(
    'file_size_bytes', 'payload_bytes_delivered', 'transfer_time_sec', 'throughput_mbps',
    'transfer_success', 'integrity_verified', 'failure_reason',
    'packets_sent', 'packets_received', 'acks_received', 'packets_acked', 'packets_timed_out',
    'packets_duplicated', 'packets_dropped', 'retransmissions', 'retransmission_ratio',
    'udp_payload_bytes_emitted', 'protocol_overhead_bytes', 'protocol_overhead_ratio',
    'rtt_sample_count', 'rtt_mean_ms', 'rtt_p95_ms',
    'chunk_size_bytes', 'window_bytes_requested', 'window_packets', 'timeout_ms',
    'retry_limit', 'packet_loss_rate', 'delay_ms', 'scenario', 'impairment_seed',
    'impairment_mechanism'
)
$summary.metrics | Select-Object -Property $metricColumns |
    Export-Csv -LiteralPath (Join-Path $runRoot 'metrics-row.csv') -NoTypeInformation -Encoding UTF8 -NoClobber
Get-Content -LiteralPath (Join-Path $summaryDir 'manifest.json') -Raw -Encoding UTF8
Get-Content -LiteralPath (Join-Path $senderDir 'events-sender.jsonl') -Encoding UTF8 -TotalCount 5
Get-Content -LiteralPath (Join-Path $receiverDir 'events-receiver.jsonl') -Encoding UTF8 -TotalCount 5
~~~

Use `throughput_mbps` as delivered-payload goodput. Combined emitted bytes include both endpoints, control traffic and retries, including DATA subsequently dropped by the receive-side shim; they exclude UDP/IP/link headers. Overhead uses this declared boundary. Null is unavailable, not zero; the authoritative reasons remain in the JSON. See [LOGGING.md](../LOGGING.md) for definitions.

## 9. Request analysis — inside the still-open transfer console

Do this only after terminal completion and receiver finalization. **Choose one route per explanation**, and skip this section for runs needing only measurements.

**One explanation operation, without paid interpretation:**

~~~text
explain {"run_id":null,"question":"Summarize this run using only the supplied measurements: outcome and integrity, delivered bytes, transfer time and goodput, configured loss versus observed simulator drops, retransmissions and timeouts, RTT, and combined sender-and-receiver UDP payload emissions and overhead. State unavailable values and uncertainty; do not invent causes or recommendations."}
~~~

Here `run_id:null` selects this console's current/last run. With this guide's one-run-per-console setup it is unambiguous. To select explicitly, replace `null` with the quoted application `run_id` printed at acceptance, not the wire protocol UUID.

**Natural-language analysis demonstration — interpretation plus explanation:**

~~~text
ask Explain the last transfer using its recorded measurements. State the outcome and integrity, delivered bytes, elapsed time and goodput, configured loss versus observed drops, retransmissions, timeouts, RTT, and combined sender-and-receiver emissions and overhead. Do not invent causes.
~~~

The console displays the underlying evidence beside the explanation. GPT receives the projected summary and source references, not the full raw packet-event timeline.

`EVIDENCE_PENDING` can mean the receiver is still finalizing. Wait and inspect before requesting analysis again. A natural-language request may already have paid for interpretation even if the explanation is not called. If capture, API completion or analysis fails, preserve that result and inspect it before approving another request; do not repeatedly retry by typing the command.

Java's structural/numeric acceptance does not establish semantic correctness. Review endpoint attribution, configured versus observed values, units, missing reasons and claims against the actual captured input. A remains `REQUIRES_CORRECTION`; neither rewritten reviewer prose nor this new guide changes its original status. Use B–D's reviewed explanations with their presentation caveats when showing prior evidence.

## 10. Show deterministic rejection, close, and retain the evidence

For the final demonstration, show an invalid command rejected by Java **without an API call**:

~~~text
start_transfer {"file_id":"sample","receiver_id":"receiver-a","window_bytes":8192,"timeout_ms":1}
~~~

Expected: `INVALID_PARAMETER`, because 1 ms is below the allowed 50 ms; no new sender starts. A model's refusal alone is separate evidence and does not replace this demonstration.

After the transfer is terminal and any explanation finishes:

~~~text
exit
~~~

Terminal 2 returns to PowerShell and its transcript closes. Keep the console open until this point; it cannot later reload the run for explanation.

Inspect capture completeness in Terminal 2:

~~~powershell
$captureRoot = Join-Path $runRoot 'target/evaluation'
$captureDirs = @(Get-ChildItem -LiteralPath $captureRoot -Directory -Filter 'llm-*')
if ($captureDirs.Count -ne 1) { throw 'Expected exactly one recorded console session in this run folder; inspect missing or unexpected captures.' }
foreach ($captureDir in $captureDirs) {
    $sessionEnd = Join-Path $captureDir.FullName 'session-end.json'
    Write-Output $captureDir.FullName
    if (Test-Path -LiteralPath $sessionEnd) {
        Get-Content -LiteralPath $sessionEnd -Raw -Encoding UTF8 | ConvertFrom-Json | Format-List
    } else {
        Write-Warning 'No session-end.json: recording closure is unconfirmed.'
    }
    $apiEvents = @(Get-Content -LiteralPath (Join-Path $captureDir.FullName 'events.jsonl') -Encoding UTF8 | ForEach-Object { $_ | ConvertFrom-Json })
    $apiEvents | Where-Object { $null -ne $_.metadata } | ForEach-Object { $_.metadata } |
        Format-Table requestedModel, returnedModel, attempt, httpStatus, apiLatencyMillis, inputTokens, outputTokens, failureCode
    Get-ChildItem -LiteralPath $captureDir.FullName -Recurse -File |
        Where-Object { $_.Name -eq 'request.json' -or $_.Name -like 'attempt-*-response.txt' } |
        Select-Object FullName
}
~~~

`COMPLETE` describes recording closure, not successful transfer or correct prose. Check per-attempt failures and omissions too; missing usage is unknown, not zero. A direct-only session should have zero model requests. Capture folders preserve exact available requests/responses, usage/timing, Java decisions and rejected drafts labelled untrusted.

The latest prior project ledger estimates **$0.13104675 spent of $10; $9.86895325 remaining**, including the initial user-reported $0.08. It is [saved here](../target/evaluation/section6-unsupported-prep-20260925-221110-460830/api-usage-and-cost.json), not an automatic spending cutoff. Add later actual usage to a fresh ledger; never overwrite the prior one. A natural-language start/status uses one operation, direct explanation one, and natural-language explanation two; each operation can make up to two HTTP attempts.

When finished with paid work in this terminal, remove its process-local key:

~~~powershell
Remove-Item Env:OPENAI_API_KEY -ErrorAction SilentlyContinue
~~~

The complete run folder already contains its input/output copies, run configuration, raw event logs, endpoints, summary/manifest, hash check, CSV row and any API capture. Preserve failures as well as successes. A CSV row or screenshot is not a replacement for the raw logs.

Optional sharing, **after both processes have closed**:

~~~powershell
$archive = $runRoot + '.zip'
if (Test-Path -LiteralPath $archive) { throw 'Archive already exists; preserve it.' }
Compress-Archive -LiteralPath $runRoot -DestinationPath $archive
Write-Output $archive
~~~

The archive contains that run's evidence, not the external JAR or all evaluated source changes. Share those separately with the JAR hash recorded in `run-config.json`. The folder and ZIP remain under ignored `target/`; a Git clone will not bring them to a teammate.

## 11. Repeat for Person 4's required experiments

Return Terminal 1 to `$repo`, select the next row, and repeat **sections 3–10** with a new run folder. Terminal 2 loads the newly printed `run-config.json`. Restart both Java processes for each run; simulator settings are read at startup. Reuse the exact small and large master inputs.

| Scenario | File choice | Loss percent | Delay per direction | Window bytes | DATA timeout |
| --- | --- | ---: | ---: | ---: | ---: |
| `baseline` | `small`, then `large` | 0 | 0 ms | 8192 | 500 ms |
| `loss-2` | `small`, then `large` | 2 | 0 ms | 8192 | 500 ms |
| `delay-200` | `small`, then `large` | 0 | 200 ms | 8192 | 500 ms |
| `delay-200-window16` comparison | `large`; also `small` for the proposed eight-run design | 0 | 200 ms | 16384 | 500 ms |

For example, before executing the rest of section 3 for the loss row, set:

~~~powershell
$fileChoice = 'small'
$scenario = 'loss-2'
$lossPercent = 2
$delayMs = 0
$seed = 42
$windowBytes = 8192
$dataTimeoutMs = 500
~~~

**Replace the selection values at the beginning of section 3**; do not paste its baseline assignments afterwards and accidentally reset them. Use the generated `start_command` for the changed window, rather than reusing the baseline command shown in section 6.

The PDF requires small and large files in each of the three scenarios: **six file/scenario combinations**, plus an evidence-based timeout/window comparison. The extra large delayed-window run provides one controlled comparison. Pairing both sizes at both delayed-window settings gives the existing **eight-run proposal**, which is a practical team design, not an instructor-mandated count. Repetitions or further settings are optional, not automatically required.

The shim is `RECEIVE_DELIVERY_V1`:

- Random loss affects eligible DATA received by the receiver, including retries; ACKs are not randomly dropped.
- Fixed delay affects receiver DATA and sender ACK delivery separately. 200 ms in each direction is not a 200 ms RTT and is not jitter.
- START/FINISH controls bypass the shim. Matching profile values at both endpoints are required for the intended experiment/reconciliation.
- A dropped datagram was already emitted. Configured 2% probability does not guarantee exactly 2% observed drops in a finite run.
- With impairment disabled, unavailable configuration/drop evidence is not measured zero. The guide's enabled zero profile provides a measured baseline.

Use direct starts/status and saved summaries for the matrix so measurement collection need not make paid calls. Select explanations separately within the approved budget. Compare goodput, overhead, completion time, retransmissions/timeouts and RTT using their documented meanings. Keep file bytes, seed, chunk size and all other settings constant for a window comparison. Report observed zero spurious retransmissions honestly; one run per configuration does not establish statistical reliability.

The example large delayed transfer may take roughly two minutes with an 8-packet window; this is an estimate, not a promise. Keep the separately recorded supervision limit reasonable for the selected size/configuration.

## 12. Optional standalone sender command

This is a separate basic UDP route. **It is not needed when the console starts the sender**, and its results do not appear in another console's in-memory run history.

For a fresh baseline-only run, prepare section 3 with `$senderRoute = 'direct-main'`, `$windowBytes = 1024` and `$dataTimeoutMs = 200` so the recorded settings match this launcher's fixed defaults. Start the receiver as in section 5. In Terminal 2, load the new configuration as in section 4 and run this **instead of the console**:

~~~powershell
if ($c.sender_route -ne 'direct-main') { throw 'Prepare a separate direct-main run first.' }
Set-Location -LiteralPath $runRoot
$impairmentArgs = @(
    '-Dnettransfer.impairment.enabled=true'
    ("-Dnettransfer.impairment.lossPercent={0}" -f $c.loss_percent)
    ("-Dnettransfer.impairment.delayMs={0}" -f $c.delay_ms_per_direction)
    ("-Dnettransfer.impairment.seed={0}" -f $c.seed)
    ("-Dnettransfer.impairment.scenario={0}" -f $c.scenario)
)
Start-Transcript -LiteralPath (Join-Path $runRoot 'sender-transcript.txt') -NoClobber
try {
    java @impairmentArgs -jar $jar sender $c.port sample.bin
} finally {
    Stop-Transcript
}
~~~

This reads `storage/outgoing/sample.bin`; the receiver publishes `storage/incoming/received.bin`. Sender event logging is automatic. Use section 8 for hashes and reconciliation. Skip the console commands, explanation and API-capture checks for this route; it creates no console capture folder. After both processes close, section 10's archive command can preserve it. No API key is needed. This launcher has a fixed one-packet window and 200 ms DATA timeout; use the console's structured start for Person 4's parameter comparisons. Do not try to pass invented `--window`, `--loss` or `--timeout` flags to it.

## 13. If a step fails

| Observation | Practical response |
| --- | --- |
| Java/Java compiler unavailable | Configure the local JDK in that terminal using section 1. |
| Port 9000 already bound | Finish the previous receiver; use `Get-NetUDPEndpoint -LocalPort 9000` to inspect ownership if needed. |
| Existing incoming file or transcript | Preserve the run and prepare a fresh folder; do not use overwrite flags. |
| `UNKNOWN_FILE` or path rejected | Check `catalog`, the `sample=storage/outgoing/sample.bin` mapping, and the readable file under this run's application root. An older `data/input`-root JAR is incompatible with these current mappings. |
| Model asks a question | Reply within the same console; wait for Java's actual start acceptance. |
| API timeout/incomplete answer | Save the failed attempt; check `status` before doing anything that could start a second transfer. |
| Evidence pending | Wait for receiver finalization/publication, then inspect both endpoints. |
| Reconciliation rejects records | Preserve the error and raw logs; check identities, finalization and matching profiles. Do not edit measurements. |
| Transfer failed / no received file | Preserve sender/receiver failures and null reasons; do not report success or failed integrity without evidence. |
| Recording incomplete or body omitted | Retain the folder and inspect the specific omission/failure before another paid request. |
| Manual transfer deadline reached | Check direct `status`. If still running, interrupt the console with Ctrl+C, then the receiver if necessary; record the external interruption and preserve partial evidence. |

There is **no implemented `stop` or `cancel` console command**. Typing one can become a natural-language request and incur an API call. `exit` refuses while a transfer is active; normal completion then `exit` is preferred. Interruption can leave missing final records or a missing `session-end.json`; do not relabel these as clean completed runs.

For the final 5–8-minute recording or instructor-specified live demo, show a successful natural-language start, an impaired run with metrics, an explanation beside those metrics, and the deterministic invalid-command rejection. The report/protocol specification and at least three shared raw logs with reproduction commands remain separate deliverables. Live integrity `FAILED` remains `NOT EXERCISED`; saved D was transfer `FAILED` with integrity `UNCONFIRMED`.
