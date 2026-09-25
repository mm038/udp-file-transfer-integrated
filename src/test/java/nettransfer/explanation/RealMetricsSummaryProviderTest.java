package nettransfer.explanation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import nettransfer.cli.TransferCli;
import nettransfer.control.EvidenceSource;
import nettransfer.control.IntegrityStatus;
import nettransfer.control.TransferError;
import nettransfer.control.TransferRequest;
import nettransfer.control.TransferSettings;
import nettransfer.control.TransferSnapshot;
import nettransfer.control.TransferState;
import nettransfer.control.TransferSummary;
import nettransfer.control.TransferService;
import nettransfer.control.TransferStart;
import nettransfer.control.command.CommandProposal;
import nettransfer.control.command.TransferConfiguration;
import nettransfer.metrics.LiveMetricsSnapshot;
import nettransfer.metrics.MetricsCollector;
import nettransfer.metrics.MetricsSchema;
import nettransfer.metrics.PersistedEvidenceRepository;
import nettransfer.metrics.TransferContext;
import nettransfer.metrics.TransferMetrics;
import nettransfer.net.UdpChannel;
import nettransfer.protocol.ControlMessage;
import nettransfer.protocol.MessageType;
import nettransfer.protocol.PacketDecoder;
import nettransfer.transfer.ReceiverEngine;
import nettransfer.transfer.SenderEngine;
import nettransfer.transfer.TransferResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static nettransfer.explanation.SummaryProvider.Status.AVAILABLE;
import static nettransfer.explanation.SummaryProvider.Status.INCOMPLETE;
import static nettransfer.explanation.SummaryProvider.Status.PENDING;
import static nettransfer.explanation.SummaryProvider.Status.REJECTED;
import static nettransfer.explanation.SummaryProvider.Status.UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class RealMetricsSummaryProviderTest {
    @TempDir
    Path tempDir;

    @Test
    void validatedReconciliationBecomesIdentityBoundRealSummary() throws Exception {
        Fixture fixture = successfulTransfer(tempDir.resolve("reconciled"), null);

        SummaryProvider.LookupResult result = provider(fixture).lookup(fixture.selection());

        assertEquals(AVAILABLE, result.status(), result::toString);
        RecordedSummary summary = result.summary();
        assertEquals(EvidenceSource.REAL, summary.source());
        assertEquals(fixture.applicationId(), summary.runId());
        assertEquals(fixture.applicationId(), summary.transferId());
        assertEquals(fixture.protocolId(), summary.protocolTransferId());
        assertEquals(MetricsSchema.METRIC_DEFINITION_VERSION, summary.definitionVersion());
        assertEquals(RecordedSummary.EvidenceScope.RECONCILED, summary.metadata().scope());
        assertEquals(fixture.applicationId().toString(), summary.metadata().applicationTransferId());
        assertEquals(fixture.applicationId().toString(), summary.metadata().senderRunId());
        assertEquals(fixture.receiverRunId(), summary.metadata().receiverRunId());
        assertEquals(fixture.protocolId(), summary.metadata().protocolTransferId());
        assertEquals(MetricsSchema.SUMMARY_SCHEMA_VERSION, summary.metadata().metricsSchemaVersion());
        assertEquals("SUCCESS", summary.metadata().senderTerminalOutcome());
        assertEquals(Boolean.TRUE, summary.metadata().receiverIntegrityVerified());
        assertNull(summary.metadata().failureCategory());
        assertNull(summary.metadata().failureReason());
        assertEquals(8, summary.metadata().sourceReferences().size());
        assertTrue(summary.metadata().sourceReferences().stream()
                .noneMatch(reference -> reference.contains("..") || Path.of(reference).isAbsolute()));
    }

    @Test
    void mapsEveryAgreedNumericMetricWithUnitsKindsAndMissingReasons() throws Exception {
        Fixture fixture = successfulTransfer(tempDir.resolve("mapping"), null);
        PersistedEvidenceRepository repository = new PersistedEvidenceRepository(fixture.logsRoot());
        RecordedSummary summary = new RealMetricsSummaryProvider(repository)
                .lookup(fixture.selection()).summary();
        TransferMetrics source = repository.lookup(fixture.applicationId().toString())
                .evidence().reconciled().summary().metrics();
        Map<String, RecordedSummary.Field> fields = summary.fields().stream()
                .collect(Collectors.toMap(RecordedSummary.Field::id, Function.identity()));

        assertEquals(26, fields.size());
        assertFields(fields, RecordedSummary.Kind.OBSERVED,
                "file_size_bytes", "payload_bytes_delivered", "transfer_time_sec", "throughput_mbps",
                "packets_sent", "packets_received", "packets_dropped", "retransmissions",
                "acks_received", "packets_acked", "packets_timed_out", "packets_duplicated",
                "retransmission_ratio", "udp_payload_bytes_emitted", "protocol_overhead_bytes",
                "protocol_overhead_ratio", "rtt_sample_count", "rtt_mean_ms", "rtt_p95_ms");
        assertFields(fields, RecordedSummary.Kind.CONFIGURED,
                "chunk_size_bytes", "window_bytes_requested", "window_packets", "timeout_ms",
                "retry_limit", "packet_loss_rate", "delay_ms");
        assertEquals("bytes", fields.get("payload_bytes_delivered").unit());
        assertEquals("Mbps", fields.get("throughput_mbps").unit());
        assertEquals("fraction", fields.get("retransmission_ratio").unit());
        assertEquals("ms", fields.get("rtt_p95_ms").unit());
        assertEquals("rounds", fields.get("retry_limit").unit());

        assertMetric(fields, source, "file_size_bytes", source.getFileSizeBytes());
        assertMetric(fields, source, "payload_bytes_delivered", source.getPayloadBytesDelivered());
        assertMetric(fields, source, "transfer_time_sec", source.getTransferTimeSec());
        assertMetric(fields, source, "throughput_mbps", source.getThroughputMbps());
        assertMetric(fields, source, "packets_sent", source.getPacketsSent());
        assertMetric(fields, source, "packets_received", source.getPacketsReceived());
        assertMetric(fields, source, "packets_dropped", source.getPacketsDropped());
        assertMetric(fields, source, "retransmissions", source.getRetransmissions());
        assertMetric(fields, source, "acks_received", source.getAcksReceived());
        assertMetric(fields, source, "packets_acked", source.getPacketsAcked());
        assertMetric(fields, source, "packets_timed_out", source.getPacketsTimedOut());
        assertMetric(fields, source, "packets_duplicated", source.getPacketsDuplicated());
        assertMetric(fields, source, "retransmission_ratio", source.getRetransmissionRatio());
        assertMetric(fields, source, "udp_payload_bytes_emitted", source.getUdpPayloadBytesEmitted());
        assertMetric(fields, source, "protocol_overhead_bytes", source.getProtocolOverheadBytes());
        assertMetric(fields, source, "protocol_overhead_ratio", source.getProtocolOverheadRatio());
        assertMetric(fields, source, "rtt_sample_count", source.getRttSampleCount());
        assertMetric(fields, source, "rtt_mean_ms", source.getRttMeanMs());
        assertMetric(fields, source, "rtt_p95_ms", source.getRttP95Ms());
        assertMetric(fields, source, "chunk_size_bytes", source.getChunkSizeBytes());
        assertMetric(fields, source, "window_bytes_requested", source.getWindowBytesRequested());
        assertMetric(fields, source, "window_packets", source.getWindowPackets());
        assertMetric(fields, source, "timeout_ms", source.getTimeoutMs());
        assertMetric(fields, source, "retry_limit", source.getRetryLimit());
        assertMetric(fields, source, "packet_loss_rate", source.getPacketLossRate());
        assertMetric(fields, source, "delay_ms", source.getDelayMs());

        assertEquals(0, fields.get("retransmissions").value().compareTo(BigDecimal.ZERO));
        assertNull(fields.get("retransmissions").unavailableReason());
        assertNull(fields.get("packets_dropped").value());
        assertEquals("no active simulator or observed drop evidence",
                fields.get("packets_dropped").unavailableReason());
        assertNull(fields.get("packet_loss_rate").value());
        assertEquals(fixture.senderMetrics().getUnavailableReasons().get("packet_loss_rate"),
                fields.get("packet_loss_rate").unavailableReason());
    }

    @Test
    void senderOnlyFailureProducesFinalSenderEvidenceWithoutReceiverClaims() throws Exception {
        Fixture fixture = startTimeout(tempDir.resolve("start-timeout"));

        SummaryProvider.LookupResult result = provider(fixture).lookup(fixture.selection());

        assertEquals(AVAILABLE, result.status(), result::toString);
        RecordedSummary summary = result.summary();
        assertEquals(RecordedSummary.EvidenceScope.SENDER_FINAL, summary.metadata().scope());
        assertEquals("FAILED", summary.metadata().senderTerminalOutcome());
        assertEquals("START_HANDSHAKE_TIMEOUT", summary.metadata().failureCategory());
        assertNull(summary.metadata().receiverRunId());
        assertNull(summary.metadata().receiverIntegrityVerified());
        assertNull(field(summary, "payload_bytes_delivered").value());
        assertNotNull(field(summary, "payload_bytes_delivered").unavailableReason());
        assertNull(field(summary, "throughput_mbps").value());
        assertNotNull(field(summary, "throughput_mbps").unavailableReason());
        assertTrue(field(summary, "throughput_mbps").definition().contains("receiver delivery is required"));
        assertTrue(field(summary, "throughput_mbps").definition().contains("Live ACK-based rate is a separate field"));
        assertEquals(3, summary.metadata().sourceReferences().size());
    }

    @Test
    void pendingIncompleteUnavailableAndRejectedStatesRemainTypedAndExposeNoSummary() throws Exception {
        Fixture pendingFixture = successfulTransfer(tempDir.resolve("pending"), null);
        setState(pendingFixture.receiverDirectory(), "RECORDING");
        assertState(PENDING, provider(pendingFixture).lookup(pendingFixture.selection()));

        Fixture incompleteFixture = successfulTransfer(tempDir.resolve("incomplete"), null);
        setState(incompleteFixture.senderDirectory(), "INCOMPLETE");
        assertState(INCOMPLETE, provider(incompleteFixture).lookup(incompleteFixture.selection()));

        UUID missing = UUID.randomUUID();
        var missingProvider = new RealMetricsSummaryProvider(
                new PersistedEvidenceRepository(tempDir.resolve("missing-logs")));
        assertState(UNAVAILABLE, missingProvider.lookup(
                SummaryProvider.Selection.applicationTransfer(missing, UUID.randomUUID())));

        Fixture malformed = successfulTransfer(tempDir.resolve("malformed"), null);
        Files.writeString(malformed.senderDirectory().resolve("endpoint-sender.json"), "{broken");
        assertState(REJECTED, provider(malformed).lookup(malformed.selection()));
    }

    @Test
    void exactApplicationAndProtocolSelectionCannotChooseAnotherTransfer() throws Exception {
        Fixture fixture = successfulTransfer(tempDir.resolve("exact"), null);
        RealMetricsSummaryProvider provider = provider(fixture);

        var wrongApplication = new SummaryProvider.Selection(fixture.applicationId(), UUID.randomUUID(),
                fixture.applicationId().toString(), fixture.protocolId());
        assertState(REJECTED, provider.lookup(wrongApplication));

        var wrongProtocol = new SummaryProvider.Selection(fixture.applicationId(), fixture.applicationId(),
                fixture.applicationId().toString(), UUID.randomUUID());
        assertState(REJECTED, provider.lookup(wrongProtocol));

        Fixture other = successfulTransfer(tempDir.resolve("other"), null);
        var crossed = new SummaryProvider.Selection(fixture.applicationId(), fixture.applicationId(),
                other.applicationId().toString(), other.protocolId());
        assertState(UNAVAILABLE, provider.lookup(crossed));
    }

    @Test
    void conflictingApplicationIdentityAndSyntheticProvenanceAreRejected() throws Exception {
        Fixture conflict = successfulTransfer(tempDir.resolve("conflict"), "receiver-application");
        assertState(REJECTED, provider(conflict).lookup(conflict.selection()));

        Fixture synthetic = successfulTransfer(tempDir.resolve("synthetic"), null);
        Path endpointPath = synthetic.senderDirectory().resolve("endpoint-sender.json");
        JsonObject endpoint = object(endpointPath);
        endpoint.addProperty("evidence_source", "SYNTHETIC");
        endpoint.getAsJsonObject("metrics").addProperty("evidence_source", "SYNTHETIC");
        Files.writeString(endpointPath, endpoint + "\n");
        assertState(REJECTED, provider(synthetic).lookup(synthetic.selection()));
    }

    @Test
    void finishAckLossKeepsSenderFailureSeparateFromReceiverVerifiedIntegrity() throws Exception {
        Fixture fixture = finishAckLoss(tempDir.resolve("finish-timeout"));

        SummaryProvider.LookupResult result = provider(fixture).lookup(fixture.selection());

        assertEquals(AVAILABLE, result.status(), result::toString);
        assertEquals(RecordedSummary.EvidenceScope.RECONCILED, result.summary().metadata().scope());
        assertEquals("FAILED", result.summary().metadata().senderTerminalOutcome());
        assertEquals("FINISH_HANDSHAKE_TIMEOUT", result.summary().metadata().failureCategory());
        assertEquals(Boolean.TRUE, result.summary().metadata().receiverIntegrityVerified());
    }

    @Test
    void legacySyntheticProviderRetainsTypedSyntheticBoundary() {
        RecordedSummary fixture = SyntheticExplanationFixtures.baseline().evidence();
        SyntheticSummaryProvider provider = new SyntheticSummaryProvider(java.util.List.of(fixture));
        var selection = new SummaryProvider.Selection(
                fixture.runId(), fixture.transferId(), fixture.runId().toString(), fixture.protocolTransferId());

        SummaryProvider.LookupResult result = provider.lookup(selection);

        assertEquals(AVAILABLE, result.status());
        assertEquals(EvidenceSource.SYNTHETIC, result.summary().source());
        assertEquals(RecordedSummary.EvidenceScope.SYNTHETIC_FIXTURE,
                result.summary().metadata().scope());
        assertEquals(RecordedSummary.FIXTURE_DEFINITION_VERSION,
                result.summary().metadata().metricDefinitionVersion());
        assertNotEquals(MetricsSchema.METRIC_DEFINITION_VERSION,
                result.summary().metadata().metricDefinitionVersion());
    }

    @Test
    void validatedRealReconciledEvidenceReachesExistingExplanationClient() throws Exception {
        Fixture fixture = successfulTransfer(tempDir.resolve("flow-success"), null);
        RealMetricsSummaryProvider provider = provider(fixture);
        RecordedSummary evidence = provider.lookup(fixture.selection()).summary();
        TransferSummary selected = selected(evidence, TransferState.COMPLETED, IntegrityStatus.VERIFIED);
        AtomicReference<ExplanationRequest> captured = new AtomicReference<>();
        ExplanationDraft draft = draft(evidence);
        ExplanationFlow flow = new ExplanationFlow(provider, request -> {
            captured.set(request);
            return draft;
        });

        ExplanationFlow.Result result = flow.explain(selected, "Explain the validated transfer.");

        assertEquals(ExplanationFlow.Status.EXPLAINED, result.status(), result::message);
        assertEquals(evidence, result.evidence());
        assertSame(draft, result.draft());
        assertEquals(evidence, captured.get().evidence());
        assertEquals(RecordedSummary.EvidenceScope.RECONCILED,
                captured.get().evidence().metadata().scope());
        assertEquals(EvidenceSource.REAL, captured.get().evidence().source());
    }

    @Test
    void successfulTransferWithNoEligibleRttSamplesReachesExplanationClient() throws Exception {
        Fixture fixture = successfulTransferWithoutRttSamples(tempDir.resolve("flow-no-rtt"));
        RealMetricsSummaryProvider provider = provider(fixture);

        SummaryProvider.LookupResult lookup = provider.lookup(fixture.selection());

        assertEquals(AVAILABLE, lookup.status(), lookup::toString);
        RecordedSummary evidence = lookup.summary();
        assertEquals(RecordedSummary.EvidenceScope.RECONCILED, evidence.metadata().scope());
        assertEquals("SUCCESS", evidence.metadata().senderTerminalOutcome());
        assertEquals(Boolean.TRUE, evidence.metadata().receiverIntegrityVerified());
        assertEquals(BigDecimal.ZERO, field(evidence, "rtt_sample_count").value());
        AtomicReference<ExplanationRequest> captured = new AtomicReference<>();
        ExplanationFlow flow = new ExplanationFlow(provider, request -> {
            captured.set(request);
            return draft(request.evidence());
        });

        ExplanationFlow.Result result = flow.explain(
                selected(evidence, TransferState.COMPLETED, IntegrityStatus.VERIFIED),
                "Explain this completed transfer, including unavailable RTT.");

        assertEquals(ExplanationFlow.Status.EXPLAINED, result.status(), result::message);
        assertNotNull(captured.get());
        for (String id : java.util.List.of("rtt_mean_ms", "rtt_p95_ms")) {
            RecordedSummary.Field metric = field(captured.get().evidence(), id);
            assertNull(metric.value(), id);
            assertEquals("RTT sampling produced no eligible samples",
                    fixture.senderMetrics().getUnavailableReasons().get(id), id);
            assertEquals(fixture.senderMetrics().getUnavailableReasons().get(id),
                    metric.unavailableReason(), id);
        }
        TransferMetrics reconciled = new PersistedEvidenceRepository(fixture.logsRoot())
                .lookup(fixture.applicationId().toString()).evidence().reconciled().summary().metrics();
        for (String id : java.util.List.of("payload_bytes_delivered", "throughput_mbps",
                "udp_payload_bytes_emitted", "protocol_overhead_bytes", "protocol_overhead_ratio")) {
            assertNotNull(field(captured.get().evidence(), id).value(), id);
            assertNull(field(captured.get().evidence(), id).unavailableReason(), id);
            assertFalse(reconciled.getUnavailableReasons().containsKey(id), id);
        }
    }

    @Test
    void missingOriginalRttReasonStillRejectsEvidence() throws Exception {
        Fixture fixture = successfulTransferWithoutRttSamples(tempDir.resolve("missing-rtt-reason"));
        Path endpointPath = fixture.senderDirectory().resolve("endpoint-sender.json");
        JsonObject endpoint = object(endpointPath);
        endpoint.getAsJsonObject("metrics").getAsJsonObject("unavailable_reasons")
                .remove("rtt_mean_ms");
        Files.writeString(endpointPath, endpoint + "\n");

        SummaryProvider.LookupResult result = provider(fixture).lookup(fixture.selection());

        assertState(REJECTED, result);
        assertEquals("VALIDATION_MISSING_UNAVAILABLE_REASON", result.reasonCode());
    }

    @Test
    void reconciledFailureBeforeDataPreservesUninitializedMetricsAndReceiverReasons() throws Exception {
        Fixture fixture = startAckLoss(tempDir.resolve("flow-no-data"));
        RealMetricsSummaryProvider provider = provider(fixture);

        SummaryProvider.LookupResult lookup = provider.lookup(fixture.selection());

        assertEquals(AVAILABLE, lookup.status(), lookup::toString);
        RecordedSummary evidence = lookup.summary();
        assertEquals(RecordedSummary.EvidenceScope.RECONCILED, evidence.metadata().scope());
        assertEquals("FAILED", evidence.metadata().senderTerminalOutcome());
        assertEquals("START_HANDSHAKE_TIMEOUT", evidence.metadata().failureCategory());
        assertNull(evidence.metadata().receiverIntegrityVerified());
        assertEquals(BigDecimal.ZERO, field(evidence, "packets_sent").value());
        assertEquals(BigDecimal.ZERO, field(evidence, "payload_bytes_delivered").value());
        AtomicReference<ExplanationRequest> captured = new AtomicReference<>();
        ExplanationFlow flow = new ExplanationFlow(provider, request -> {
            captured.set(request);
            return draft(request.evidence());
        });

        ExplanationFlow.Result result = flow.explain(
                selected(evidence, TransferState.FAILED, IntegrityStatus.UNCONFIRMED),
                "Explain why this transfer failed before sending DATA.");

        assertEquals(ExplanationFlow.Status.EXPLAINED, result.status(), result::message);
        assertNotNull(captured.get());
        for (String id : java.util.List.of("rtt_sample_count", "rtt_mean_ms", "rtt_p95_ms")) {
            RecordedSummary.Field metric = field(captured.get().evidence(), id);
            assertNull(metric.value(), id);
            assertEquals(fixture.senderMetrics().getUnavailableReasons().get(id),
                    metric.unavailableReason(), id);
        }
        RecordedSummary.Field ratio = field(captured.get().evidence(), "retransmission_ratio");
        assertNull(ratio.value());
        assertNotNull(ratio.unavailableReason());
        assertFalse(ratio.unavailableReason().isBlank());
        var persisted = new PersistedEvidenceRepository(fixture.logsRoot())
                .lookup(fixture.applicationId().toString()).evidence();
        TransferMetrics receiver = persisted.receiver().record().metrics();
        TransferMetrics reconciled = persisted.reconciled().summary().metrics();
        assertNull(reconciled.getIntegrityVerified());
        assertEquals("no completed SHA-256 comparison was observed",
                receiver.getUnavailableReasons().get("integrity_verified"));
        assertEquals(receiver.getUnavailableReasons().get("integrity_verified"),
                reconciled.getUnavailableReasons().get("integrity_verified"));
    }

    @Test
    void validatedSenderFinalFailureReachesClientWithMissingReceiverValuesIntact() throws Exception {
        Fixture fixture = startTimeout(tempDir.resolve("flow-sender-failure"));
        RealMetricsSummaryProvider provider = provider(fixture);
        RecordedSummary evidence = provider.lookup(fixture.selection()).summary();
        TransferSummary selected = selected(evidence, TransferState.FAILED, IntegrityStatus.UNCONFIRMED);
        AtomicReference<ExplanationRequest> captured = new AtomicReference<>();
        ExplanationFlow flow = new ExplanationFlow(provider, request -> {
            captured.set(request);
            return draft(evidence);
        });

        ExplanationFlow.Result result = flow.explain(selected, "Why did the sender fail?");

        assertEquals(ExplanationFlow.Status.EXPLAINED, result.status(), result::message);
        assertEquals(RecordedSummary.EvidenceScope.SENDER_FINAL,
                captured.get().evidence().metadata().scope());
        RecordedSummary.Field delivered = field(captured.get().evidence(), "payload_bytes_delivered");
        assertNull(delivered.value());
        assertNotNull(delivered.unavailableReason());
        assertEquals(BigDecimal.ZERO, field(captured.get().evidence(), "packets_acked").value());
    }

    @Test
    void finishAckFailureRequestKeepsSenderFailureAndReceiverIntegritySeparate() throws Exception {
        Fixture fixture = finishAckLoss(tempDir.resolve("flow-finish-timeout"));
        RealMetricsSummaryProvider provider = provider(fixture);
        RecordedSummary evidence = provider.lookup(fixture.selection()).summary();
        TransferSummary selected = selected(evidence, TransferState.FAILED, IntegrityStatus.UNCONFIRMED);
        AtomicReference<ExplanationRequest> captured = new AtomicReference<>();
        ExplanationFlow flow = new ExplanationFlow(provider, request -> {
            captured.set(request);
            return draft(evidence);
        });

        assertEquals(ExplanationFlow.Status.EXPLAINED,
                flow.explain(selected, "Separate sender and receiver outcomes.").status());
        assertEquals("FAILED", captured.get().evidence().metadata().senderTerminalOutcome());
        assertEquals(Boolean.TRUE, captured.get().evidence().metadata().receiverIntegrityVerified());
        assertEquals(IntegrityStatus.UNCONFIRMED, captured.get().integrity());
    }

    @Test
    void explanationClientFailureRetainsValidatedRealEvidence() throws Exception {
        Fixture fixture = successfulTransfer(tempDir.resolve("flow-client-failure"), null);
        RealMetricsSummaryProvider provider = provider(fixture);
        RecordedSummary evidence = provider.lookup(fixture.selection()).summary();
        ExplanationFlow flow = new ExplanationFlow(provider, request -> {
            throw new IllegalStateException("private model failure");
        });

        ExplanationFlow.Result result = flow.explain(
                selected(evidence, TransferState.COMPLETED, IntegrityStatus.VERIFIED), "Explain.");

        assertEquals(ExplanationFlow.Status.EXPLANATION_UNAVAILABLE, result.status());
        assertEquals(evidence, result.evidence());
        assertNull(result.draft());
        assertFalse(result.message().contains("private"));
    }

    @Test
    void cliExplainsExactRealApplicationAndProtocolWithValidatedEvidence() throws Exception {
        Fixture fixture = successfulTransfer(tempDir.resolve("cli-real-explain"), null);
        RealMetricsSummaryProvider provider = provider(fixture);
        RecordedSummary evidence = provider.lookup(fixture.selection()).summary();
        TransferSummary selected = selected(evidence, TransferState.COMPLETED, IntegrityStatus.VERIFIED);
        AtomicReference<ExplanationRequest> captured = new AtomicReference<>();
        ExplanationFlow flow = new ExplanationFlow(provider, request -> {
            captured.set(request);
            return draft(evidence);
        });
        TransferService service = new TransferService() {
            @Override public TransferStart start(TransferRequest request) { throw new AssertionError(); }
            @Override public TransferSnapshot status(UUID transferId) {
                assertEquals(evidence.transferId(), transferId);
                return selected.finalSnapshot();
            }
            @Override public TransferSummary summary(UUID transferId) {
                assertEquals(evidence.runId(), transferId);
                return selected;
            }
        };
        var configuration = TransferConfiguration.localhost(tempDir,
                Map.of("fixture", Path.of("storage/outgoing/fixture.bin")));
        var output = new StringWriter();
        var cli = new TransferCli(service, configuration, new StringReader(""),
                new PrintWriter(output), request -> new CommandProposal.Unsupported("unused"), flow);

        assertTrue(cli.handleLine("explain {\"run_id\":\"" + evidence.runId()
                + "\",\"question\":\"Explain validated real evidence.\"}"));
        String text = output.toString();

        assertNotNull(captured.get());
        assertEquals(evidence.transferId(), captured.get().evidence().transferId());
        assertEquals(evidence.protocolTransferId(), captured.get().evidence().protocolTransferId());
        assertTrue(text.contains("EXPLAINED:"), text);
        assertTrue(text.contains("Evidence [REAL]"), text);
        assertTrue(text.contains("evidence_scope=RECONCILED"), text);
        assertTrue(text.contains("sender_outcome=SUCCESS"), text);
        assertTrue(text.contains("receiver_integrity_verified=true"), text);
        assertTrue(text.contains("protocol_transfer_id=" + evidence.protocolTransferId()), text);
        assertTrue(text.contains("Validated source references"), text);
        assertFalse(text.contains("Evidence [SYNTHETIC]"), text);
    }

    @Test
    void cliKeepsValidatedRealEvidenceVisibleWhenExplanationClientFails() throws Exception {
        Fixture fixture = successfulTransfer(tempDir.resolve("cli-client-failure"), null);
        RealMetricsSummaryProvider provider = provider(fixture);
        RecordedSummary evidence = provider.lookup(fixture.selection()).summary();
        TransferSummary selected = selected(evidence, TransferState.COMPLETED, IntegrityStatus.VERIFIED);
        ExplanationFlow flow = new ExplanationFlow(provider, request -> {
            throw new IllegalStateException("private client failure");
        });
        TransferService service = new TransferService() {
            @Override public TransferStart start(TransferRequest request) { throw new AssertionError(); }
            @Override public TransferSnapshot status(UUID transferId) { return selected.finalSnapshot(); }
            @Override public TransferSummary summary(UUID transferId) { return selected; }
        };
        var configuration = TransferConfiguration.localhost(tempDir,
                Map.of("fixture", Path.of("storage/outgoing/fixture.bin")));
        var output = new StringWriter();
        var cli = new TransferCli(service, configuration, new StringReader(""),
                new PrintWriter(output), request -> new CommandProposal.Unsupported("unused"), flow);

        assertTrue(cli.handleLine("explain {\"run_id\":\"" + evidence.runId()
                + "\",\"question\":\"Explain.\"}"));
        String text = output.toString();

        assertTrue(text.contains("EXPLANATION_UNAVAILABLE:"), text);
        assertTrue(text.contains("Evidence [REAL]"), text);
        assertTrue(text.contains("evidence_scope=RECONCILED"), text);
        assertTrue(text.contains("Validated source references"), text);
        assertTrue(text.contains("No accepted GPT answer is available"), text);
        assertFalse(text.contains("private client failure"), text);
    }

    private RealMetricsSummaryProvider provider(Fixture fixture) throws IOException {
        return new RealMetricsSummaryProvider(new PersistedEvidenceRepository(fixture.logsRoot()));
    }

    private Fixture successfulTransfer(Path root, String receiverApplicationId) throws Exception {
        Files.createDirectories(root);
        Path logs = root.resolve("logs");
        Path input = Files.write(root.resolve("input.bin"), new byte[]{1, 2, 3, 4});
        Path output = root.resolve("output.bin");
        UUID applicationId = UUID.randomUUID();
        String receiverRun = "receiver-" + UUID.randomUUID();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (UdpChannel receiverChannel = new UdpChannel(0);
             UdpChannel senderChannel = new UdpChannel()) {
            TransferContext.Builder receiverContext = TransferContext
                    .builder(TransferContext.Endpoint.RECEIVER).runId(receiverRun);
            if (receiverApplicationId != null) {
                receiverContext.applicationTransferId(receiverApplicationId);
            }
            ReceiverEngine receiver = new ReceiverEngine(
                    receiverChannel, 1_000, 1_000, 60, receiverContext.build());
            SenderEngine sender = sender(senderChannel, receiverChannel.getLocalPort(), applicationId,
                    200, 2);
            var receiverLogger = receiver.enableEventLogging(logs);
            var senderLogger = sender.enableEventLogging(logs);
            Future<TransferResult> receiverFuture = executor.submit(
                    () -> receiver.receiveFile(output.toString()));
            assertTrue(sender.sendFile(input.toString()).isSuccess());
            assertTrue(receiverFuture.get(3, TimeUnit.SECONDS).isSuccess());
            return new Fixture(logs, applicationId, receiverRun, sender.getProtocolTransferId(),
                    senderLogger.getRunDirectory(), receiverLogger.getRunDirectory(),
                    sender.getMetricsSnapshot());
        } finally {
            executor.shutdownNow();
        }
    }

    private Fixture startTimeout(Path root) throws Exception {
        Files.createDirectories(root);
        Path logs = root.resolve("logs");
        Path input = Files.writeString(root.resolve("input.txt"), "timeout");
        UUID applicationId = UUID.randomUUID();
        int unusedPort;
        try (UdpChannel probe = new UdpChannel(0)) {
            unusedPort = probe.getLocalPort();
        }
        try (UdpChannel channel = new UdpChannel()) {
            SenderEngine sender = sender(channel, unusedPort, applicationId, 20, 1);
            var logger = sender.enableEventLogging(logs);
            assertFalse(sender.sendFile(input.toString()).isSuccess());
            return new Fixture(logs, applicationId, null, sender.getProtocolTransferId(),
                    logger.getRunDirectory(), null, sender.getMetricsSnapshot());
        }
    }

    private Fixture successfulTransferWithoutRttSamples(Path root) throws Exception {
        Files.createDirectories(root);
        Path logs = root.resolve("logs");
        byte[] payload = {1, 2, 3, 4};
        Path input = Files.write(root.resolve("input.bin"), payload);
        Path output = root.resolve("output.bin");
        UUID applicationId = UUID.randomUUID();
        String receiverRun = "receiver-" + UUID.randomUUID();
        CountDownLatch retransmitted = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (UdpChannel receiverChannel = new AckAfterRetransmissionChannel(retransmitted);
             UdpChannel senderChannel = new RetransmissionSignallingChannel(retransmitted)) {
            ReceiverEngine receiver = new ReceiverEngine(receiverChannel, 1_000, 1_000, 60,
                    TransferContext.builder(TransferContext.Endpoint.RECEIVER)
                            .runId(receiverRun).build());
            SenderEngine sender = new SenderEngine(senderChannel, InetAddress.getLoopbackAddress(),
                    receiverChannel.getLocalPort(), 4, 1, 200, 3, 200, 3, 200, 3,
                    TransferContext.builder(TransferContext.Endpoint.SENDER)
                            .runId(applicationId.toString())
                            .applicationTransferId(applicationId.toString())
                            .fileAttribution("approved-file:fixture").build());
            var receiverLogger = receiver.enableEventLogging(logs);
            var senderLogger = sender.enableEventLogging(logs);
            Future<TransferResult> receiverFuture = executor.submit(
                    () -> receiver.receiveFile(output.toString()));
            assertTrue(sender.sendFile(input.toString()).isSuccess());
            assertTrue(receiverFuture.get(3, TimeUnit.SECONDS).isSuccess());
            assertArrayEquals(payload, Files.readAllBytes(output));
            TransferMetrics metrics = sender.getMetricsSnapshot();
            assertTrue(metrics.getRetransmissions() > 0);
            assertEquals(0L, metrics.getRttSampleCount());
            assertNull(metrics.getRttMeanMs());
            assertNull(metrics.getRttP95Ms());
            return new Fixture(logs, applicationId, receiverRun, sender.getProtocolTransferId(),
                    senderLogger.getRunDirectory(), receiverLogger.getRunDirectory(), metrics);
        } finally {
            executor.shutdownNow();
        }
    }

    private Fixture startAckLoss(Path root) throws Exception {
        Files.createDirectories(root);
        Path logs = root.resolve("logs");
        Path input = Files.write(root.resolve("input.bin"), new byte[]{1, 2, 3, 4});
        Path output = root.resolve("output.bin");
        UUID applicationId = UUID.randomUUID();
        String receiverRun = "receiver-" + UUID.randomUUID();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (UdpChannel receiverChannel = new UdpChannel(0);
             UdpChannel senderChannel = new DroppedControlMessageChannel(MessageType.START_ACK)) {
            ReceiverEngine receiver = new ReceiverEngine(receiverChannel, 1_000, 600, 60,
                    TransferContext.builder(TransferContext.Endpoint.RECEIVER)
                            .runId(receiverRun).build());
            SenderEngine sender = new SenderEngine(senderChannel, InetAddress.getLoopbackAddress(),
                    receiverChannel.getLocalPort(), 4, 1, 200, 2, 100, 1, 200, 2,
                    TransferContext.builder(TransferContext.Endpoint.SENDER)
                            .runId(applicationId.toString())
                            .applicationTransferId(applicationId.toString())
                            .fileAttribution("approved-file:fixture").build());
            var receiverLogger = receiver.enableEventLogging(logs);
            var senderLogger = sender.enableEventLogging(logs);
            Future<TransferResult> receiverFuture = executor.submit(
                    () -> receiver.receiveFile(output.toString()));
            assertFalse(sender.sendFile(input.toString()).isSuccess());
            assertFalse(receiverFuture.get(3, TimeUnit.SECONDS).isSuccess());
            assertEquals(0L, sender.getMetricsSnapshot().getPacketsSent());
            assertNull(sender.getMetricsSnapshot().getRttSampleCount());
            return new Fixture(logs, applicationId, receiverRun, sender.getProtocolTransferId(),
                    senderLogger.getRunDirectory(), receiverLogger.getRunDirectory(),
                    sender.getMetricsSnapshot());
        } finally {
            executor.shutdownNow();
        }
    }

    private Fixture finishAckLoss(Path root) throws Exception {
        Files.createDirectories(root);
        Path logs = root.resolve("logs");
        Path input = Files.write(root.resolve("input.bin"), new byte[]{5, 6, 7});
        Path output = root.resolve("output.bin");
        UUID applicationId = UUID.randomUUID();
        String receiverRun = "receiver-" + UUID.randomUUID();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (UdpChannel receiverChannel = new UdpChannel(0);
             UdpChannel senderChannel = new DroppedControlMessageChannel(MessageType.FINISH_ACK)) {
            ReceiverEngine receiver = new ReceiverEngine(receiverChannel, 1_000, 1_000, 100,
                    TransferContext.builder(TransferContext.Endpoint.RECEIVER)
                            .runId(receiverRun).build());
            SenderEngine sender = sender(senderChannel, receiverChannel.getLocalPort(), applicationId,
                    20, 1);
            var receiverLogger = receiver.enableEventLogging(logs);
            var senderLogger = sender.enableEventLogging(logs);
            Future<TransferResult> receiverFuture = executor.submit(
                    () -> receiver.receiveFile(output.toString()));
            assertFalse(sender.sendFile(input.toString()).isSuccess());
            assertTrue(receiverFuture.get(3, TimeUnit.SECONDS).isSuccess());
            return new Fixture(logs, applicationId, receiverRun, sender.getProtocolTransferId(),
                    senderLogger.getRunDirectory(), receiverLogger.getRunDirectory(),
                    sender.getMetricsSnapshot());
        } finally {
            executor.shutdownNow();
        }
    }

    private static SenderEngine sender(UdpChannel channel, int port, UUID applicationId,
                                       int finishTimeout, int finishRetries) throws Exception {
        return new SenderEngine(channel, InetAddress.getLoopbackAddress(), port,
                4, 1, 100, 2, 100, 2, finishTimeout, finishRetries,
                TransferContext.builder(TransferContext.Endpoint.SENDER)
                        .runId(applicationId.toString())
                        .applicationTransferId(applicationId.toString())
                        .fileAttribution("approved-file:fixture")
                        .build());
    }

    private static void setState(Path directory, String state) throws IOException {
        Path path = directory.resolve("run-state.json");
        JsonObject object = object(path);
        object.addProperty("recording_state", state);
        Files.writeString(path, object + "\n");
    }

    private static JsonObject object(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void assertState(SummaryProvider.Status expected,
                                    SummaryProvider.LookupResult result) {
        assertEquals(expected, result.status(), result::toString);
        assertNull(result.summary());
        assertNotNull(result.reasonCode());
        assertNotNull(result.reason());
    }

    private static void assertFields(Map<String, RecordedSummary.Field> fields,
                                     RecordedSummary.Kind kind, String... ids) {
        for (String id : ids) {
            assertEquals(kind, fields.get(id).kind(), id);
        }
    }

    private static void assertMetric(Map<String, RecordedSummary.Field> fields,
                                     TransferMetrics source, String id, Number expected) {
        RecordedSummary.Field field = fields.get(id);
        if (expected == null) {
            assertNull(field.value(), id);
            assertEquals(source.getUnavailableReasons().get(id), field.unavailableReason(), id);
            return;
        }
        BigDecimal value = expected instanceof Long || expected instanceof Integer
                ? BigDecimal.valueOf(expected.longValue())
                : BigDecimal.valueOf(expected.doubleValue());
        assertEquals(0, value.compareTo(field.value()), id);
        assertNull(field.unavailableReason(), id);
    }

    private static RecordedSummary.Field field(RecordedSummary summary, String id) {
        return summary.fields().stream().filter(value -> id.equals(value.id())).findFirst().orElseThrow();
    }

    private static ExplanationDraft draft(RecordedSummary evidence) {
        RecordedSummary.Field field = evidence.fields().stream()
                .filter(value -> value.value() != null).findFirst().orElseThrow();
        return new ExplanationDraft(evidence.runId(), evidence.transferId(),
                java.util.List.of(new ExplanationDraft.Observation(
                        "The supplied value is preserved from validated evidence.",
                        java.util.List.of(new ExplanationDraft.Reference(
                                field.id(), field.value(), field.unit())))),
                java.util.List.of(), java.util.List.of("Validated evidence remains bounded by its recorded scope."));
    }

    private static TransferSummary selected(RecordedSummary evidence, TransferState state,
                                            IntegrityStatus integrity) {
        TransferContext context = TransferContext.builder(TransferContext.Endpoint.SENDER)
                .runId(evidence.runId().toString())
                .applicationTransferId(evidence.transferId().toString())
                .protocolTransferId(evidence.protocolTransferId())
                .schemaVersion(MetricsSchema.ENDPOINT_RECORD_SCHEMA_VERSION)
                .metricDefinitionVersion(MetricsSchema.METRIC_DEFINITION_VERSION)
                .evidenceSource(TransferMetrics.EvidenceSource.REAL)
                .build();
        var authoritative = context.newMetricsBuilder()
                .captureTimestamp(evidence.capturedAt())
                .finalizationTimestamp(evidence.capturedAt())
                .transferSuccess(state == TransferState.COMPLETED)
                .failureReason(state == TransferState.FAILED ? "validated sender failure" : null)
                .build();
        LiveMetricsSnapshot live = new LiveMetricsSnapshot(context, authoritative,
                new MetricsCollector.EndpointEmissionObservations(null, false),
                state == TransferState.COMPLETED
                        ? LiveMetricsSnapshot.LifecycleState.SUCCEEDED
                        : LiveMetricsSnapshot.LifecycleState.FAILED,
                evidence.capturedAt(), null, null, null,
                new MetricsCollector.SenderObservations(0, false, 0, 0, false, 0), null);
        TransferError error = state == TransferState.FAILED
                ? new TransferError(TransferError.Code.TRANSFER_FAILED, "validated sender failure") : null;
        TransferSnapshot snapshot = new TransferSnapshot(
                evidence.transferId(), evidence.runId(), evidence.protocolTransferId(), state,
                evidence.capturedAt(), EvidenceSource.REAL,
                nettransfer.control.TransferMetrics.fromLive(live), error);
        TransferRequest request = new TransferRequest(UUID.randomUUID(), evidence.transferId(),
                "approved-file", "approved-receiver", Path.of("selection-only.bin"),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 9000),
                new TransferSettings(1024, 4096, 4, 200, 5));
        return new TransferSummary(request, snapshot, integrity, "validated terminal selection");
    }

    private record Fixture(Path logsRoot, UUID applicationId, String receiverRunId,
                           UUID protocolId, Path senderDirectory, Path receiverDirectory,
                           TransferMetrics senderMetrics) {
        SummaryProvider.Selection selection() {
            return SummaryProvider.Selection.applicationTransfer(applicationId, protocolId);
        }
    }

    private static final class DroppedControlMessageChannel extends UdpChannel {
        private final MessageType droppedType;

        private DroppedControlMessageChannel(MessageType droppedType) throws SocketException {
            this.droppedType = droppedType;
        }

        @Override
        public ReceivedDatagram receive() throws IOException {
            while (true) {
                ReceivedDatagram datagram = super.receive();
                byte[] data = datagram.data();
                if (data.length == 0 || data[0] != '{') {
                    return datagram;
                }
                ControlMessage message = ControlMessage.fromJson(
                        new String(data, StandardCharsets.UTF_8));
                if (message.getType() != droppedType) {
                    return datagram;
                }
            }
        }
    }

    /** Delays the first DATA ACK until an actual retransmission makes its RTT ambiguous. */
    private static final class AckAfterRetransmissionChannel extends UdpChannel {
        private final CountDownLatch retransmitted;
        private boolean firstAck = true;

        private AckAfterRetransmissionChannel(CountDownLatch retransmitted) throws SocketException {
            super(0);
            this.retransmitted = retransmitted;
        }

        @Override
        public void send(byte[] data, InetAddress address, int port) throws IOException {
            if (firstAck && data.length > 0 && data[0] != '{'
                    && PacketDecoder.decode(data).getType() == MessageType.ACK) {
                firstAck = false;
                try {
                    if (!retransmitted.await(3, TimeUnit.SECONDS)) {
                        throw new IOException("Test sender did not retransmit DATA before ACK release");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for test retransmission", exception);
                }
            }
            super.send(data, address, port);
        }
    }

    private static final class RetransmissionSignallingChannel extends UdpChannel {
        private final CountDownLatch retransmitted;
        private int dataEmissions;

        private RetransmissionSignallingChannel(CountDownLatch retransmitted) throws SocketException {
            this.retransmitted = retransmitted;
        }

        @Override
        public void send(byte[] data, InetAddress address, int port) throws IOException {
            super.send(data, address, port);
            if (data.length > 0 && data[0] != '{') {
                var packet = PacketDecoder.decode(data);
                if (packet.getType() == MessageType.DATA && packet.getSeqNum() == 0
                        && ++dataEmissions == 2) {
                    retransmitted.countDown();
                }
            }
        }
    }
}
