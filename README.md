# LLM-Assisted Reliable File Transfer over UDP

**Group members**

| Student name | Student ID |
| --- | --- |
| Moza Alhmoudi | 100062840 |
| Raaida Mahbub | 100063481 |
| Mariam Almarzooqi | 100060686 |
| Amna Albahrani | 100070037 |

Assignment 1 submission: a Java implementation of reliable file transfer over UDP, with cumulative acknowledgements, Go-Back-N retransmission, CRC32 packet checks, and final SHA-256 file verification. A command console supports natural-language requests and explanations of recorded metrics; Java validates commands before executing transfers.

This README is the submission index. The source, protocol document, evaluation report, four demonstration videos, screenshot evidence, seven-run results, figures, and three representative raw-log sets are included below.

## Required deliverables

| Assignment deliverable | Location and contents |
| --- | --- |
| **Source code and build/run instructions** | [Java source](src/main/java/nettransfer/), [automated tests](src/test/java/nettransfer/), and [Maven build configuration](pom.xml). See the separate [Build and run guide](docs/build-and-run.md) for prerequisites, building/testing, receiver/sender and console launches, logging, and experiment reproduction. |
| **Protocol specification (2-4 pages)** | [Protocol Specifications.pdf](docs/Protocol%20Specifications.pdf): packet formats, sender/receiver state machines, timeout/retry policy, deterministic LLM validation boundary, and a sequence diagram covering normal operation and loss recovery. [PROTOCOL.md](PROTOCOL.md) supplies additional wire-format and implementation details. |
| **Evaluation report (4-6 pages)** | [Evaluation report.pdf](docs/Evaluation%20report.pdf) covers the setup, seven-run results, figures, analysis, limitations, and possible improvements. Supporting setup evidence, results, raw logs, and figures are indexed below. |
| **Three or more raw metric logs and reproduction commands** | [Submitted raw logs](submission-evidence/raw-logs/) contain sender events, receiver events, and a reconciled summary for baseline, 2% loss, and 200 ms delay. [Saved reproduction commands](submission-evidence/reproduction-commands.md) cover these three representative runs; the [seven-experiment reproduction guide](docs/build-and-run.md#reproduce-all-seven-experiments) covers the full matrix. |
| **Recorded demonstration (5-8 minutes), or live demonstration as specified by the instructor** | All four recordings are linked under [Demonstrations](#demonstrations) and in the [video index](docs/videos/README.md). [LLM-Assisted Transfer and Demonstration Evidence.pdf](docs/LLM-Assisted%20Transfer%20and%20Demonstration%20Evidence.pdf) supplies accompanying screenshots for all four required points. |

## Documentation

| Document | What to review |
| --- | --- |
| [Protocol Specifications.pdf](docs/Protocol%20Specifications.pdf) | Main protocol submission, including the reliability design, validation boundary, and sequence diagram. The standalone sender defaults to a one-packet window; the experiments use the configurable console with eight- and sixteen-packet windows. |
| [Evaluation report.pdf](docs/Evaluation%20report.pdf) | Experimental setup, results and analysis of all seven experiments, three comparison figures, limitations, and possible improvements. Supported by the two experimental evidence PDFs, master results CSV, and report figures linked here. |
| [Experimental Evaluation Evidence.pdf](docs/Experimental%20Evaluation%20Evidence.pdf) | 25 figures documenting test/build validation, input-file generation, configuration, transfers, and results for the baseline, loss, delay, and window-size experiments. |
| [Experimental Results and Analysis Evidence.pdf](docs/Experimental%20Results%20and%20Analysis%20Evidence.pdf) | Six figures documenting preservation of the seven runs, extraction into the master CSV, figure generation, comparisons across conditions, and final integrity checks. This and the preceding document support the evaluation report. |
| [LLM-Assisted Transfer and Demonstration Evidence.pdf](docs/LLM-Assisted%20Transfer%20and%20Demonstration%20Evidence.pdf) | 21 figures documenting natural-language transfer initiation, completion and hashes, reconciliation, metrics-grounded LLM explanations, invalid-command rejection, and a successful transfer under 2% packet loss. |
| [Build and run guide](docs/build-and-run.md) | Current build/run instructions, moved out of this README, including experiment setup and reproduction. |
| [Logging and reconciliation reference](LOGGING.md) | Implemented JSONL schemas, measurement definitions, impairment observations, endpoint finalisation, and reconciliation rules. Use this to interpret the submitted logs and summaries. |
| [Protocol implementation reference](PROTOCOL.md) | DATA/ACK binary layout, size and sequence rules, CRC coverage, handshakes, and related engine behaviour. |
| [Metrics summary and design notes](docs/Metrics_Summary_Revised.md) | Accepted metric inventory, units, provenance, and missing-value conventions; includes historical design discussion. Current implemented formulas are in `LOGGING.md`. |
| [Extended PowerShell walkthrough](docs/live-demo-walkthrough.md) | Detailed file preparation, per-run capture, transfer/status, hash verification, reconciliation, explanation, and experiment workflow. |
| [Maven setup notes](docs/maven-setup.md) | Additional Windows/Maven setup guidance and the original development-environment notes. |

The older supplementary Markdown notes retain historical development checkpoints and local artifact references. Use the build/run guide above for a fresh checkout, and the submission evidence linked here for assessment.

<a id="current-evaluation-checkpoint"></a>

## Experimental evaluation evidence

Read the [evaluation report](docs/Evaluation%20report.pdf) for the analysis and interpretation of the experiments. The supporting dataset, raw logs, reproduction commands, and figures follow.

The [master experiment results CSV](person4-experiment-results.csv) contains **seven runs**, all recorded as successful with verified file integrity. The small file is **262,267 bytes**; the large file is **2,097,275 bytes**. All seven use 1,024-byte chunks and a 500 ms DATA timeout.

| Experiment | File | Configured impairment | Window |
| --- | --- | --- | --- |
| Small baseline | Small | No intentional loss or added delay | 8 packets / 8,192 bytes |
| Large baseline | Large | No intentional loss or added delay | 8 packets / 8,192 bytes |
| Small loss | Small | 2% random DATA loss, no added delay | 8 packets / 8,192 bytes |
| Large loss | Large | 2% random DATA loss, no added delay | 8 packets / 8,192 bytes |
| Small delay | Small | 200 ms per direction, no intentional loss | 8 packets / 8,192 bytes |
| Large delay | Large | 200 ms per direction, no intentional loss | 8 packets / 8,192 bytes |
| Large delay, larger window | Large | 200 ms per direction, no intentional loss | 16 packets / 16,384 bytes |

The CSV records configuration, completion time, delivered-payload throughput, packet counts, retransmissions, timeouts, overhead, RTT statistics, and success/integrity outcomes. Additional counters and payload-delivery fields are available in the raw event logs and reconciled summaries. Configured loss/delay and observed drops/RTT are distinct measurements; see [LOGGING.md](LOGGING.md) for their definitions.

### Raw logs and reproduction

These are the **three representative small-file runs** included as raw submission evidence. The CSV and PDF evidence documents cover the full seven-run evaluation.

| Scenario | Sender event log | Receiver event log | Reconciled metrics |
| --- | --- | --- | --- |
| Baseline | [events-sender.jsonl](submission-evidence/raw-logs/baseline/events-sender.jsonl) | [events-receiver.jsonl](submission-evidence/raw-logs/baseline/events-receiver.jsonl) | [summary.jsonl](submission-evidence/raw-logs/baseline/summary.jsonl) |
| 2% DATA loss | [events-sender.jsonl](submission-evidence/raw-logs/loss-2pct/events-sender.jsonl) | [events-receiver.jsonl](submission-evidence/raw-logs/loss-2pct/events-receiver.jsonl) | [summary.jsonl](submission-evidence/raw-logs/loss-2pct/summary.jsonl) |
| 200 ms delay per direction | [events-sender.jsonl](submission-evidence/raw-logs/delay-200ms/events-sender.jsonl) | [events-receiver.jsonl](submission-evidence/raw-logs/delay-200ms/events-receiver.jsonl) | [summary.jsonl](submission-evidence/raw-logs/delay-200ms/summary.jsonl) |

- [Saved reproduction commands](submission-evidence/reproduction-commands.md): Bash/macOS-oriented receiver and console commands for the three small-file scenarios.
- [Seven-experiment reproduction guide](docs/build-and-run.md#reproduce-all-seven-experiments): test-file preparation, launch commands, and settings for every experiment.
- [Extended experiment walkthrough](docs/live-demo-walkthrough.md#11-repeat-for-person-4s-required-experiments): the original PowerShell experiment matrix and capture workflow.

Submitted evidence lives in `submission-evidence/raw-logs/`. The top-level `logs/` directory is generated during execution and is not a submitted evidence folder. The copied JSONL files are event and summary exports; a new reconciliation uses the complete endpoint run directories generated by a fresh run, as described in the build/run guide.

### Report figures

| Figure | Description |
| --- | --- |
| [Throughput comparison](report-figures/figure-throughput.png) | Small- and large-file delivered-payload throughput under baseline, 2% loss, and 200 ms delay. |
| [Retransmission comparison](report-figures/figure-retransmissions.png) | Retransmission counts for both file sizes across the three network conditions. |
| [Window-size comparison](report-figures/figure-window-comparison.png) | Large-file throughput under 200 ms delay with eight- versus sixteen-packet windows. |

These figures were generated from the experiment results; the extraction and plotting evidence appears in [Experimental Results and Analysis Evidence.pdf](docs/Experimental%20Results%20and%20Analysis%20Evidence.pdf).

## Demonstrations

The four recordings are available in [docs/videos/](docs/videos/README.md), with a combined duration of approximately **5 minutes 29 seconds**. Open or download each recording using the links below. Accompanying screenshot evidence is in [LLM-Assisted Transfer and Demonstration Evidence.pdf](docs/LLM-Assisted%20Transfer%20and%20Demonstration%20Evidence.pdf).

| Required demonstration | Accompanying screenshot evidence | Recording |
| --- | --- | --- |
| Successful transfer initiated through a natural-language command | Figures 1-7: setup, accepted command, real transfer, status, SHA-256 verification, and reconciled metrics. | [Video 1: Natural-language transfer](docs/videos/Successful%20File%20Transfer%20Initiated%20via%20Natural-Language%20Command.mov) |
| Loss or delay scenario and the collected metrics | Figures 16-21: controlled 2% DATA loss, successful completion, drops, retransmissions, timeouts, and reconciliation. | [Video 2: Packet loss and collected metrics](docs/videos/File%20Transfer%20Under%202_%20Simulated%20Packet%20Loss%20%2B%20Collected%20Metrics%20.mov) |
| LLM explanation with the underlying metrics displayed beside it | Figures 8-12: explanation request and measured performance/overhead analysis; Figures 6-7 supply the underlying reconciled metrics. | [Video 3: Metrics-grounded explanation](docs/videos/LLM%20Explanation%20Grounded%20in%20Recorded%20Transfer%20Metrics%20.mov) |
| Invalid or unsafe command rejected by the deterministic validator | Figures 13-15: a 1 ms timeout rejected as `INVALID_PARAMETER`, with no new transfer started. | [Video 4: Invalid-command rejection](docs/videos/Deterministic%20Rejection%20of%20an%20Invalid%20Command.MOV) |

The [video index](docs/videos/README.md) also lists each recording and its associated demonstration point.

## Source and dependencies

| Area | Location |
| --- | --- |
| Sender, receiver, windows, ACK handling, and retransmission | [transfer/](src/main/java/nettransfer/transfer/) |
| Packet encoding/validation and UDP transport/impairment | [protocol/](src/main/java/nettransfer/protocol/), [net/](src/main/java/nettransfer/net/) |
| File handling and SHA-256 integrity | [storage/](src/main/java/nettransfer/storage/), [integrity/](src/main/java/nettransfer/integrity/) |
| Console, deterministic command validation, and transfer lifecycle | [cli/](src/main/java/nettransfer/cli/), [control/](src/main/java/nettransfer/control/) |
| Instrumentation, logging, summaries, and reconciliation | [metrics/](src/main/java/nettransfer/metrics/) |
| LLM command interpretation and evidence-based explanation | [llm/](src/main/java/nettransfer/llm/), [explanation/](src/main/java/nettransfer/explanation/) |
| Unit and integration tests | [src/test/java/nettransfer/](src/test/java/nettransfer/) |

The project targets **Java 17** and uses **Maven**. Third-party dependencies declared in [pom.xml](pom.xml) are **Gson 2.11.0** for JSON and **JUnit Jupiter 5.10.2** for testing. The integrated LLM client defaults to **OpenAI `gpt-5-mini`**, used to propose structured commands from natural language and explain validated recorded metrics. Java controls resource selection, parameter bounds, transfer execution, and evidence validation. Direct transfers and structured start/status commands work without an API key.
