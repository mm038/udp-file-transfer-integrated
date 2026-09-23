# Stage 7 integrated acceptance

## Verification status

| Area | Status | Evidence |
| --- | --- | --- |
| Integrated architecture | IMPLEMENTED | CLI, real adapter, logging root, repository, provider, flow, and client are connected. |
| Deterministic acceptance | TESTED OFFLINE | Stub explanation clients are used; automated tests make no OpenAI API calls. |
| UDP workflow | TESTED WITH REAL UDP TRAFFIC | Loopback tests use real sender/receiver engines, sockets, files, hashes, logs, reconciliation, and CLI commands. |
| Live GPT explanation quality | NOT YET VERIFIED | No paid Stage 7 API call was executed. |
| Cross-host behavior | NOT YET VERIFIED | Automated acceptance uses loopback, not two physical hosts or a routed network. |

The final Stage 7 run on September 23, 2026 executed 762 tests with zero failures,
errors, or skips and successfully produced `target/udp-file-transfer.jar`.

## Genuine end-to-end acceptance

`IntegratedWorkflowAcceptanceTest` creates a temporary application root and a
2,500-byte input, drives `start_transfer` through the actual CLI and
`RealTransferService`, and transfers it through real loopback UDP sockets to a
real `ReceiverEngine`. A gated START_ACK keeps the sender in `AWAITING_START`
long enough to inspect genuine live status without changing the protocol.

The test then verifies:

- application, sender-run, and protocol identities;
- sender lifecycle, retained terminal snapshot, counters, ACK progress, RTT,
  elapsed time, ACK-based sender rate, and endpoint-local emissions;
- byte-for-byte output and matching SHA-256;
- finalized sender and receiver event logs, run states, and endpoint records;
- a `PENDING` explanation result during receiver completion recovery, with no
  explanation-client invocation;
- exact repository association and reconciliation;
- reuse of the existing reconciled output without overwrite;
- delivered bytes, success, integrity, packet counts, duration, throughput,
  RTT, UDP payload emissions, overhead, ratios, observed zero values, and
  unavailable reasons;
- conversion through `RealMetricsSummaryProvider` and a REAL `RECONCILED`
  request to a deterministic `ExplanationClient`;
- CLI presentation of the validated evidence and relative source references.

Timing and rates are checked with invariants rather than machine-specific
constants. The test never reconstructs receiver delivery from sender ACK
progress.

## Other acceptance coverage

Existing genuine UDP integration tests cover empty files, START timeout with a
silent peer, dropped FINISH_ACK, receiver SHA-256 mismatch, receiver completion
recovery, adapter shutdown interruption, endpoint logging, sender/receiver
metrics, RTT, retransmission timeout handling, and terminal snapshot retention.

Targeted validation tests cover logging initialization/finalization failure,
missing and incomplete endpoints, conflicting application and protocol IDs,
duplicate receiver candidates, unsupported schemas and metric definitions,
unsafe paths, synthetic/REAL isolation, observed zero versus unavailable,
typed non-available explanation states, malformed model output, client failure,
and deterministic command validation. These are validation tests, not claims
that every case was exercised through the complete CLI-to-UDP-to-explanation
path.

## Optional one-call live GPT acceptance

This procedure is manual and must not be run without explicit authorization.
It uses one genuine finalized transfer. Pending `explain` attempts do not call
the API; stop if output does not say the evidence is available.

1. Build and create `data/input/stage7.txt` under the project root.
2. In terminal 1, start the receiver from the project root so it shares `logs/`:

   ```powershell
   java -jar target/udp-file-transfer.jar receiver 9000 stage7-received.txt
   ```

3. In terminal 2, start the integrated console:

   ```powershell
   $env:OPENAI_API_KEY = '<set privately; never paste into logs>'
   $env:OPENAI_MODEL = 'gpt-5-mini'
   $env:OPENAI_REQUEST_TIMEOUT_MS = '90000'
   java -cp target/udp-file-transfer.jar nettransfer.cli.TransferCliMain . "stage7=data/input/stage7.txt"
   ```

4. Run these direct commands, replacing `<run-id>` with the accepted ID:

   ```text
   start_transfer {"file_id":"stage7","receiver_id":"receiver-a","window_bytes":null,"timeout_ms":null}
   status current
   status last
   explain {"run_id":"<run-id>","question":"Explain the validated outcome, distinguish sender observations from receiver observations, and list important limitations."}
   ```

5. If evidence is still `PENDING`, wait for the receiver's completion-recovery
   period to finish and issue `explain` once more. Only the AVAILABLE attempt is
   intended to make an API call.

Maximum intended paid calls: **1**. The request is bounded by the configured
client and structured schema, but exact cost depends on the selected model's
current input/output token pricing. Check the current official pricing before
authorization; do not infer cost from historical runs.

Expected evidence includes REAL provenance, exact application and protocol IDs,
`RECONCILED` scope for a successful transfer, sender outcome, receiver-local
integrity, recorded metrics with units, unavailable reasons, and validated
relative references beneath `logs/standalone/`.

Reject the live result if it fabricates values, changes units, treats configured
loss as observed loss, describes ACK-based sender rate as reconciled throughput,
equates FINISH_ACK loss with receiver integrity failure, hides missing-value
reasons, claims unsupported causation, changes provenance, or implies that the
model independently verified or executed the transfer.
