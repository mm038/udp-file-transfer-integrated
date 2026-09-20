package nettransfer.explanation;

import nettransfer.control.IntegrityStatus;
import nettransfer.control.TransferState;
import nettransfer.explanation.SyntheticExplanationFixtures.Fixture;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static nettransfer.explanation.SyntheticExplanationFixtures.*;

/**
 * Authored SYNTHETIC compatibility examples for Metrics_Summary_Revised.md.
 * Test code only: not a producer schema, parser, calculator, identity resolver or measured evidence.
 * Numeric fields exercise the existing analysis envelope. The remaining typed metadata is deliberately
 * kept outside it: production support for producer metadata/outcome reconciliation does not yet exist.
 */
public final class AcceptedMetricFixtures {
    private AcceptedMetricFixtures() { }

    public record Metadata(String experimentId, String schemaVersion, String metricDefinitionVersion,
                           Instant finalizedAt, String endpointAttribution, String fileIdentity,
                           String integrityEvidenceSource, String scenario, Long impairmentSeed,
                           boolean transferSuccess, Boolean integrityVerified, String failureReason,
                           Map<String, String> unavailableReasons) {
        public Metadata {
            unavailableReasons = Map.copyOf(unavailableReasons);
        }
    }

    public record Example(Fixture analysis, Metadata metadata) { }

    /** Lossless, in-order 10 MiB illustration, with the existing fixture's 4-packet window. */
    public static Example baseline() {
        var original = SyntheticExplanationFixtures.baseline();
        var fields = List.of(
                observed("file_size_bytes", "10485760", "bytes", "Synthetic source metadata; not proof of delivery."),
                observed("payload_bytes_delivered", "10485760", "bytes", "Unique receiver payload accepted and written; excludes duplicate copies."),
                observed("transfer_time_sec", "8.500000", "s", "Monotonic first START attempt to sender terminal decision; excludes GPT time."),
                observed("throughput_mbps", "9.87", "Mbps", "Supplied useful delivered-payload rate in decimal megabits/s; on failure this is failed-run delivery rate, not successful-file throughput."),
                observed("packets_sent", "10240", "packets", "Sender DATA attempts including resends, before outgoing impairment."),
                observed("packets_received", "10240", "packets", "Receiver DATA arrivals including duplicates and out-of-order arrivals; not unique delivery."),
                observed("packets_dropped", "0", "packets", "DATA attempts dropped by the identified simulator; not all network loss."),
                observed("retransmissions", "0", "packets", "Lifetime DATA resend attempts; not consecutive recovery rounds."),
                observed("acks_received", "10240", "packets", "Sender DATA-ACK arrivals including repeats; excludes START_ACK/FINISH_ACK."),
                observed("packets_acked", "10240", "packets", "Distinct DATA sequences newly confirmed by valid cumulative ACK progress."),
                observed("packets_timed_out", "0", "events", "Detected expired DATA send attempts; one Go-Back-N trigger may resend several packets."),
                observed("packets_duplicated", "0", "packets", "Valid receiver DATA arrivals already accepted; excludes ahead-of-gap discards."),
                observed("retransmission_ratio", "0.0", "fraction", "Supplied resends / all DATA attempts; null for zero or unavailable attempts."),
                missingField("udp_payload_bytes_emitted", "bytes", "Actual UDP payload emissions at both endpoints counted once; includes DATA/ACK/control; excludes suppressed sends and IP/UDP/link headers.", "Complete endpoint emission observations are not supplied."),
                missingField("protocol_overhead_bytes", "bytes", "Supplied emitted UDP payload bytes minus unique delivered payload; requires compatible complete accounting.", "Complete byte accounting is unavailable."),
                missingField("protocol_overhead_ratio", "fraction", "Supplied overhead / emitted UDP payload bytes; null for zero emissions or incomplete accounting.", "Complete byte accounting is unavailable."),
                missingField("rtt_sample_count", "samples", "Count of usable sender DATA/ACK samples; zero differs from unavailable sampling.", "RTT sampling evidence is not supplied."),
                missingField("rtt_mean_ms", "ms", "Supplied arithmetic mean of valid unambiguous sender RTT samples; not configured delay.", "RTT sampling evidence is not supplied."),
                missingField("rtt_p95_ms", "ms", "Supplied nearest-rank p95 of the same valid RTT samples; excludes retransmission ambiguity.", "RTT sampling evidence is not supplied."),
                configured("chunk_size_bytes", "1024", "bytes", "Effective accepted DATA payload chunk size."),
                configured("window_bytes_requested", "4096", "bytes", "Resolved requested window budget, including Java defaults when omitted; not necessarily explicit user input."),
                configured("window_packets", "4", "packets", "Effective whole DATA-packet window capacity."),
                configured("timeout_ms", "200", "ms", "Configured DATA retransmission timeout; not API request timeout."),
                configured("retry_limit", "5", "rounds", "Consecutive retransmission rounds without progress; not resends per packet."),
                configured("packet_loss_rate", "0.0", "percent", "Configured simulated DATA loss percentage; not observed loss."),
                configured("delay_ms", "0", "ms", "Configured fixed outgoing DATA simulator delay; not measured RTT."));
        var metadata = new Metadata("SYNTHETIC-BASELINE-001", "synthetic-test-manifest-only-v1",
                "accepted-set-synthetic-example-v1", CAPTURED_AT, "SYNTHETIC sender and receiver",
                "synthetic-file", "SYNTHETIC receiver SHA-256 check", "synthetic_lossless_in_order_baseline",
                null, true, true, null, Map.of());
        return example(original, fields, TransferState.COMPLETED, IntegrityStatus.VERIFIED, metadata);
    }

    /** Independent authored values exercise decimal preservation; no metric is calculated here. */
    public static Example precisionAndAccounting() {
        var example = baseline();
        example = values(example, Map.of("transfer_time_sec", "8.500000125", "throughput_mbps", "9.868950443104",
                "packet_loss_rate", "12.500", "delay_ms", "0.125", "udp_payload_bytes_emitted", "10747904",
                "protocol_overhead_bytes", "262144", "protocol_overhead_ratio", "0.024390243902",
                "rtt_sample_count", "4", "rtt_mean_ms", "1.375", "rtt_p95_ms", "1.750"));
        var old = example.metadata();
        var metadata = new Metadata(old.experimentId(), old.schemaVersion(), old.metricDefinitionVersion(),
                old.finalizedAt(), old.endpointAttribution(), old.fileIdentity(), old.integrityEvidenceSource(),
                "synthetic_boundary_values_not_a_reconstructed_packet_history", 42L, true, true, null, Map.of());
        return example(example.analysis(), example.analysis().evidence().fields(),
                TransferState.COMPLETED, IntegrityStatus.VERIFIED, metadata);
    }

    public static Example partialFailure() {
        var example = values(baseline(), Map.of("payload_bytes_delivered", "2048", "packets_sent", "4",
                "packets_received", "2", "packets_acked", "1", "acks_received", "1",
                "retransmissions", "1", "packets_timed_out", "1"));
        example = missing(example, "transfer_time_sec", "Terminal monotonic timing observation is absent.");
        example = missing(example, "throughput_mbps", "Delivered bytes alone cannot supply a rate without duration.");
        example = missing(example, "retransmission_ratio", "No finalized ratio supplied; the consumer must not calculate it.");
        return outcome(example, false, null, TransferState.FAILED, IntegrityStatus.UNCONFIRMED,
                "SYNTHETIC sender failure; no recorded cause", "SYNTHETIC check not available");
    }

    public static Example integrityFailure() {
        return outcome(baseline(), false, false, TransferState.FAILED, IntegrityStatus.FAILED,
                "SYNTHETIC checked SHA-256 mismatch", "SYNTHETIC receiver SHA-256 check");
    }

    public static Example receiverVerifiedWithoutSenderConfirmation() {
        return outcome(baseline(), false, true, TransferState.FAILED, IntegrityStatus.UNCONFIRMED,
                "SYNTHETIC sender final confirmation missing", "SYNTHETIC receiver SHA-256 check only");
    }

    public static Example zeroByte() {
        var example = values(baseline(), Map.of("file_size_bytes", "0", "payload_bytes_delivered", "0",
                "transfer_time_sec", "0", "packets_sent", "0", "packets_received", "0",
                "packets_acked", "0", "acks_received", "0", "rtt_sample_count", "0"));
        example = missing(example, "throughput_mbps", "Authored zero-duration edge case; rate is undefined.");
        example = missing(example, "retransmission_ratio", "No DATA attempts; ratio denominator is zero.");
        example = missing(example, "rtt_mean_ms", "Sampling ran but produced no usable DATA RTT samples.");
        return missing(example, "rtt_p95_ms", "Sampling ran but produced no usable DATA RTT samples.");
    }

    /** Deliberately inconsistent negative examples remain test inputs, never repaired into evidence. */
    public static Example values(Example original, Map<String, String> replacements) {
        var fields = original.analysis().evidence().fields().stream().map(field -> replacements.containsKey(field.id())
                ? new RecordedSummary.Field(field.id(), new BigDecimal(replacements.get(field.id())), field.unit(),
                        field.kind(), field.definition(), null) : field).toList();
        return example(original.analysis(), fields, original.analysis().state(), original.analysis().integrity(), original.metadata());
    }

    public static Example missing(Example original, String id, String reason) {
        var fields = original.analysis().evidence().fields().stream().map(field -> field.id().equals(id)
                ? new RecordedSummary.Field(field.id(), null, field.unit(), field.kind(), field.definition(), reason)
                : field).toList();
        return example(original.analysis(), fields, original.analysis().state(), original.analysis().integrity(), original.metadata());
    }

    private static Example outcome(Example original, boolean success, Boolean verified, TransferState state,
                                   IntegrityStatus integrity, String failure, String integritySource) {
        var old = original.metadata();
        var metadata = new Metadata(old.experimentId(), old.schemaVersion(), old.metricDefinitionVersion(),
                old.finalizedAt(), old.endpointAttribution(), old.fileIdentity(), integritySource,
                "synthetic_outcome_boundary", old.impairmentSeed(), success, verified, failure,
                verified == null ? Map.of("integrity_verified", "No endpoint check evidence is available.") : Map.of());
        return example(original.analysis(), original.analysis().evidence().fields(), state, integrity, metadata);
    }

    private static Example example(Fixture original, List<RecordedSummary.Field> fields, TransferState state,
                                   IntegrityStatus integrity, Metadata metadata) {
        var old = original.evidence();
        var evidence = new RecordedSummary(old.runId(), old.transferId(), old.protocolTransferId(), old.source(),
                old.capturedAt(), old.definitionVersion(), "SYNTHETIC accepted-metric compatibility fixture; not producer output", fields);
        var draft = new ExplanationDraft(evidence.runId(), evidence.transferId(), List.of(), List.of(),
                List.of("SYNTHETIC authored values only; not measured evidence or an implemented producer schema.",
                        "Missing measurements and causes cannot be inferred from other supplied counters."));
        var reasons = new HashMap<>(metadata.unavailableReasons());
        for (var field : fields) {
            reasons.remove(field.id());
            if (field.value() == null) {
                reasons.put(field.id(), field.unavailableReason());
            }
        }
        var manifest = new Metadata(metadata.experimentId(), metadata.schemaVersion(), metadata.metricDefinitionVersion(),
                metadata.finalizedAt(), metadata.endpointAttribution(), metadata.fileIdentity(), metadata.integrityEvidenceSource(),
                metadata.scenario(), metadata.impairmentSeed(), metadata.transferSuccess(), metadata.integrityVerified(),
                metadata.failureReason(), reasons);
        return new Example(new Fixture(evidence, draft, state, integrity), manifest);
    }
}
