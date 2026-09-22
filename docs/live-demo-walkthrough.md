# A simple picture of the instructor demonstration

Planning walkthrough, 21 September 2026. Read this now; there is no need to run it yet. The current code supports the real transfer and validation portions. Measured live progress, impairment setup and real measured explanations still need integration. This document does not start milestone 9.

The final demonstration is: type a request -> GPT proposes a command -> Java validates it -> the UDP engine transfers the file -> Java records measurements -> show those measurements beside GPT's explanation. Finish by showing Java reject an invalid command.

## Prepare once before the demonstration

Use two PowerShell terminals, both opened in the project directory. In each terminal, if Java is not already on PATH, use the project's existing local Java configuration:

```powershell
$env:JAVA_HOME = (Get-Content .vscode/settings.json -Raw | ConvertFrom-Json).'java.configuration.runtimes'[0].path
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
```

In terminal 1, verify and package the project. No clean is needed:

```powershell
mvn -o verify
```

In terminal 2, use an API key already configured privately in that terminal and select the evaluation model. This checks presence without displaying the key:

```powershell
if ([string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) { throw 'Configure the API key privately before starting the natural-language demo.' }
$env:OPENAI_MODEL = 'gpt-5-mini'
```

Choose an existing file under data/input. The following sample uses data/input/llm-demo.txt, which exists in the current workspace. Its console name will be demo. Keep the sample unchanged for repeatability. For a different file, change the mapping in the console launch command.

## 1. Start the receiver - terminal 1

Choose a new output path for this run. The UUID suffix prevents accidental reuse of earlier demonstration filenames:

```powershell
New-Item -ItemType Directory -Force -Path data/received | Out-Null
$receivedFile = Join-Path 'data/received' ('instructor-' + [guid]::NewGuid().ToString('N') + '.txt')
java -jar target/udp-file-transfer.jar receiver 9000 "$receivedFile"
```

Leave this terminal waiting for the transfer. Receiver-a currently means localhost, port 9000. This example runs sender and receiver on the same computer; two physical machines would need an approved Java configuration change.

## 2. Open your transfer console - terminal 2

```powershell
java -cp target/udp-file-transfer.jar nettransfer.cli.TransferCliMain . "demo=data/input/llm-demo.txt"
```

You should see the transfer> prompt. The PowerShell evaluation script is not needed for this interactive demonstration.

## 3. Request a transfer - inside the transfer console

```text
Send demo to receiver-a with a 64 KiB window.
```

Explain to the instructor that GPT proposes the structured request, Java validates the approved file/receiver and settings, and the Java engine sends the bytes. Expected settings are a 65,536-byte window, 64 packet slots, and the default 200 ms DATA timeout. Java prints the accepted application IDs.

If GPT asks an unnecessary question, that is model behaviour to record during rehearsal, not a confirmed start. Wait for Java's Start accepted output.

## 4. Ask for status - inside the transfer console

```text
What is the status of this transfer?
```

Use the direct Java command whenever you want a status check without another API call:

```text
status
```

Today, Java reports RUNNING, COMPLETED or FAILED. A tiny file may finish before the status question is answered; seeing COMPLETED is normal. Detailed bytes/progress/current throughput need the actual observation integration. To demonstrate changing live progress at project completion, rehearse with a larger approved file and/or the integrated delay scenario.

## 5. Show that the file arrived intact

Once the receiver finishes, terminal 1 returns to PowerShell. Compare hashes there:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath 'data/input/llm-demo.txt', $receivedFile
```

The two SHA-256 values should match. The engine also performs its own integrity verification; the final interface must retain the distinction between successful sender completion and verified integrity.

## 6. Show a loss or delay run and its actual measurements - pending integration

This is part of the completed-project demonstration, not a capability already supplied by the current script.

1. Enable Person 2's implemented impairment setup using her actual configuration/command. Use a documented scenario and file. No valid impairment-launch command is available in the current repository, so none is invented here.
2. Restart the receiver in terminal 1 with a fresh output filename, using the commands from step 1. The current receiver handles one transfer per process.
3. Send demo again from the still-open console using the natural-language command from step 3. If using another approved file, launch the console with its mapping before the demonstration.
4. Query status. The integrated observer should expose only the progress actually measured.
5. At completion, display Person 2's finalized summary and linked event log. Their exact paths/format and display command must be filled in after the concrete handoff. Show actual duration, delivered bytes, throughput, timeout/retransmission evidence, and the configured scenario; do not invent representative values.

The final assignment experiments also require baseline, at least 2% random loss, and meaningful delay or jitter, using small and large files in each scenario. Prepare those measured results beforehand; the 5-8 minute demonstration can show selected evidence rather than rerun every experiment live.

## 7. Ask GPT to explain the measured run

Inside the transfer console:

```text
Explain why the last transfer was slow and cite the recorded measurements.
```

After the real provider and identity/measurement integration are complete, the intended result is the selected run's real evidence beside a grounded explanation. GPT must separate measured facts from possible causes and acknowledge missing evidence.

Today this request selects the real outcome, then returns EVIDENCE_UNAVAILABLE before the explanation client. That is expected until integration is complete. Synthetic milestone 8 explanations are for model evaluation and cannot substitute for this final real-evidence demonstration.

## 8. Prove that Java rejects an invalid command

Inside the console, issue this deterministic invalid proposal:

```text
start_transfer {"file_id":"demo","receiver_id":"receiver-a","window_bytes":65536,"timeout_ms":1}
```

Java should report INVALID_PARAMETER and start no transfer because 1 ms is outside the allowed timeout range. This direct proposal deliberately bypasses model variability so the instructor sees the Java validation boundary. A model's refusal to delete a file alone would not prove that boundary.

After any active transfer finishes, close the console:

```text
exit
```

## What the instructor should see

A successful natural-language transfer, matching file hashes, a real loss/delay run with collected metrics, an explanation citing those metrics, and a Java-rejected invalid command. The completed project also supplies source/build instructions, protocol specification and sequence diagram, evaluation report, raw logs and reproducibility instructions.

Before this is a fully runnable final-demo script, insert and rehearse the actual impairment command, summary/event paths and measured-status integration. These remain shared follow-up work; this walkthrough does not claim they already exist.
