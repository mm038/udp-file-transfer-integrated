package nettransfer.explanation;

import nettransfer.control.*;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Authored test data only: no files, network observations, calculations, or agreed metric schema. */
public final class SyntheticExplanationFixtures {
    public static final Instant CAPTURED_AT = Instant.parse("2026-09-20T08:00:00Z");

    private SyntheticExplanationFixtures() { }

    public record Fixture(RecordedSummary evidence, ExplanationDraft draft,
                          TransferState state, IntegrityStatus integrity) {
        public TransferSummary selected() {
            var request = new TransferRequest(UUID.randomUUID(), evidence.transferId(),
                    "synthetic-file", "synthetic-receiver", Path.of("synthetic-input-not-created.bin"),
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 9000),
                    new TransferSettings(1024, 4096, 4, 200, 5));
            var error = state == TransferState.FAILED
                    ? new TransferError(TransferError.Code.TRANSFER_FAILED, "SYNTHETIC failure outcome") : null;
            var snapshot = new TransferSnapshot(evidence.transferId(), evidence.runId(),
                    evidence.protocolTransferId(), state, CAPTURED_AT, evidence.source(),
                    TransferMetrics.unavailable("No engine measurements in this SYNTHETIC fixture"), error);
            return new TransferSummary(request, snapshot, integrity, "SYNTHETIC test outcome");
        }

        public Fixture withIdentity(UUID runId, UUID transferId, UUID protocolId) {
            var copy = new RecordedSummary(runId, transferId, protocolId, evidence.source(),
                    evidence.capturedAt(), evidence.definitionVersion(), evidence.label(), evidence.fields());
            var analysis = new ExplanationDraft(runId, transferId, draft.observations(),
                    draft.hypotheses(), draft.limitations());
            return new Fixture(copy, analysis, state, integrity);
        }
    }

    public static Fixture baseline() {
        return fixture("baseline", List.of(observed("duration_ms", "1000", "ms",
                        "Draft: protocol duration from first START send to terminal outcome."),
                observed("retransmitted_packets", "0", "packets",
                        "Draft: data packet sends after the first send of each sequence.")),
                "The supplied synthetic duration is 1000 ms and retransmitted packet count is 0.",
                List.of(ref("duration_ms", "1000", "ms"), ref("retransmitted_packets", "0", "packets")),
                List.of(), List.of("SYNTHETIC test data; draft definitions are not a team contract.",
                        "One synthetic run does not establish the fastest settings."),
                TransferState.COMPLETED, IntegrityStatus.VERIFIED);
    }

    public static Fixture loss() {
        return fixture("configured loss", List.of(configured("configured_loss_percent", "10", "percent",
                        "Draft: configured synthetic impairment probability, not measured packet loss."),
                observed("retransmitted_packets", "12", "packets",
                        "Draft: data packet sends after the first send of each sequence."),
                missingField("observed_drops", "packets", "Draft: observed data packet drops.",
                        "The synthetic fixture supplies no drop observations.")),
                "The synthetic configured loss is 10 percent; the supplied retransmitted packet count is 12.",
                List.of(ref("configured_loss_percent", "10", "percent"),
                        ref("retransmitted_packets", "12", "packets")),
                List.of("Dropped packets could contribute; packet observations would be needed to test that cause."),
                List.of("SYNTHETIC test data; draft definitions are not a team contract.",
                        "observed_drops is unavailable: the fixture supplies no drop observations.",
                        "Configured impairment and retransmissions do not establish observed packet loss percentage."),
                TransferState.COMPLETED, IntegrityStatus.VERIFIED);
    }

    public static Fixture delay() {
        return fixture("configured delay", List.of(configured("configured_delay_ms", "150", "ms",
                        "Draft: fixed artificial delay setting, not measured RTT."),
                observed("timeout_events", "3", "events", "Draft: recorded data timeout events."),
                missingField("rtt_ms", "ms", "Draft: measured round trip time.",
                        "No RTT samples supplied by the synthetic fixture.")),
                "The synthetic configured delay is 150 ms and the supplied timeout count is 3.",
                List.of(ref("configured_delay_ms", "150", "ms"), ref("timeout_events", "3", "events")),
                List.of("Delay could contribute; ACK timing and RTT observations are needed to test this."),
                List.of("SYNTHETIC test data; draft definitions are not a team contract.",
                        "rtt_ms is unavailable: no RTT samples are supplied.",
                        "Timeouts alone do not prove congestion."),
                TransferState.COMPLETED, IntegrityStatus.VERIFIED);
    }

    public static Fixture missing() {
        return fixture("missing measurements", List.of(
                missingField("duration_ms", "ms", "Draft: protocol duration.",
                        "No protocol timing observations supplied."),
                missingField("throughput_bps", "bits/s", "Draft: supplied payload throughput measurement.",
                        "No throughput measurement supplied.")),
                null, List.of(), List.of(),
                List.of("SYNTHETIC test data; draft definitions are not a team contract.",
                        "duration_ms is unavailable: no protocol timing observations supplied.",
                        "throughput_bps is unavailable: no throughput measurement supplied.",
                        "The evidence cannot establish transfer performance."),
                TransferState.COMPLETED, IntegrityStatus.VERIFIED);
    }

    public static Fixture failure() {
        return fixture("unconfirmed failed transfer", List.of(
                observed("unique_payload_bytes_acked", "2048", "bytes",
                        "Draft: unique payload bytes acknowledged before the outcome."),
                missingField("duration_ms", "ms", "Draft: protocol duration.",
                        "No terminal protocol timing observation supplied.")),
                "The synthetic supplied unique acknowledged payload count is 2048 bytes.",
                List.of(ref("unique_payload_bytes_acked", "2048", "bytes")), List.of(),
                List.of("SYNTHETIC test data; draft definitions are not a team contract.",
                        "duration_ms is unavailable: no terminal protocol timing observation supplied.",
                        "The selected outcome failed with unconfirmed integrity; partial ACK progress is not verified completion."),
                TransferState.FAILED, IntegrityStatus.UNCONFIRMED);
    }

    public static RecordedSummary.Field observed(String id, String value, String unit, String definition) {
        return new RecordedSummary.Field(id, new BigDecimal(value), unit,
                RecordedSummary.Kind.OBSERVED, definition, null);
    }

    public static RecordedSummary.Field configured(String id, String value, String unit, String definition) {
        return new RecordedSummary.Field(id, new BigDecimal(value), unit,
                RecordedSummary.Kind.CONFIGURED, definition, null);
    }

    public static RecordedSummary.Field missingField(String id, String unit, String definition, String reason) {
        return new RecordedSummary.Field(id, null, unit, RecordedSummary.Kind.OBSERVED, definition, reason);
    }

    public static ExplanationDraft.Reference ref(String id, String value, String unit) {
        return new ExplanationDraft.Reference(id, new BigDecimal(value), unit);
    }

    private static Fixture fixture(String name, List<RecordedSummary.Field> fields, String observation,
                                   List<ExplanationDraft.Reference> refs, List<String> hypotheses,
                                   List<String> limitations, TransferState state, IntegrityStatus integrity) {
        UUID runId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        var evidence = new RecordedSummary(runId, transferId, UUID.randomUUID(), EvidenceSource.SYNTHETIC,
                CAPTURED_AT, RecordedSummary.FIXTURE_DEFINITION_VERSION,
                "SYNTHETIC fixture: " + name + "; draft definitions", fields);
        var observations = observation == null ? List.<ExplanationDraft.Observation>of()
                : List.of(new ExplanationDraft.Observation(observation, refs));
        return new Fixture(evidence, new ExplanationDraft(runId, transferId, observations, hypotheses, limitations),
                state, integrity);
    }
}
