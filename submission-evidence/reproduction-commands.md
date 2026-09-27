# Reproduction Commands

These commands reproduce the representative small-file experiments used in the evaluation.

## Prerequisites

Run from the project root:

```bash
export JAVA_HOME="/opt/homebrew/opt/openjdk"
export PATH="$JAVA_HOME/bin:$PATH"

PROJECT_ROOT="$PWD"
JAR="$PROJECT_ROOT/target/udp-file-transfer.jar"
```

The sender and receiver must be run in separate terminals.

The representative test file is approximately 256 KB.

---

# 1. Baseline Experiment

Configuration:

- Loss: 0%
- Delay: 0 ms
- Window: 8192 bytes (8 packets)
- Timeout: 500 ms
- Seed: 42

## Terminal 1 — Receiver

```bash
cd "$PROJECT_ROOT"

java \
  -Dnettransfer.impairment.enabled=true \
  -Dnettransfer.impairment.lossPercent=0 \
  -Dnettransfer.impairment.delayMs=0 \
  -Dnettransfer.impairment.seed=42 \
  -Dnettransfer.impairment.scenario=baseline \
  -Dnettransfer.logsRoot="$PWD/logs" \
  -Dnettransfer.receiverInitialTimeoutMs=240000 \
  -Dnettransfer.receiverInactivityTimeoutMs=15000 \
  -jar "$JAR" receiver 9000 received.bin
```

## Terminal 2 — Console

```bash
cd "$PROJECT_ROOT"

java \
  -Dnettransfer.impairment.enabled=true \
  -Dnettransfer.impairment.lossPercent=0 \
  -Dnettransfer.impairment.delayMs=0 \
  -Dnettransfer.impairment.seed=42 \
  -Dnettransfer.impairment.scenario=baseline \
  -Dnettransfer.evaluation.record=true \
  -cp "$JAR" nettransfer.cli.TransferCliMain \
  "$PWD" \
  'sample=storage/outgoing/person4-small.bin'
```

At the `transfer>` prompt:

```text
start_transfer {"file_id":"sample","receiver_id":"receiver-a","window_bytes":8192,"timeout_ms":500}
```

After completion:

```text
status last
```

---

# 2. Two-Percent Packet-Loss Experiment

Configuration:

- Loss: 2%
- Delay: 0 ms
- Window: 8192 bytes (8 packets)
- Timeout: 500 ms
- Seed: 42

## Terminal 1 — Receiver

```bash
cd "$PROJECT_ROOT"

java \
  -Dnettransfer.impairment.enabled=true \
  -Dnettransfer.impairment.lossPercent=2 \
  -Dnettransfer.impairment.delayMs=0 \
  -Dnettransfer.impairment.seed=42 \
  -Dnettransfer.impairment.scenario=loss-2pct \
  -Dnettransfer.logsRoot="$PWD/logs" \
  -Dnettransfer.receiverInitialTimeoutMs=240000 \
  -Dnettransfer.receiverInactivityTimeoutMs=15000 \
  -jar "$JAR" receiver 9000 received.bin
```

## Terminal 2 — Console

```bash
cd "$PROJECT_ROOT"

java \
  -Dnettransfer.impairment.enabled=true \
  -Dnettransfer.impairment.lossPercent=2 \
  -Dnettransfer.impairment.delayMs=0 \
  -Dnettransfer.impairment.seed=42 \
  -Dnettransfer.impairment.scenario=loss-2pct \
  -Dnettransfer.evaluation.record=true \
  -cp "$JAR" nettransfer.cli.TransferCliMain \
  "$PWD" \
  'sample=storage/outgoing/person4-small.bin'
```

At the `transfer>` prompt:

```text
start_transfer {"file_id":"sample","receiver_id":"receiver-a","window_bytes":8192,"timeout_ms":500}
```

After completion:

```text
status last
```

---

# 3. 200 ms Delay Experiment

Configuration:

- Loss: 0%
- Fixed delay: 200 ms per direction
- Window: 8192 bytes (8 packets)
- Timeout: 500 ms
- Seed: 42

## Terminal 1 — Receiver

```bash
cd "$PROJECT_ROOT"

java \
  -Dnettransfer.impairment.enabled=true \
  -Dnettransfer.impairment.lossPercent=0 \
  -Dnettransfer.impairment.delayMs=200 \
  -Dnettransfer.impairment.seed=42 \
  -Dnettransfer.impairment.scenario=delay-200ms \
  -Dnettransfer.logsRoot="$PWD/logs" \
  -Dnettransfer.receiverInitialTimeoutMs=240000 \
  -Dnettransfer.receiverInactivityTimeoutMs=15000 \
  -jar "$JAR" receiver 9000 received.bin
```

## Terminal 2 — Console

```bash
cd "$PROJECT_ROOT"

java \
  -Dnettransfer.impairment.enabled=true \
  -Dnettransfer.impairment.lossPercent=0 \
  -Dnettransfer.impairment.delayMs=200 \
  -Dnettransfer.impairment.seed=42 \
  -Dnettransfer.impairment.scenario=delay-200ms \
  -Dnettransfer.evaluation.record=true \
  -cp "$JAR" nettransfer.cli.TransferCliMain \
  "$PWD" \
  'sample=storage/outgoing/person4-small.bin'
```

At the `transfer>` prompt:

```text
start_transfer {"file_id":"sample","receiver_id":"receiver-a","window_bytes":8192,"timeout_ms":500}
```

After completion:

```text
status last
```

---

# Evidence Included

Raw evidence for the three representative experiments is stored under:

```text
raw-logs/baseline/
raw-logs/loss-2pct/
raw-logs/delay-200ms/
```

Each directory contains:

- `events-sender.jsonl`
- `events-receiver.jsonl`
- `summary.jsonl`

The `summary.jsonl` file is the reconciled sender-and-receiver summary for the corresponding experiment.
