# Stage 7 integrated acceptance

Historical Stage 7 acceptance record, updated 25 September 2026 for the evaluated working tree based on commit `985b1a8`. The historical stage name is separate from Section 7 of the current evaluation plan. Sections 3–6 evaluation and prose review are complete, with A's original explanation retaining `REQUIRES_CORRECTION`. Person 4's experiments and final deliverables remain pending. The later procedure is reference guidance, not authorization to run it now. See the [current evaluation checkpoint](../README.md#current-evaluation-checkpoint).

The later approved source-directory update changes current console mappings to
`storage/outgoing`, shared with the standalone sender. Saved A–D and Section 6
acceptance used the earlier `data/input` console root; none of those artifacts or
review results have been rewritten. The current procedure requires the new
source-root build identified in the [walkthrough](live-demo-walkthrough.md).
Its [separate validation](../target/evaluation/storage-outgoing-20260925-231608-232001/validation.json)
records 315 passing focused tests across 14 classes. Both the previous and updated
JARs are preserved there; this does not replace the earlier live acceptance records.

## Verification status

| Area | Status | Evidence |
| --- | --- | --- |
| Integrated architecture | IMPLEMENTED | CLI, real adapter, logging root, repository, provider, flow, and client are connected. |
| Deterministic acceptance | TESTED OFFLINE | Stub explanation clients are used; automated tests make no OpenAI API calls. |
| UDP workflow | TESTED WITH REAL UDP TRAFFIC | Loopback tests use real sender/receiver engines, sockets, files, hashes, logs, reconciliation, and CLI commands. |
| Live GPT explanation quality | REVIEWED WITH LIMITATIONS | B–D passed the assignment-minimum review with minor presentation caveats; A retains `REQUIRES_CORRECTION`. See final evidence below. |
| Natural-language start, active status and clarification | PASS | A start/selection, C active status, and Section 6 context-retaining clarification each have reviewed real evidence. |
| Unsupported natural-language operation | PASS | Section 6 caused no execution or deletion; this is separate from deterministic Java rejection tested offline. |
| Cross-host behavior | NOT EXERCISED | The saved acceptance uses loopback, not two physical hosts or a routed network. |
| Live integrity `FAILED` | NOT EXERCISED | D is transfer `FAILED`, integrity `UNCONFIRMED`; existing receiver mismatch tests are separate evidence. |
| Required experiment matrix, report and final demonstration | PENDING PERSON 4 / TEAM | Capability checks do not establish finished submission or demonstration. |

The historical final Stage 7 run on September 23, 2026 executed 762 tests with zero
failures, errors, or skips and successfully produced `target/udp-file-transfer.jar`.
Fresh offline verification on September 25 also passed 762 tests across 56 classes
and packaged the JAR. Do not repeat that full suite for these documentation edits;
never run `mvn clean`, because `target/` contains preserved evaluation evidence.

## Final live evidence

Use final validation/review files, not historical preparation or session-result
"remaining" lists. Earlier records remain unchanged and can contain superseded
statuses.

| Check | Saved result and evidence |
| --- | --- |
| A: baseline, natural-language start and explanation selection | [Transfer/selection PASS](../target/evaluation/section5-run-a-32768-20260925-204658-930498/validation.json); [original prose `REQUIRES_CORRECTION`](../target/evaluation/section5-run-a-32768-20260925-204658-930498/explanation-review.json). |
| B: 2% random DATA loss | [Transfer PASS](../target/evaluation/section5-run-b-loss2-20260925-210849-739240/validation.json); [explanation minimum PASS with minor presentation caveats](../target/evaluation/section5-run-b-loss2-20260925-210849-739240/explanation-review.json). |
| C: 200 ms fixed DATA/ACK delay per direction | [Transfer PASS](../target/evaluation/section5-run-c-delay200-20260925-211634-307439/validation.json), [active natural-language status PASS](../target/evaluation/section5-run-c-delay200-20260925-211634-307439/natural-language-status-review.json), [explanation minimum PASS with minor presentation caveats](../target/evaluation/section5-run-c-delay200-20260925-211634-307439/explanation-review.json). |
| D: unavailable receiver | [Expected bounded failure PASS](../target/evaluation/section5-run-d-unavailable-20260925-212915-115426/validation.json); [explanation minimum PASS with minor presentation caveats](../target/evaluation/section5-run-d-unavailable-20260925-212915-115426/explanation-review.json). Outcome is `FAILED` / `UNCONFIRMED`, not failed integrity. |
| Missing receiver clarification and follow-up | [Final validation PASS](../target/evaluation/section6-clarification-prep-20260925-215444-525297/validation.json), [initial clarification review](../target/evaluation/section6-clarification-prep-20260925-215444-525297/clarification-review.json), [context-retaining follow-up review](../target/evaluation/section6-clarification-prep-20260925-215444-525297/followup-review.json), [exactly one successful transfer review](../target/evaluation/section6-clarification-prep-20260925-215444-525297/transfer-review.json). |
| Unsupported operation | [Final validation PASS](../target/evaluation/section6-unsupported-prep-20260925-221110-460830/validation.json), [prose review with minor presentation caveat](../target/evaluation/section6-unsupported-prep-20260925-221110-460830/unsupported-review.json). No transfer, deletion or unsupported execution occurred. |

A's prose incorrectly attributed combined sender/receiver UDP emission bytes to
the sender and included unsupported claims. Leave the original explanation and
failed review intact. Use verified measurements for comparisons and reviewed
explanations for presentation; any reviewer wording must be labeled separately
from original model output. No fix or paid regeneration of A is needed to begin
the required experiments.

## Resolved inspection findings

| Former gap | Passing repair evidence |
| --- | --- |
| Reconciliation lost unavailable RTT reasons | [74 targeted tests, zero failures/errors](../target/evaluation/metrics-null-reasons-20260925-131319-640/validation.json). |
| No reproducible integrated loss/delay option | [320 focused tests and packaged checks](../target/evaluation/controlled-impairment-20260925-134232-676/validation.json), with [independent transfer checks](../target/evaluation/controlled-impairment-20260925-134232-676/independent-transfer-checks.json); B/C provide later live acceptance. |
| No exact API capture route | [341 focused tests and packaged capture check](../target/evaluation/api-evidence-capture-20260925-151307-190/validation.json); A–D and Section 6 provide later request/response, attempt and Java-decision captures. |
| Receiver accepted invalid DATA framing | [160 focused tests and raw-UDP checks](../target/evaluation/data-framing-20260925-161314-533/validation.json), with [independent framing checks](../target/evaluation/data-framing-20260925-161314-533/independent-framing-checks.json). |

These repairs are in the evaluated local changes; the base commit alone does not
include them. Retain the existing extreme-file-size/chunk-count validation limit
as a documented limitation and use explicitly bounded practical experiment
sizes. No concrete blocker was found for Person 4's required experiments.

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

## Later approved API work

No repeat of A–D or the full suite is needed merely for documentation cleanup or
handoff. Any new paid work requires explicit approval and a fresh evidence
directory. For such later work, preserve these implemented controls:

- Keep `gpt-5-mini`, `commands-v2` with a 4,096-token interpretation cap, and
  `explanations-v6` with a 32,768-token explanation cap.
- The console allows at most two HTTP attempts per operation. A direct structured
  explanation uses one operation; natural-language explanation adds a separate
  interpretation operation, each with its own two-attempt bound.
- Set `OPENAI_REQUEST_TIMEOUT_MS=90000`. This is the API timeout, not the DATA
  retransmission timeout or an outer transfer supervision deadline; record any
  separately selected transfer deadline.
- Enable `-Dnettransfer.evaluation.record=true` at console startup. It creates a
  fresh `target/evaluation/llm-...` folder with available exact request/response
  bodies, attempt usage/timing and Java decisions, including failures and rejected
  drafts. Check `session-end.json` and any explicit body omissions. Recording
  does not establish prose correctness, and missing usage is unknown, not zero.

Reference procedure for a later approved transfer:

1. Choose or create a readable regular input under `storage/outgoing`, for example
   `storage/outgoing/stage7.txt`; this example is not assumed to exist. Preserve its
   size and SHA-256. Use the new source-root build, not the earlier acceptance JAR.
2. In terminal 1, start the receiver from the project root so it shares `logs/`:

   ```powershell
   $receivedName = 'stage7-' + [guid]::NewGuid().ToString('N') + '.txt'
   $receivedFile = Join-Path 'storage/incoming' $receivedName
   java -jar target/udp-file-transfer.jar receiver 9000 "$receivedName"
   ```

   The argument is a safe filename; the receiver publishes it under
   `storage/incoming`. Restart the receiver with a new filename for every transfer.

3. Use the integrated console with capture enabled. The key must already be
   configured privately; do not type it into a saved command:

   ```powershell
   if ([string]::IsNullOrWhiteSpace($env:OPENAI_API_KEY)) { throw 'Configure the API key privately before the paid evaluation.' }
   $env:OPENAI_MODEL = 'gpt-5-mini'
   $env:OPENAI_REQUEST_TIMEOUT_MS = '90000'
   java '-Dnettransfer.evaluation.record=true' -cp target/udp-file-transfer.jar nettransfer.cli.TransferCliMain . "stage7=storage/outgoing/stage7.txt"
   ```

4. Use direct Java commands to start and inspect the transfer; these commands do
   not call the API. Record the accepted application/run IDs:

   ```text
   start_transfer {"file_id":"stage7","receiver_id":"receiver-a","window_bytes":null,"timeout_ms":null}
   status current
   status last
   ```

5. Wait for receiver completion recovery and the saved-file message. In terminal 1,
   compare the preserved input and published output independently:

   ```powershell
   Get-FileHash -Algorithm SHA256 -LiteralPath 'storage/outgoing/stage7.txt', $receivedFile
   ```

6. Preserve finalized endpoint records and events, reconcile the exact matching
   endpoints, and inspect the evidence before requesting a paid explanation.
   Endpoint paths are `logs/standalone/<local-run-id>/`; combined outputs are
   `logs/standalone/reconciled/<protocol-uuid>/summary.jsonl` and `manifest.json`.
   Sender and receiver local run IDs differ. See [LOGGING.md](../LOGGING.md) for the
   explicit reconciliation command; source-reference paths do not send raw logs
   to GPT.

7. Through the prepared evaluation route, request one direct explanation,
   replacing `<run-id>` with the accepted run ID:

   ```text
   explain {"run_id":"<run-id>","question":"Explain the validated outcome, distinguish sender observations from receiver observations, and list important limitations."}
   ```

   `PENDING`, incomplete, unavailable or rejected evidence stops this direct route
   before the explanation API client. An AVAILABLE result can invoke it immediately;
   `explain` is not a free availability probe. A natural-language request can still
   incur an interpretation request before the evidence check.

The direct explanation above permits at most two HTTP attempts. Preserve failed
attempts and rejected prose as evidence. The latest saved [cost ledger](../target/evaluation/section6-unsupported-prep-20260925-221110-460830/api-usage-and-cost.json)
estimates project spend at $0.13104675 of $10, including the initial reported
$0.08, leaving $9.86895325. It is historical accounting, not a budget authorization
for further calls.

For controlled baseline/loss/delay measurements, use matching explicit profiles
on both receiver and console as documented in [README](../README.md#controlled-loss-and-delay)
and the [demo walkthrough](live-demo-walkthrough.md#6-show-a-loss-or-delay-run-and-its-actual-measurements).
The simple commands above leave impairment disabled, so they do not establish
measured zero simulator drops. Enabled zero-loss/zero-delay baseline, 2% receiver
DATA loss and fixed DATA/ACK delay are implemented; jitter is not claimed.

Person 4 still needs small and large files under baseline, at least 2% random
loss, and meaningful delay or jitter: six file/scenario combinations, plus the
required timeout/window comparison. A–C used the same 262,267-byte file and
settings, so they are capability evidence rather than the completed matrix.
The plan's eight-run proposal is a practical design, not an instructor-mandated
count. Final report, specification/diagrams, log bundle, reproduction instructions,
model/library disclosure and final demonstration remain pending.

Expected evidence includes REAL provenance, exact application and protocol IDs,
`RECONCILED` scope for a successful transfer, sender outcome, receiver-local
integrity, recorded metrics with units, unavailable reasons, and validated
relative references beneath `logs/standalone/`.

Reject the live result if it fabricates values, changes units, treats configured
loss as observed loss, describes ACK-based sender rate as reconciled throughput,
equates FINISH_ACK loss with receiver integrity failure, hides missing-value
reasons, claims unsupported causation, changes provenance, or implies that the
model independently verified or executed the transfer.
