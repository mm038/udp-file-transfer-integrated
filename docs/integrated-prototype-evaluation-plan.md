# Integrated prototype: workspace setup and evaluation checklist

Prepared September 25, 2026; this integrated-repository copy is updated after the Section 7 assessment. Sections 3–6 and the assignment-coverage assessment are complete. The current checkpoint below supersedes the earlier preparation statuses; the original sibling's planning copy and historical evidence remain unchanged.

The evaluation target is [udp-file-transfer-integrated](https://github.com/mm038/udp-file-transfer-integrated), branch `feature/metrics-llm-integration`, based on `985b1a816fd772088b4dcc04d8796a759850feef` plus the preserved local repairs. The commit alone does not reproduce the evaluated implementation. Before this documentation cleanup, all 48 entries in the latest saved working-tree snapshot matched their hashes; the added plan copy brought the Git status to 49 entries (33 tracked modifications and 16 untracked files).

The existing repository is `udp-file-transfer`, branch `feature/LLM-integration`, at `e77dbd7`. Its old status documents describe earlier checkpoints. Its tests, synthetic answers and `target/` artifacts do not establish that the newly integrated prototype works.

The saved manual/paid evaluation has finished. The four earlier source-review findings have passing repair evidence; the tested working tree includes those repairs. Keep original failures and final reviews together rather than relying on old preparation/session-result labels.

After the documentation cleanup, the user approved a separate source-directory change: the console now selects files under `storage/outgoing`, matching the standalone sender. The receiver still publishes under `storage/incoming`. Current commands in the [walkthrough](live-demo-walkthrough.md) use the new source-root build; the saved A–D and Section 6 runs used the earlier `data/input` console root. Their original commands, build identities and review results remain unchanged. This follow-up does not repeat the paid evaluation or complete Person 4's experiment matrix.

The follow-up has [315 passing focused tests across 14 classes and a new packaged JAR](../target/evaluation/storage-outgoing-20260925-231608-232001/validation.json). Its fresh evidence directory retains the earlier JAR, the updated JAR, initial failure records and final results; all 1,843 checked prior evaluation/report files retained their hashes.

## Current checkpoint: ready for Person 4's experiments

Use the [README checkpoint](../README.md#current-evaluation-checkpoint) for final evidence links, [LOGGING.md](../LOGGING.md) for implemented metric definitions, and the [walkthrough](live-demo-walkthrough.md) for current launch settings. This plan is now the maintained copy in the integrated repository; the original sibling contains the earlier planning record.

| Work | Current status |
| --- | --- |
| Clone, workspace and Java/Maven setup | COMPLETE; Java/javac 17.0.8.1, Maven 3.9.16; JAVA_HOME explicitly corrected in the user's build terminal |
| Ordinary automated verification and JAR packaging | PASS: 762 tests across 56 classes, zero failures/errors/skips; finished September 25 at 12:06:12 Dubai |
| Integrated code/evidence-path inspection (section 3) | COMPLETE; missing RTT reasons, controlled impairment, API capture and DATA framing repairs have saved passing focused checks |
| Metric definitions and independent real-value checks (section 4; section 8 reference) | COMPLETE; real acceptance summaries/counters independently checked; use current LOGGING definitions over historical proposals |
| Manual real-transfer acceptance (section 5) | PASS: A–C successful with independently matching hashes; D expected bounded failure, FAILED / UNCONFIRMED |
| Paid model calls and actual prose review (section 6) | COMPLETE; A remains REQUIRES_CORRECTION; B–D passed assignment-minimum review with minor presentation caveats; clarification and unsupported-operation checks completed |
| Assignment coverage (section 7) | COMPLETE assessment: ready for Person 4's required experiments with documented limitations; no new experiment-blocking code fix identified |
| Experiments, handoff bundle and final deliverables (sections 7 and 9) | PENDING PERSON 4 / TEAM; documentation cleanup is not a shared bundle, finished report or recorded demonstration |

Saved build results in the integrated repository (the full suite was not repeated after each later focused repair):

- Successful log: `target/evaluation/integrated-retry-20260925-120422-068/maven-verify.log`.
- Surefire reports: `target/surefire-reports/TEST-*-integrated-retry-20260925-120422-068.xml`; all 56 report totals were read and checked.
- Packaged JAR: `target/udp-file-transfer.jar`.
- Preserved earlier environment failure: `target/evaluation/integrated-20260925-105050/maven-verify.log`. That attempt stopped before compilation/tests because Java could not be located.

The successful log's logger identity error, receiver initial timeout and `real-status: automatic=false` are expected negative-test cases, confirmed in the tests. Shade reports overlapping manifest/module metadata, but packaging succeeded; packaged entry points have since been exercised during acceptance. Do not rerun the successful full suite solely because these messages appeared.

The initial loss/delay gap is resolved by the tested `RECEIVE_DELIVERY_V1` shim: receiver DATA random loss and fixed DATA/ACK receive-delivery delay per direction, with control messages unaffected. A configured drop occurs after socket emission. See [the supported properties](../README.md#controlled-loss-and-delay) and [passing validation](../target/evaluation/controlled-impairment-20260925-134232-676/validation.json).

Retain A's original [REQUIRES_CORRECTION review](../target/evaluation/section5-run-a-32768-20260925-204658-930498/explanation-review.json): its valid measurements remain usable, but its prose misattributes combined emissions and makes unsupported claims. Do not regenerate A merely to begin experiments or present reviewer corrections as original output. Live integrity FAILED remains NOT EXERCISED. A–C use the same 262,267-byte file and settings, so they do not complete Person 4's small/large matrix or parameter comparison.

Current API settings are `gpt-5-mini`, `commands-v2` / `explanations-v6`, maximum two HTTP attempts per operation, interpretation cap 4096 and explanation cap 32768. Any later explicitly approved API work must enable `-Dnettransfer.evaluation.record=true` and preserve fresh captures. `OPENAI_REQUEST_TIMEOUT_MS=90000` limits an HTTP attempt; it is not a transfer deadline. The [latest saved ledger](../target/evaluation/section6-unsupported-prep-20260925-221110-460830/api-usage-and-cost.json) estimates $0.13104675 spent of $10, with $9.86895325 remaining, including the initial user-reported $0.08.

Next preparation is a concise handoff of the tested source snapshot, current commands/metric meanings, selected ignored evidence, limitations and Person 4's ownership. No new full-suite run, A–D replay, code fix or paid explanation is required by this assessment. Sections 1–6 below retain the setup and acceptance procedure as reference; the checkpoint and final evidence determine current status.

## 1. Clone beside the existing repository

The intended layout is:

```text
Assignment 1 Workspace/
  udp-file-transfer/                 existing repository and historical evidence
  udp-file-transfer-integrated/      new clone to evaluate
  udp-prototype-review.code-workspace
```

Both folders can appear in one VS Code window while keeping separate Git histories. This is a multi-root workspace; adding a folder does not merge repositories. See the [official VS Code guide](https://code.visualstudio.com/docs/editing/workspaces/multi-root-workspaces).

Run each step separately in PowerShell and check its result before continuing.

1. Go to the parent directory:

   ```powershell
   Set-Location -LiteralPath 'C:\Users\raaid\Desktop\fall 2026\Advanced Computer Networks\Assignments\Assignment 1\Assignment 1 Workspace'
   Test-Path -LiteralPath '.\udp-file-transfer-integrated'
   ```

   Expect `False` before the first clone. If it is `True`, inspect the existing folder before doing anything else; do not delete or overwrite it.

2. Clone the verified integration branch:

   ```powershell
   git clone --branch feature/metrics-llm-integration https://github.com/mm038/udp-file-transfer-integrated.git udp-file-transfer-integrated
   ```

   If Git requests authentication, use its normal GitHub sign-in flow; do not put tokens in the URL or chat. A failed clone is a stop point, not a reason to run the later steps. [Git clone documentation](https://git-scm.com/docs/git-clone).

3. Add the clone to this VS Code window, while the terminal is still in the parent directory:

   ```powershell
   code --add '.\udp-file-transfer-integrated'
   ```

   This targets the last active VS Code window. Alternatively, use **File > Add Folder to Workspace...** in the intended window and select the new folder. Explorer should show both repositories. Use **File > Save Workspace As...** to save `udp-prototype-review.code-workspace` in the parent directory, beside the repositories.

4. Enter and identify the new repository:

   ```powershell
   Set-Location -LiteralPath '.\udp-file-transfer-integrated'
   git rev-parse --show-toplevel
   git remote -v
   git branch --show-current
   git log -1 --oneline
   git status --short
   ```

   Expect the new folder, the `mm038/udp-file-transfer-integrated` remote, and branch `feature/metrics-llm-integration`. A fresh clone normally has no status output. Record the actual commit rather than assuming it is still `985b1a8`.

5. Stop for the first code inspection. Keep the integrated folder active for subsequent evaluation commands. Preserve the old repository and all its evidence. Do not copy its `target/`, JAR, local metrics or synthetic fixtures into the new clone as if they were integration results.

The original planning file remains in the old repository. This maintained integrated copy identifies the tested base commit plus local repairs; any shared handoff must include that evaluated working state and selected ignored evidence.

## 2. What counts as ready?

**Ready for Person 4's experiments:** the integrated build works; representative real transfers succeed and fail honestly; required measurements are produced with understood definitions; reviewed explanations are available with limitations identified; and reproduction instructions are usable. Section 7 found no new blocking code issue. A's original explanation remains a failed semantic review, not a blanket model-quality PASS.

**Assignment complete:** the required experiments, analysis, protocol document, evaluation report and demonstration are also finished. A merge, passing tests or this small acceptance exercise cannot establish that second status alone.

Use `PASS`, `FAIL`, `NOT EXERCISED` and `PENDING PERSON 4`, with evidence paths. A claimed implementation without a check stays unverified. Record limitations without turning unsupported behavior into a pass.

## 3. Integrated inspection reference (review complete)

The bullets in sections 3–6 record the review procedure, not outstanding tasks. Completion of a review does not mean every model answer passed; use the checkpoint and retained final reviews for outcomes.

- Read its `AGENTS.md` if present, README, build configuration, protocol document, metric definitions, integration notes and launch/evaluation scripts.
- Record repository URL, branch, full commit, Java/Maven versions and operating system: see the checkpoint/handoff (Windows 11, Java 17.0.8.1, Maven 3.9.16). Recheck Git state when resuming; use a separate evaluation branch if fixes are needed.
- Check that the merged explanation instructions retain the substance of `explanations-v5`; record actual command/explanation prompt versions and configured model. Keep `gpt-5-mini` for comparability.
- Trace an actual transfer through engine events, logger, finalized summary, real evidence provider, identity checks and the exact request sent to GPT. Confirm this works through the ordinary application entry point, not only a test helper.
- Check application run/transfer IDs, experiment label and wire ID association, endpoint attribution, real provenance, outcome/integrity reconciliation, units, field definitions and missing-value reasons.
- Inspect what the model actually receives. If it receives only summary counters, report that clearly. Logs on disk do not establish that GPT saw individual events or their order. The assignment permits citations from a summary or event log; raw-log ingestion itself is not mandatory.
- Confirm absent, incomplete, mismatched or stale real records produce an honest unavailable/rejected result. No synthetic fallback. If real evidence remains unavailable, fix or record that integration blocker before paying for an explanation.
- Identify actual receiver/CLI/impairment launch commands, approved file/receiver identifiers, port, output locations, restart behavior and transfer termination bounds. Use the integrated README/code, not assumed commands from the old repository.
- Inspect the runner's actual cases. Historically, `-Batch real` tested start/status and `-Batch explanations` used four synthetic fixtures. Reuse a runner only if it exercises the intended integrated route; otherwise use the real console and save evidence. Do not build a new evaluation framework merely for this check.
- **Check 9b: API retries and evaluation budget — COMPLETE (September 25, 2026).** The proposed persistent shared attempt/spending-limit feature was deemed unnecessary for this small, supervised evaluation; no budget-mode implementation is required or was made. The user accepts up to **US$10 in API spending for the entire project**, including earlier usage. Keep `gpt-5-mini` and the existing maximum of two HTTP attempts per GPT operation; a natural-language explanation uses interpretation plus explanation and can therefore reach four attempts if both retry. The ordinary console has no automatic project spending cutoff: inspect actual usage before starting and between small batches. Keep `OPENAI_REQUEST_TIMEOUT_MS=90000` for later explicitly approved evaluation using the existing configuration; it limits API requests, not transfers. This supersedes the initial ten-attempt ceiling and proposed single-attempt evaluation mode in earlier planning/handoff notes. Section 3 and the planned manual/paid checks are complete; new paid calls still require explicit approval.
- **Check 9c: API evidence capture — COMPLETE.** The console recorder is enabled with `-Dnettransfer.evaluation.record=true`; it preserves available exact requests/responses, rejected drafts labelled untrusted, per-attempt usage/timing/failures and Java decisions in fresh session folders. See [passing validation](../target/evaluation/api-evidence-capture-20260925-151307-190/validation.json). Check session completion and explicit omissions; UDP logs or a transcript alone are insufficient.
- Make a requirement-to-evidence table from section 7. Mark anything missing as an actionable gap.

## 4. Build, existing regression checks and metric definitions

- Inspect test/plugin configuration and run the integrated offline suite and packaging once: fresh 762 tests/56 classes passed with zero failures/errors/skips, using `mvn -o` and no paid calls. Do not replace this result with older 603/151 counts or repeat it without a new reason.
- Preserve the failed setup log and save the successful check in a new result folder with uniquely suffixed reports. No `mvn clean` was used. Continue preserving artifacts for every later run.
- Reuse existing checks for framing, bounds, CRC/file verification, cumulative ACKs, duplicate/out-of-order handling, retransmission/retry limits, peer/transfer-ID checks, control-message recovery and terminal failure. Add a focused check only for an actual coverage gap that affects readiness.
- Reuse Java validator checks for malformed commands, unknown files/receivers, path/address/port restrictions, numeric bounds, unsupported operations and invalid lifecycle transitions.
- Reuse explanation/provider checks for identity mismatch, malformed/incomplete records, null reasons, incorrect citations, tools, malformed API responses and timeouts. An API problem must not fabricate a network outcome.
- Use [LOGGING.md](../LOGGING.md) for the implementation-specific metric/event glossary and source locations; section 8 below retains the planning reference with current impairment corrections. Confirm definitions against actual output rather than assuming every earlier proposal became code.
- Verify calculations independently on a saved real run: delivered-payload throughput, resend fraction, emitted-byte overhead and RTT sample/percentile handling. These are reviewer calculations; GPT must still not invent absent metrics.
- Check at least one complete successful run has the required usable measurements. Universal nulls plus reasons are honest missingness, but do not establish that required instrumentation is implemented. Record specific RTT inapplicability where justified.

After inspecting the build, a typical Maven verification command is:

```powershell
$checkTag = 'integrated-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
mvn "-Dsurefire.reportNameSuffix=$checkTag" verify
```

Use the project's actual wrapper/build instructions if different. Add `-o` only when dependencies are available locally. Do not execute this merely because it appears in the plan; inspect the cloned build first.

## 5. Four representative real transfers

Use actual files, sender, receiver and real logging. A local impairment shim producing real packet events is real evidence; handwritten fixture numbers are synthetic. Use fresh output paths and preserve all raw records. One small acceptance set is enough initially; Person 4 performs the full experiment matrix later.

Choose a file large enough to span several windows and include a final partial chunk. Size is a team choice, not a prescribed assignment value. Use a larger file only if needed to make the active-status check or impairment visible. Record file size and source SHA-256.

| Run | Setup | What must be established |
| --- | --- | --- |
| A: baseline success | No intentional loss, low delay; start through natural language | Correct resolved Java command/settings; real transfer; sender success; independently matching source/received SHA-256; finalized real summary and machine-readable events |
| B: loss and recovery | Supported, documented seeded random DATA loss of at least 2%; same file/settings otherwise | Successful recovery and matching hash; actual configured loss plus observed events/counters; no assumption that one finite run's observed fraction equals its configuration |
| C: delayed path | Meaningful supported added delay or jitter with documented direction/message types; loss disabled to isolate delay | Successful transfer and accurate measurements; exercise active progress/status while genuinely running, then terminal status; note if the query arrives after completion |
| D: bounded failure | Supported safe failure condition, such as unavailable receiver or exhausted retries, selected after reading code | Finite termination; accurate failed/unconfirmed outcome and recorded reason if known; honest partial/absent receiver evidence; saved failure artifacts; no false success |

- Record seed, impairment placement/direction/message types, units, chunk/window/timeout/retry settings, unique run ID and file identities for each run.
- Set separate finite bounds for HTTP calls and transfers. Choose a reasonable transfer deadline after inspecting retry/control waits; do not confuse the 90-second API limit with a transfer limit. A transfer may outlive a timed-out API request; inspect status before issuing another start.
- Check active-status delivered bytes/throughput refer to actual observations. Sender ACK progress must not be relabelled as receiver-written bytes. A finished-run status alone does not validate active progress.
- Read both sender and receiver artifacts where needed. Distinguish send attempts, emitted datagrams, arrivals, accepted payload and verified contents.
- For each successful run, compare source and destination hashes independently, for example with `Get-FileHash -Algorithm SHA256 -LiteralPath <actual-path>`.
- Inspect at least a short linked event segment for attempts/ACKs/recovery and reconcile summary fields with it. Whole-log parsing is preferable where existing tools support it; do not demand every aggregate equal an unrelated counter.
- Confirm packet corruption detection/file mismatch, duplicate handling and control recovery through existing automated evidence. Add a targeted real scenario only if a material gap remains. Do not manufacture failures by editing saved measurements.
- Record whether a failure before DATA leaves some measurement fields unavailable. Test failure finalization; avoid requiring a successful-transfer denominator when no DATA was sent.

These four runs are acceptance checks, not enough to conclude that one window/timeout is faster or to make statistical reliability claims.

## 6. Small paid LLM evaluation and prose review

The table below preserves the original **nine-operation estimate before retries**, not a new authorized batch or the final usage total. Section 6 evaluation and prose review are complete. Keep the **US$10 overall project budget**, `gpt-5-mini`, maximum two HTTP attempts per operation, interpretation cap **4096** and explanation cap **32768**. Later approved API work uses `OPENAI_REQUEST_TIMEOUT_MS=90000` and `-Dnettransfer.evaluation.record=true`; the API timeout does not bound a transfer. The current ledger is linked in the checkpoint. The console has no automatic dollar cutoff; count interpretation, explanation and retries separately and inspect errors before paying again.

| Check | GPT operations before retries | Expected behavior |
| --- | ---: | --- |
| Incomplete start request | 1 | Asks for essential file/receiver information without executing; does not demand optional defaults |
| Supply the missing essentials and start A | 1 | Uses clarification context, returns structured command, Java validates, and exactly one real transfer starts |
| Natural-language active status during C | 1 | Selects the intended active transfer and displays supported real measurements; starts nothing |
| Natural-language explanation of A | 2 | Interpretation selects the run, then the separate explanation call uses that run's finalized real evidence |
| Direct structured explanation of B | 1 | Accurately explains configured loss, actual counters and observed recovery without invented causes |
| Direct structured explanation of C | 1 | Explains measured timing/RTT and limitations without treating configured delay as measured RTT |
| Direct structured explanation of D | 1 | Preserves actual failure/integrity and missing values; unknown cause remains unknown |
| Unsupported natural-language operation | 1 | Refuses the operation and causes no unsupported execution |

The two-call natural-language explanation route and one-call direct explanation route are expectations from the existing architecture; verify them in the clone. Directly start B/C/D without paid interpretation. Combine several evidence questions into each explanation rather than making one request per metric. This small merged-system acceptance justifies rechecking representative command behavior without replaying every earlier paid batch.

- Demonstrate deterministic rejection separately with a malformed/out-of-range structured command or the existing injected runner test; it needs no paid request. A model's refusal alone does not prove Java would reject an invalid proposal.
- Save actual prompts, exact model-visible analysis input, returned model/version, output, Java decision, attempt count, token usage and API latency. Keep API latency separate from transfer duration and omit credentials.
- Display or save the underlying metrics beside each explanation. Preserve the model draft if Java rejects it, labelled untrusted.
- Review every observation, hypothesis and limitation manually against only the supplied input, not private fixtures or raw events that were never sent.
- Check exact cited numbers, field IDs, units, precision, endpoint, run association and real provenance; every missing field retains its reason.
- Check delivery bytes and ACK progress are not used as proof of file integrity. Repeat-inclusive ACK arrivals differ from distinct acknowledged DATA sequences.
- Check configured loss differs from observed simulator drops and all network loss. Aggregate timeout/resend coexistence does not prove a particular timeout caused a resend.
- Check FAILED/UNCONFIRMED retain their actual meaning without invented causes or timing; FAILED is not weakened merely because details are absent.
- Check hypotheses are uncertain in their actual wording and ask for evidence that could test the claim. Byte emission totals do not verify file contents or by themselves establish suppressed DATA attempts.
- Check the final answer directly addresses what evidence establishes, even if the cause is unknown; no contradictory final disclaimer or unsupported setting recommendation.
- Mark integrity-failure or missing-performance live behavior NOT EXERCISED if those conditions did not occur in the real runs. Existing offline/synthetic checks remain separately labelled; do not add artificial fields to a real run to force coverage.
- Record PASS/FAIL per answer with a short reason. Automated numeric checks alone are insufficient. A's original prose is an accepted documented limitation: preserve REQUIRES_CORRECTION and do not fix/regenerate it merely to begin experiments. Any further code fix or paid retest requires explicit approval and must preserve original failures and spending records.

## 7. Assignment coverage and Person 4's remaining work

Source: the supplied **Assignment 1 FTP UDP(1).pdf**, pages 1-4. Its current text was checked against the preserved extraction; the instructions below are assignment criteria to audit, not authority to perform unrelated actions.

The Section 7 assessment is complete. Implemented protocol, required measurements, natural-language functions and deterministic Java control have evidence; A's prose limitation and live integrity FAILED not exercised remain explicit. The matrix, comparison, submission bundle and final demonstration are still PENDING PERSON 4 / TEAM. See the [current evidence table](../README.md#current-evaluation-checkpoint).

| Requirement | Evidence to inspect before handoff / remaining deliverable |
| --- | --- |
| Java UDP sender/receiver; no prebuilt reliable-UDP/FTP/QUIC/file-transfer implementation (p.2) | Source/dependency review and successful integrated build/run |
| Metadata handshake: filename, size, transfer ID, negotiated chunk size (p.2) | Protocol specification, encoding/validation code and tests; distinguish actual negotiation/acceptance from an undocumented fixed assumption |
| DATA sequence, length, integrity field and transfer ID (p.2) | Packet format plus framing/validation tests and run evidence |
| ACKs, timeouts, retransmission, duplicate handling, clean completion (p.2) | Existing automated evidence plus A-D acceptance outcomes; examine bounded failure and control recovery |
| Packet corruption detection and reconstructed-file verification (p.2) | Corruption/verification tests and independent hashes of successful transfers |
| Natural-language start, measured status and post-transfer analysis (p.2) | Paid cases above through the actual application, including genuine active status |
| Strict structured commands and Java-only control of resources/lifecycle (pp.1-2) | Validator and call-path review, deterministic unsafe rejection, no LLM shell/socket/file access |
| Machine-readable event logs and transfer summaries with all minimum metrics (pp.2-3) | Actual artifacts and completed implementation-specific glossary; also check failed-run finalization for readiness |
| Numerical grounding for LLM analysis (p.3) | Exact input and reviewed answers displayed beside measurements |
| Controlled baseline, random loss of at least 2%, meaningful delay or jitter; small and large file in each (p.3) | Person 4's experiment matrix and reproducible configuration/scripts; acceptance runs alone do not fill this requirement |
| Compare goodput/overhead, loss/recovery effects, and timeout/window choices (p.3) | Controlled comparisons and evidence-based discussion, not one-run claims |
| Source and build/run instructions (p.3) | Pinned working revision, tested commands, documented dependencies/configuration |
| Protocol specification, 2-4 pages (p.3) | Packet formats, state machine, timeout/retry policy, validation boundary; sequence diagram including normal exchanges and a loss/timeout/retransmission path |
| Evaluation report, 4-6 pages (p.3) | Person 4/team: setup, figures/tables, interpretation, limitations and one improvement proposal |
| Three or more raw metric logs plus reproduction commands/scripts (p.3) | Preserve/share actual raw artifacts. An ignored local folder or summary prose alone is not a submitted log |
| Recorded 5-8 minute demonstration or instructor-specified live demo (p.3) | Natural-language successful transfer, impaired transfer with metrics, explanation beside evidence, deterministic invalid-command rejection |
| Disclosure and explainable code (p.4) | Identify model/tool roles and third-party libraries; team can explain implementation and metrics under course policy |

### Minimal experiment plan for Person 4

The PDF requires small and large files in all three scenarios: at least **six file/scenario combinations**. It does not prescribe exact file sizes, three repeats, a particular RTT percentile, JSON field names, or a numerical target for goodput.

| Scenario | File coverage | Configuration / comparison |
| --- | --- | --- |
| Baseline | Small + large | Low delay, no intentional loss; record goodput and overhead |
| Lossy path | Same small + large | At least 2% random loss, documented location/seed; keep other settings comparable to baseline |
| Delayed/variable path | Same small + large | Meaningful delay or jitter; compare two documented timeout OR window settings, changing one factor at a time |

Using two settings for both delayed-path file sizes yields a compact **eight-run plan** (2 baseline + 2 loss + 4 delay). Eight is our practical proposal for answering the performance-choice question, not an explicit instructor minimum. One run per configuration supports a limited demonstration, not statistical conclusions; repetitions can be added if required by the instructor or unexplained variability. Preserve run failures as results.

- [ ] Choose and document exact file sizes/contents and hashes; assignment does not define small/large thresholds.
- [ ] Provide scenario commands/configs, seed support, timing boundaries, output paths and a results-table template.
- [ ] Identify the measured goodput, overhead, retransmission and RTT fields for plots; separate configuration from observation.
- [ ] Explain how to vary one parameter, restart/reset endpoints, avoid output collisions, recognize failure and preserve failed-run artifacts.
- [ ] Label which experiments/report/demo items Person 4 owns and which still need team input. Do not call the whole assignment complete while these remain pending.

## 8. Metric guide to reconcile with the integrated producer

The following meanings come from the team's accepted earlier metric scope. They remain a **planning reference**; the maintained implementation-specific glossary is [LOGGING.md](../LOGGING.md), including nullable configuration, event types and current receive-side impairment. Some earlier definitions were proposals. The three simulator-related rows below are corrected to avoid applying the old outgoing-drop assumption to actual runs.

For each field, record: plain-language purpose, unit, source endpoint/event, counting/timing boundary, formula/denominator if applicable, zero/null meaning, behavior on failure, actual code location, and an example real value with its evidence path. Treat a zero as an observed zero; a missing measurement needs a reason. Check the real producer's distinction between integrity booleans and the application's integrity enum.

### Delivery and performance

| Field | Meaning and purpose | Important limit |
| --- | --- | --- |
| `file_size_bytes` | Original input size; tells us how much useful data was intended | Not a measurement of received bytes |
| `payload_bytes_delivered` | Unique payload accepted/written by the receiver, excluding repeated copies | Delivery is not proof of correct contents; needs receiver evidence |
| `transfer_time_sec` | Elapsed protocol time; earlier proposal was first sender START attempt to terminal decision, measured monotonically | Verify actual boundaries; exclude LLM/API time |
| `throughput_mbps` | Useful delivery rate, proposed `delivered_bytes * 8 / seconds / 1,000,000` | This is payload goodput; a failed-run partial rate is not successful-file throughput; unavailable/zero duration prevents this calculation |

### Packet activity

| Field | Meaning and purpose | Important limit |
| --- | --- | --- |
| `packets_sent` | Sender DATA attempts including resends, counted before socket send | Actual emissions are separate; the current receiver-side drop occurs after emission |
| `packets_received` | Receiver DATA arrivals, including duplicates/out-of-order arrivals | Arrival does not imply valid, accepted or written payload |
| `packets_dropped` | Eligible DATA arrivals deliberately dropped by the receiver's enabled simulator before engine delivery | Includes eligible retransmitted DATA; those datagrams were already emitted; not all network loss |
| `retransmissions` | DATA attempts after a sequence's first attempt | A recovery round can resend many packets; not a timeout count |
| `acks_received` | Sender DATA-ACK arrivals, including repeats; excludes START/FINISH ACKs under the earlier definition | Not unique progress or delivered bytes |
| `packets_acked` | Distinct DATA sequences confirmed through valid cumulative ACK progress | A single ACK can advance several sequences; repeated ACKs add none |
| `packets_timed_out` | Detected DATA deadline expirations; earlier Go-Back-N meaning counts the expired trigger | Not every packet resent and not socket polling timeouts; includes retry-limit-ending detection |
| `packets_duplicated` | Valid DATA arrivals whose sequence was already accepted at the receiver | An ahead-of-gap discard is different from a duplicate |
| `retransmission_ratio` | Proposed resend fraction `retransmissions / packets_sent` | Ratio 0.10 means 10% of attempts were resends, not 10% network loss; denominator must be documented |

### Byte accounting and RTT

| Field | Meaning and purpose | Important limit |
| --- | --- | --- |
| `udp_payload_bytes_emitted` | Actual UDP payload emissions from both endpoints: encoded DATA, ACK/control traffic and retransmissions | Includes DATA later dropped by the receive-side shim; excludes IP/UDP/link headers; count each emission once |
| `protocol_overhead_bytes` | Proposed `udp_payload_bytes_emitted - payload_bytes_delivered`: traffic beyond unique useful delivery | Requires compatible complete endpoint accounting; includes retransmitted payload under this definition |
| `protocol_overhead_ratio` | Proposed `protocol_overhead_bytes / udp_payload_bytes_emitted` | Denominator is emitted bytes, not file size; undefined for zero/incomplete emission evidence |
| `rtt_sample_count` | Number of usable DATA-to-ACK round-trip samples | Zero usable samples differs from unavailable sampling evidence |
| `rtt_mean_ms` | Arithmetic mean of valid sampled RTTs | Check send/ACK association, cumulative ACK selection and exclusion of ambiguous retransmitted samples |
| `rtt_p95_ms` | Latency at the 95th percentile; earlier proposal used nearest rank `ceil(0.95 * n)` after sorting | Check actual percentile rule and sample count; configured delay and transfer duration are not RTT |

### Configuration: what was requested/applied, not measured performance

| Field | Meaning and purpose | Important limit |
| --- | --- | --- |
| `chunk_size_bytes` | Effective DATA payload capacity per packet | Check negotiated/accepted value; final chunk may be smaller |
| `window_bytes_requested` | Resolved byte budget, including any Java default | Distinguish explicit user choice from default |
| `window_packets` | Effective maximum outstanding DATA packet slots | Check byte-to-slot rounding and whether the protocol counts outstanding data as expected |
| `timeout_ms` | Configured DATA retransmission deadline | Not measured RTT and not the API timeout |
| `retry_limit` | Earlier meaning: consecutive recovery rounds without progress | Not total DATA resends or necessarily retries per packet |
| `packet_loss_rate` | Configured simulator loss percentage, 0-100 | A configuration, not an observed fraction; document affected direction/types |
| `delay_ms` | Configured fixed receive-delivery delay per direction for receiver DATA and sender ACK | Not RTT or jitter; control messages bypass the shim |
| `scenario` | Label linking the run to the complete impairment configuration | Label alone is insufficient to reproduce an experiment |
| `impairment_seed` | Random-generator seed when supported | Reproducibility aid; does not guarantee identical OS scheduling/network timing |

### Identity, outcomes and supporting metadata

| Field/group | Meaning and purpose | Important limit |
| --- | --- | --- |
| `experiment_id` | External experiment label | Must map to the actual application/wire run; not necessarily a UUID |
| `run_id`, `transfer_id`, `protocol_transfer_id` | Application selection and wire-transfer association | Verify mappings; missing wire identity cannot be invented |
| `transfer_success` | Sender-confirmed protocol completion | Receiver completion alone may not establish sender confirmation |
| `integrity_verified` | Earlier proposed boolean: true = checked match, false = checked mismatch, null = no available check | Verify actual producer semantics; the application's FAILED enum alone may not identify a specific mismatch mechanism |
| `state`, `integrity` | Application lifecycle and verification outcome supplied to the explanation | Delivery, sender completion and receiver verification are distinct facts; unknown reason/timing stays unknown |
| `failure_reason` | Recorded failure explanation where available | Do not infer it from aggregate counters |
| `evidence_source` | REAL versus SYNTHETIC provenance | A real claim requires genuine records; local controlled impairments can still produce real evidence |
| `schema_version`, `metric_definition_version` | Identify record layout and field meanings | Same field name can hide changed counting semantics; inspect compatibility |
| Endpoint, file identity and capture/finalization time | Attribute observations and establish which finalized run/file they describe | Different endpoints' wall clocks do not automatically provide valid elapsed-time differences |
| `unavailable_reasons` | Explains each missing/inapplicable measurement | Null is not zero; unsupported required instrumentation is a readiness gap |

Event logs also need a short field guide: actual event types, monotonic/UTC timestamps, endpoint/direction, message type, sequence/cumulative ACK, attempt index, byte lengths, validation/acceptance/drop/timeout outcomes and identities wherever implemented. These were suggested baseline fields, not a mandatory exact log schema. Explain every additional actual field found during inspection.

## 9. Handoff decision and small evidence bundle

- [ ] Supply the tested repository/branch/commit, Java/Maven setup, receiver/CLI/impairment commands, approved resources and output-location rules.
- [ ] Include the updated requirement-to-evidence checklist and complete actual metric/event glossary, separating PASS, FAIL, NOT EXERCISED and PENDING PERSON 4.
- [ ] Preserve A-D configurations, raw events, summaries, hashes, API request/response evidence, usage and prose reviews in separate uniquely named run folders. Share selected ignored artifacts explicitly; Git clone will not copy local ignored evidence.
- [ ] Include a concise defect list with reproduction steps, severity/owner, and whether it blocks experiments. Examples of blockers: false success, hash mismatch reported as verified, missing required logging, wrong-run evidence, unsafe dispatch, unbounded transfer failure, or materially misleading explanations.
- [ ] Give Person 4 the small/large three-scenario plan and instructions for the paired delay-setting comparison; leave full experiment execution/report authorship with the agreed owner.
- [ ] Verify the run instructions once from the cloned project. This is a handoff rehearsal, not a claim that Person 4 has independently reproduced them.
- [ ] Declare either READY FOR EXPERIMENTS with explicit remaining deliverables, or NOT READY with concrete blockers. Do not describe pending experiments/report/demo as completed.

No broad rewrite, model change, new benchmark framework, extra fault campaign or automatic commit/push is part of this preparation. Explain any newly found defect and obtain approval before code fixes; no such fix or repeat campaign is needed for the completed Section 7 assessment.
