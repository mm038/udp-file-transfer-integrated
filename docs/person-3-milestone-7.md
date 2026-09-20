# Milestone 7: independent offline regression suite

Verified September 20, 2026 on `person-3/llm-integration`: **566 tests across 30 classes**, zero failures/errors/skips, and a successful JAR build with `mvn -o verify`. This adds 68 checks to the existing 498. Milestone 7's independent offline scope is complete. Stop for review before milestone 8; no live OpenAI call, commit or push was performed.

## Audit before editing

The working tree was inspected first. HEAD was `75a6a7e`; six modified production files and the untracked `ResponsesExplanationClient.java` / `ResponsesTransport.java` already contained milestone 6 HTTP work. Those files were preserved without edits. `dependency-reduced-pom.xml` was already untracked. The existing UDP engine, adapter, dependencies, `.gitignore` and REAL-evidence gate were left unchanged.

Read the README, handoff, checklist, both milestone 6 walkthroughs, revised metrics, metric review and all four pages of the assignment PDF. The PDF requires Java validation and grounded analysis (pages 1-2), recorded metrics and numerical citations (pages 2-3), and real experiments/logs (page 3). It does not prescribe these milestones, Java types or producer field names. Synthetic tests verify interface behavior; they do not fulfill the required measured experiments.

| Milestone 7 requirement | Existing coverage reused | Audit result |
| --- | --- | --- |
| Valid/invalid commands, exact types, fields, resources, bounds, missing essentials, unsupported operations | `CommandParserTest`, `CommandValidatorTest`, `CommandDispatcherTest` | Covered; no duplicate cases added |
| Invalid model proposal causes no start | `TransferCliGptTest.invalidModelProposalsNeverReachServiceStart` | Twelve invalid stub proposals already cross real Java validation |
| Status/lifecycle, current/last IDs, concurrent starts, integrity-dependent completion | `TransferCliTest`, `FakeTransferServiceTest`, `TransferContractTest`, dispatcher tests | Covered, including a completion/selection race |
| Byte windows, worker responsiveness, sentinel counters, truthful real states | `RealTransferServiceTest`, `RealTransferServiceIntegrationTest`, validator tests | Covered with worker synchronization and local UDP; no observation data invented |
| Command HTTP construction, refusal/incomplete/malformed/error/deadline responses | `ResponsesGptClientTest`, `GptSettingsTest` | Covered, including stalled bodies and bounded retries |
| Retries cannot duplicate starts; secrets do not appear in output | `ResponsesGptClientTest.httpRetryThroughCliStartsExactlyOnceAndPreservesJavaRequestIdentity`, CLI and credential tests | Covered; two HTTP attempts produce one start |
| Generic explanations: missing fields, engine failure, causal limits, identity, invalid references, REAL gate | `ExplanationFlowTest`, `ExplanationBoundaryTest`, `TransferCliExplanationTest`, dispatcher tests | Covered; retain generic fixtures unchanged |
| Complete accepted metric vocabulary and supporting fields | Existing fixtures used a small earlier vocabulary | Gap: targeted synthetic compatibility coverage added below |
| Separate analysis HTTP adapter | Existing 59 cases in `ResponsesExplanationClientTest` | Reuse; add missing/unknown/contradictory envelope statuses and reuse projection assertions with full accepted fixtures |
| Reproducible complete offline verification | Previous recorded run was 498 tests | Fresh complete run now verifies 566 tests |

## What changed where

All code changes for this milestone are under `src/test/java`; production code is unchanged.

| File | Change |
| --- | --- |
| `explanation/AcceptedMetricFixtures.java` | Six authored synthetic examples: lossless baseline, decimal/accounting boundaries, partial failure, checked integrity mismatch, receiver verification without sender confirmation, and empty-file/zero-duration/no-sample edges. Separate typed test metadata; no file loader or metric calculator. |
| `explanation/AcceptedMetricRegressionTest.java` | 50 new checks for complete vocabulary, fixture sanity, seconds/Mbps/fraction precision, invalid conversions/inferences, boolean/null outcomes, all 18 nullable numerical measurements, inconsistent counters and controlled identity boundaries. |
| `cli/TransferCliExplanationTest.java` | Six new scenarios reuse the existing CLI/service harness. Check every field's exact value/unit/kind/definition, adjacent missing reason, synthetic label, selected state/integrity and zero starts. Total: 19 tests. |
| `llm/ResponsesExplanationClientTest.java` | Existing projection assertions now also run on all six accepted fixtures. Five invalid envelope cases plus explicit-null diagnostics acceptance. Total: 71 tests, up from 59. |
| `docs/person-3-task-checklist.md`, `docs/person-3-handoff.md`, `README.md` | Record audit, verified scope and review stop; keep real integration dependencies open. |

The baseline follows the revised document's feasible 10 MiB / 10,240 original DATA example. Its window is the existing test harness's 4,096 bytes / four packets, rather than the document's 65,536-byte illustration. The high-precision/accounting case supplies authored numbers to test preservation; it is explicitly not a reconstructed packet history. The fixtures do not send files or claim measured values. `OBSERVED` denotes a field's meaning within a `SYNTHETIC` fixture, not a real observation.

## Accepted fields and boundaries

All **26 numerical fields** fit the existing 32-field envelope. Their unit spellings below are local fixture choices; producer serialization and unit representation remain pending.

| Fields | Synthetic coverage |
| --- | --- |
| `file_size_bytes`, `payload_bytes_delivered` | Bytes; source size is separate from partial receiver delivery, sender ACK progress and integrity |
| `transfer_time_sec`, `throughput_mbps` | Seconds and decimal Mbps preserved as `BigDecimal`, including scale; no truncation to integer milliseconds, rounding or conversion to bytes/s |
| `packets_sent`, `packets_received`, `packets_dropped`, `retransmissions`, `acks_received`, `packets_acked`, `packets_timed_out`, `packets_duplicated` | DATA-specific definitions; counts of arrivals/attempts differ from unique ACK progress, simulator drops and detected timeout events |
| `retransmission_ratio` | Fraction of DATA attempts; zero versus undefined denominator; not configured or observed loss percentage |
| `udp_payload_bytes_emitted`, `protocol_overhead_bytes`, `protocol_overhead_ratio` | Supplied bytes/fraction preserved; missing accounting is not derived from DATA counts |
| `rtt_sample_count`, `rtt_mean_ms`, `rtt_p95_ms` | Count and milliseconds; unavailable sampling differs from zero usable samples; RTT is not inferred from configured delay |
| `chunk_size_bytes`, `window_bytes_requested`, `window_packets`, `timeout_ms`, `retry_limit`, `packet_loss_rate`, `delay_ms` | All seven are `CONFIGURED`; loss is percent, delay is milliseconds, retry limit is consecutive rounds rather than packet resends |

The remaining fields stay typed in a **test-only metadata record**: `experiment_id`, schema/metric-definition versions, finalization time, endpoint attribution, file identity, integrity evidence source, scenario, nullable seed, boolean success, nullable boolean integrity, nullable failure reason and unavailable reasons. The existing envelope carries source, capture time, fixture version, application run/transfer IDs and nullable protocol ID. These records document controlled test associations, not verified producer/engine associations.

No string/boolean is converted to a numeric metric. The tests explicitly author the selected Java outcome: receiver integrity `true` alongside sender success `false` still selects `FAILED`/`UNCONFIRMED`; integrity `false` and `null` remain distinct test metadata. Production does **not** yet ingest, reconcile, transmit or display that full metadata record. The HTTP adapter continues sending only the current envelope and selected Java state/integrity. Actual outcome adaptation requires the concrete producer handoff.

Every nullable numerical measurement is exercised as null with a reason and a rejected attempted zero citation. Supplied zero remains zero. A supplied numerator and denominator do not cause the consumer to calculate a missing rate/ratio. Existing tests retain the REAL gate, generic provider failure behavior, nullable identity rules and evidence preservation on client failure.

**Inconsistent-counter limit:** the generic envelope checks bounded nonnegative numbers, identity and citations; it does not validate relationships between counters. Negative examples include the original impossible 1,200-attempt / 120-resend 10 MiB illustration, more resends than attempts, excessive duplicate/ACK counts and delivered bytes exceeding source size. Tests verify original values remain unchanged and a draft citing invented corrections is rejected. A separate fixture sanity assertion demonstrates the impossible chunk capacity. This is not a claim that production rejects semantically inconsistent producer records. Such acceptance/reconciliation checks remain milestone 9 work once producer definitions and outputs exist.

The regression suite also avoids false equalities: `sent - received` need not equal simulator drops, resends need not equal timeouts, and ACK arrivals need not equal newly acknowledged DATA sequences. Repeated external experiment labels cannot bypass application-run checks; a known fixture wire UUID cannot fill in a selected unknown wire identity. No identity is derived from a filename, timestamp, label or hash.

## Verification and reproducibility

Run from the project root with Java 17 and cached dependencies. The environment assignments affect only this PowerShell process; do not print credentials.

```powershell
$env:JAVA_HOME = (Get-Content .vscode/settings.json -Raw | ConvertFrom-Json).'java.configuration.runtimes'[0].path
$env:OPENAI_API_KEY = $null
mvn -o '-Dtest=AcceptedMetricRegressionTest,ResponsesExplanationClientTest,TransferCliExplanationTest' test
mvn -o verify
```

The first focused run passed **135 tests**. After review expanded HTTP projection to the remaining five scenarios, the final full run passed **566 tests across 30 classes**, zero failures/errors/skips, and packaged `target/udp-file-transfer.jar`. The final focused selection contains 140 cases (50 + 71 + 19), all included in that full run. The 68 additions are 50 metric checks, six CLI cases and 12 net HTTP cases.

The first full sandbox run failed only the existing Windows temporary-file ACL test. The approved rerun outside the sandbox passed it and the whole suite. Maven used `-o`, the API key was cleared in the test process, and HTTP clients used explicit loopback endpoints. Existing local UDP tests also ran; these are regression checks, not new experiment logs. No new manual transfer or live model-quality result is claimed.

`clean` was not run, preserving earlier manual/hash evidence in `target/`. Shade regenerated the pre-existing untracked `dependency-reduced-pom.xml`; it remains excluded from commits. Incremental packaging reported overlapping dependency entries while completing successfully. The prior production-file contents were checked for preservation, and the engine/adapter diff remained empty.

## Review stop and remaining work

Review the fixture definitions, the 50 metric checks and reused HTTP/CLI paths, especially the distinction between fixture metadata and production support. **Milestone 8 has not started.** After review, that milestone can add an opt-in live prompt evaluation path, separately authorized and excluded from ordinary offline tests. It can use clearly labelled synthetic summaries; real logs are not a prerequisite for synthetic model evaluation.

Real integration under milestones 6/9 still needs Person 2's actual logs and finalized summaries, concrete serialization/types/null encoding, event format/location/version/finalization rules, endpoint attribution and verified experiment/application/wire association. Person 1's identity/observer hooks remain pending. Then implement the real provider and outcome reconciliation, reject inconsistent actual records according to that contract, and verify success/failure explanations beside real evidence. The complete revised field scope is already accepted and does not need agreement again. Until those dependencies exist, REAL explanations return `EVIDENCE_UNAVAILABLE` before provider/client invocation.

## Commit locally after review

No files were staged, committed or pushed. The new tests depend on your pre-existing uncommitted milestone 6 HTTP implementation. Commit that reviewed prerequisite first (if desired), then commit milestone 7; a tests-only commit against current HEAD would omit required source files.

```powershell
git status --short
git diff --check
git diff
# Review the two untracked HTTP source files too; plain git diff omits untracked files.
git add src/main/java/nettransfer/llm/ResponsesExplanationClient.java src/main/java/nettransfer/llm/ResponsesTransport.java src/main/java/nettransfer/llm/ResponsesGptClient.java src/main/java/nettransfer/llm/GptException.java
git add src/main/java/nettransfer/explanation/ExplanationRequest.java src/main/java/nettransfer/explanation/ExplanationFlow.java src/main/java/nettransfer/cli/TransferCli.java src/main/java/nettransfer/cli/TransferCliMain.java
git diff --cached --check
git diff --cached
git commit -m "Complete milestone 6 explanation HTTP implementation"

git add src/test/java/nettransfer/explanation/AcceptedMetricFixtures.java src/test/java/nettransfer/explanation/AcceptedMetricRegressionTest.java
git add src/test/java/nettransfer/cli/TransferCliExplanationTest.java src/test/java/nettransfer/llm/ResponsesExplanationClientTest.java
git add README.md docs/person-3-handoff.md docs/person-3-task-checklist.md docs/person-3-milestone-7.md
git diff --cached --check
git diff --cached --stat
git diff --cached
git commit -m "Complete independent milestone 7 offline regression coverage"
```

These are commands for you to run after reviewing each staged diff. They deliberately exclude `dependency-reduced-pom.xml` and ignored `target/` artifacts. If you prefer one commit, stage the reviewed prerequisite and milestone 7 files together before committing.
