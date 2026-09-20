package nettransfer.explanation;

import nettransfer.control.EvidenceSource;
import nettransfer.control.IntegrityStatus;
import nettransfer.control.TransferState;
import nettransfer.explanation.AcceptedMetricFixtures.Example;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static nettransfer.explanation.AcceptedMetricFixtures.*;
import static nettransfer.explanation.ExplanationFlow.Status.*;
import static nettransfer.explanation.SyntheticExplanationFixtures.ref;
import static org.junit.jupiter.api.Assertions.*;

/** Offline acceptance-vocabulary regression, never acceptance of actual producer records. */
class AcceptedMetricRegressionTest {
    @Test
    void completeAcceptedNumericSetFitsTheExistingEnvelopeAndKeepsMetadataTypedOutsideIt() {
        Example example = baseline();
        Set<String> observed = Set.of("file_size_bytes", "payload_bytes_delivered", "transfer_time_sec",
                "throughput_mbps", "packets_sent", "packets_received", "packets_dropped", "retransmissions",
                "acks_received", "packets_acked", "packets_timed_out", "packets_duplicated", "retransmission_ratio",
                "udp_payload_bytes_emitted", "protocol_overhead_bytes", "protocol_overhead_ratio",
                "rtt_sample_count", "rtt_mean_ms", "rtt_p95_ms");
        Set<String> configured = Set.of("chunk_size_bytes", "window_bytes_requested", "window_packets",
                "timeout_ms", "retry_limit", "packet_loss_rate", "delay_ms");
        var fields = example.analysis().evidence().fields();
        assertEquals(observed, fields.stream().filter(f -> f.kind() == RecordedSummary.Kind.OBSERVED)
                .map(RecordedSummary.Field::id).collect(Collectors.toSet()));
        assertEquals(configured, fields.stream().filter(f -> f.kind() == RecordedSummary.Kind.CONFIGURED)
                .map(RecordedSummary.Field::id).collect(Collectors.toSet()));
        assertEquals(26, fields.size());
        assertTrue(fields.size() <= RecordedSummary.MAX_FIELDS);
        var metadata = example.metadata();
        assertTrue(metadata.transferSuccess());
        assertEquals(Boolean.TRUE, metadata.integrityVerified());
        assertNull(metadata.failureReason());
        assertNull(metadata.impairmentSeed(), "Inapplicable seed is metadata, not a missing measurement");
        assertNotEquals(metadata.schemaVersion(), RecordedSummary.FIXTURE_DEFINITION_VERSION);
        assertFalse(metadata.metricDefinitionVersion().isBlank());
        assertEquals(example.analysis().evidence().capturedAt(), metadata.finalizedAt());
        assertEquals(example.analysis().selected().request().fileId(), metadata.fileIdentity());
        assertTrue(metadata.endpointAttribution().contains("sender and receiver"));
        assertTrue(metadata.integrityEvidenceSource().contains("receiver"));
        assertTrue(metadata.scenario().startsWith("synthetic_"));
        for (var field : fields) {
            assertEquals(field.unavailableReason(), metadata.unavailableReasons().get(field.id()));
        }
        assertEquals(EvidenceSource.SYNTHETIC, example.analysis().evidence().source());
        assertTrue(example.analysis().evidence().label().contains("not producer output"));
        assertThrows(IllegalArgumentException.class, () -> UUID.fromString(metadata.experimentId()),
                "External experiment labels cannot be parsed as application UUIDs");
        // This is a fixture manifest check, not serialization or outcome support in production.
    }

    @Test
    void correctedLosslessBaselineHasFeasibleOriginalChunksWithoutCountingAcksAsData() {
        Example example = baseline();
        BigDecimal originals = value(example, "packets_sent").subtract(value(example, "retransmissions"));
        assertEquals(new BigDecimal("10240"), originals);
        assertEquals(value(example, "file_size_bytes"), originals.multiply(value(example, "chunk_size_bytes")));
        assertEquals(value(example, "payload_bytes_delivered"), value(example, "file_size_bytes"));
        assertEquals(value(example, "packets_sent"), value(example, "packets_acked"));
        assertEquals(value(example, "packets_acked"), value(example, "acks_received"),
                "Equal only because this specific fixture stipulates one delivered ACK per DATA packet");
        assertEquals(example.analysis().selected().request().settings().windowPackets(),
                value(example, "window_packets").intValueExact());
    }

    static Stream<Arguments> exactCitations() {
        return Stream.of(
                Arguments.of("transfer_time_sec", "8.500000125", "s"),
                Arguments.of("throughput_mbps", "9.868950443104", "Mbps"),
                Arguments.of("packet_loss_rate", "12.500", "percent"),
                Arguments.of("delay_ms", "0.125", "ms"),
                Arguments.of("protocol_overhead_ratio", "0.024390243902", "fraction"),
                Arguments.of("rtt_mean_ms", "1.375", "ms"),
                Arguments.of("rtt_p95_ms", "1.750", "ms"));
    }

    @ParameterizedTest
    @MethodSource("exactCitations")
    void acceptedUnitsAndDecimalPrecisionSurviveFlowAndCitationChecks(String id, String decimal, String unit) {
        Example example = precisionAndAccounting();
        var client = new StubExplanationClient(List.of(draft(example, ref(id, decimal, unit))));
        var result = flow(example, client).explain(example.analysis().selected(), "Explain the supplied value.");
        assertEquals(EXPLAINED, result.status());
        assertEquals(new BigDecimal(decimal), field(example, id).value(), "Keep scale as well as numeric value");
        assertSame(example.analysis().evidence(), client.requests().get(0).evidence());
        assertEquals(42L, example.metadata().impairmentSeed());
    }

    static Stream<Arguments> invalidCitations() {
        return Stream.of(
                Arguments.of("transfer_time_sec", "8500.000125", "ms"),
                Arguments.of("transfer_time_sec", "8.5", "s"),
                Arguments.of("throughput_mbps", "1233618.805388375", "bytes/s"),
                Arguments.of("throughput_mbps", "9.87", "Mbps"),
                Arguments.of("packet_loss_rate", "0.125", "fraction"),
                Arguments.of("rtt_mean_ms", "0.125", "ms"),
                Arguments.of("protocol_overhead_ratio", "2.4390243902", "percent"),
                Arguments.of("retransmission_ratio", "12.500", "percent"),
                Arguments.of("observed_loss_percent", "12.500", "percent"));
    }

    @ParameterizedTest
    @MethodSource("invalidCitations")
    void rejectsUnitConversionsRoundingAndConfiguredValuesMasqueradingAsObservations(String id, String decimal, String unit) {
        Example example = precisionAndAccounting();
        var result = flow(example, request -> draft(example, ref(id, decimal, unit)))
                .explain(example.analysis().selected(), "Explain exactly the supplied evidence.");
        assertEquals(EXPLANATION_REJECTED, result.status());
        assertSame(example.analysis().evidence(), result.evidence());
        assertNull(result.draft());
    }

    static Stream<Arguments> outcomes() {
        return Stream.of(
                Arguments.of(baseline(), true, Boolean.TRUE, TransferState.COMPLETED, IntegrityStatus.VERIFIED),
                Arguments.of(partialFailure(), false, null, TransferState.FAILED, IntegrityStatus.UNCONFIRMED),
                Arguments.of(integrityFailure(), false, Boolean.FALSE, TransferState.FAILED, IntegrityStatus.FAILED),
                Arguments.of(receiverVerifiedWithoutSenderConfirmation(), false, Boolean.TRUE,
                        TransferState.FAILED, IntegrityStatus.UNCONFIRMED));
    }

    @ParameterizedTest
    @MethodSource("outcomes")
    void typedFixtureOutcomesPreserveFalseNullAndReceiverOnlyVerification(Example example, boolean success,
                     Boolean verified, TransferState state, IntegrityStatus integrity) {
        assertEquals(success, example.metadata().transferSuccess());
        assertEquals(verified, example.metadata().integrityVerified());
        assertEquals(success, example.metadata().failureReason() == null);
        assertEquals(verified == null, example.metadata().unavailableReasons().containsKey("integrity_verified"));
        var client = new StubExplanationClient(List.of(example.analysis().draft()));
        var result = flow(example, client).explain(example.analysis().selected(), "Explain this outcome.");
        assertEquals(EXPLAINED, result.status());
        assertEquals(state, client.requests().get(0).state());
        assertEquals(integrity, client.requests().get(0).integrity());
        assertTrue(result.evidence().fields().stream().noneMatch(f -> Set.of("transfer_success", "integrity_verified",
                "experiment_id", "failure_reason", "impairment_seed").contains(f.id())), "Never invent numeric booleans or IDs");
        // The state/integrity pairs are explicitly authored, not a future producer mapping algorithm.
    }

    @Test
    void partialReceiverDeliveryCannotBecomeSenderProgressOrSourceSizedThroughput() {
        Example example = partialFailure();
        var client = new StubExplanationClient(List.of(example.analysis().draft()));
        var selected = example.analysis().selected();
        var result = flow(example, client).explain(selected, "How much arrived?");
        assertEquals(EXPLAINED, result.status());
        assertEquals(new BigDecimal("2048"), value(example, "payload_bytes_delivered"));
        assertEquals(new BigDecimal("10485760"), value(example, "file_size_bytes"));
        assertNull(field(example, "throughput_mbps").value());
        assertNull(selected.finalSnapshot().metrics().uniquePayloadBytesAcked());
        assertNull(selected.finalSnapshot().metrics().totalChunks());
        assertEquals(TransferState.FAILED, client.requests().get(0).state());
    }

    @ParameterizedTest
    @ValueSource(strings = {"payload_bytes_delivered", "transfer_time_sec", "throughput_mbps", "packets_sent",
            "packets_received", "packets_dropped", "retransmissions", "acks_received", "packets_acked",
            "packets_timed_out", "packets_duplicated", "retransmission_ratio", "udp_payload_bytes_emitted",
            "protocol_overhead_bytes", "protocol_overhead_ratio", "rtt_sample_count", "rtt_mean_ms", "rtt_p95_ms"})
    void everyNullableAcceptedMeasurementRemainsMissingAndCannotBeCitedAsZero(String id) {
        Example example = missing(baseline(), id, "SYNTHETIC absent observation for " + id);
        var client = new StubExplanationClient(List.of(draft(example, ref(id, "0", field(example, id).unit()))));
        var result = flow(example, client).explain(example.analysis().selected(), "Explain missing data.");
        assertEquals(EXPLANATION_REJECTED, result.status());
        assertSame(example.analysis().evidence(), result.evidence());
        var supplied = client.requests().get(0).evidence().fields().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
        assertNull(supplied.value());
        assertEquals("SYNTHETIC absent observation for " + id, supplied.unavailableReason());
    }

    @Test
    void zeroDurationAttemptsAndUsableSamplesDoNotInventRatesRatiosOrRtt() {
        Example example = zeroByte();
        var result = flow(example, request -> example.analysis().draft())
                .explain(example.analysis().selected(), "Explain this empty-file edge case.");
        assertEquals(EXPLAINED, result.status());
        for (String id : List.of("file_size_bytes", "payload_bytes_delivered", "transfer_time_sec", "packets_sent", "rtt_sample_count")) {
            assertEquals(BigDecimal.ZERO, field(example, id).value());
        }
        for (String id : List.of("throughput_mbps", "retransmission_ratio", "rtt_mean_ms", "rtt_p95_ms", "protocol_overhead_ratio")) {
            assertNull(field(example, id).value());
            assertFalse(field(example, id).unavailableReason().isBlank());
        }
        assertEquals(TransferState.COMPLETED, example.analysis().selected().finalSnapshot().state(),
                "Explicit verified outcome, not a percentage or division by file size, establishes completion");
    }

    static Stream<Arguments> inconsistentCounters() {
        return Stream.of(
                Arguments.of(Map.of("packets_sent", "1200", "retransmissions", "120"), "packets_sent", "10360"),
                Arguments.of(Map.of("retransmissions", "10241"), "retransmissions", "10240"),
                Arguments.of(Map.of("packets_duplicated", "10241"), "packets_duplicated", "0"),
                Arguments.of(Map.of("packets_acked", "10241"), "packets_acked", "10240"),
                Arguments.of(Map.of("payload_bytes_delivered", "10485761"), "payload_bytes_delivered", "10485760"));
    }

    @ParameterizedTest
    @MethodSource("inconsistentCounters")
    void inconsistentSyntheticCountersAreNotSilentlyRepairedAndInventedCorrectionsAreRejected(
            Map<String, String> mutations, String correctedId, String correction) {
        Example example = values(baseline(), mutations);
        var client = new StubExplanationClient(List.of(draft(example, ref(correctedId, correction, field(example, correctedId).unit()))));
        var result = flow(example, client).explain(example.analysis().selected(), "Check these deliberately inconsistent inputs.");
        assertEquals(EXPLANATION_REJECTED, result.status());
        assertSame(example.analysis().evidence(), result.evidence());
        mutations.forEach((id, value) -> assertEquals(new BigDecimal(value), client.requests().get(0).evidence().fields()
                .stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow().value()));
        // Identity/citation validation is not counter-semantic validation. Do not claim EVIDENCE_REJECTED.
    }

    @Test
    void impossibleOriginalIllustrationIsDetectableWithoutInventingOtherCounters() {
        Example impossible = values(baseline(), Map.of("packets_sent", "1200", "retransmissions", "120"));
        BigDecimal maximumPayload = value(impossible, "packets_sent").subtract(value(impossible, "retransmissions"))
                .multiply(value(impossible, "chunk_size_bytes"));
        assertTrue(maximumPayload.compareTo(value(impossible, "payload_bytes_delivered")) < 0,
                "1080 original DATA attempts cannot carry a 10 MiB successful file at <=1024 bytes per chunk");
        assertEquals(new BigDecimal("10240"), value(impossible, "acks_received"), "No inferred counter repair");
    }

    @Test
    void packetAndAckArrivalsDoNotImplyDropTimeoutOrDeliveryEqualities() {
        // Authored boundary case: ACK loss/out-of-order arrivals can cause repeated Go-Back-N sends.
        Example example = values(baseline(), Map.of("packets_sent", "10244", "packets_received", "10243",
                "packets_dropped", "0", "retransmissions", "4", "acks_received", "10241",
                "packets_timed_out", "1", "packets_duplicated", "1"));
        example = missing(example, "retransmission_ratio", "No finalized ratio supplied.");
        Example supplied = example;
        var result = flow(example, request -> supplied.analysis().draft()).explain(example.analysis().selected(), "Check counter meanings.");
        assertEquals(EXPLAINED, result.status());
        assertNotEquals(value(example, "packets_sent").subtract(value(example, "packets_received")), value(example, "packets_dropped"));
        assertNotEquals(value(example, "retransmissions"), value(example, "packets_timed_out"));
        assertNotEquals(value(example, "acks_received"), value(example, "packets_acked"));
        assertNull(field(example, "retransmission_ratio").value());
    }

    @Test
    void repeatedExternalLabelCannotSelectAnotherApplicationRunOrInventWireIdentity() {
        Example first = baseline();
        Example second = baseline();
        assertEquals(first.metadata().experimentId(), second.metadata().experimentId());
        assertNotEquals(first.analysis().evidence().runId(), second.analysis().evidence().runId());
        var client = new StubExplanationClient(List.of(second.analysis().draft()));
        var result = new ExplanationFlow(id -> Optional.of(second.analysis().evidence()), client)
                .explain(first.analysis().selected(), "Explain SYNTHETIC-BASELINE-001.");
        assertEquals(EVIDENCE_REJECTED, result.status());
        assertTrue(client.requests().isEmpty());
        assertNull(result.evidence());

        var evidence = first.analysis().evidence();
        var unknownWire = first.analysis().withIdentity(evidence.runId(), evidence.transferId(), null);
        var wrongWire = new ExplanationFlow(id -> Optional.of(evidence), client)
                .explain(unknownWire.selected(), "The fixture's wire association is not verified.");
        assertEquals(EVIDENCE_REJECTED, wrongWire.status());
        assertTrue(client.requests().isEmpty());
    }

    private static RecordedSummary.Field field(Example example, String id) {
        return example.analysis().evidence().fields().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }

    private static BigDecimal value(Example example, String id) {
        return field(example, id).value();
    }

    private static ExplanationDraft draft(Example example, ExplanationDraft.Reference reference) {
        var evidence = example.analysis().evidence();
        return new ExplanationDraft(evidence.runId(), evidence.transferId(),
                List.of(new ExplanationDraft.Observation("SYNTHETIC structured reference under test", List.of(reference))),
                List.of(), List.of("Authored fixture; numerical reference checks cannot establish prose truth or counter consistency."));
    }

    private static ExplanationFlow flow(Example example, ExplanationClient client) {
        return new ExplanationFlow(new SyntheticSummaryProvider(List.of(example.analysis().evidence())), client);
    }
}
