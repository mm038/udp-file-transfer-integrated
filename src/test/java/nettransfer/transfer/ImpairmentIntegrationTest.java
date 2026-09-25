package nettransfer.transfer;

import com.google.gson.JsonParser;
import nettransfer.control.IntegrityStatus;
import nettransfer.control.TransferRequest;
import nettransfer.control.TransferSettings;
import nettransfer.control.TransferSnapshot;
import nettransfer.control.TransferState;
import nettransfer.control.engine.RealTransferService;
import nettransfer.explanation.ExplanationDraft;
import nettransfer.explanation.ExplanationFlow;
import nettransfer.explanation.ExplanationRequest;
import nettransfer.explanation.RealMetricsSummaryProvider;
import nettransfer.explanation.RecordedSummary;
import nettransfer.explanation.SummaryProvider;
import nettransfer.integrity.FileHashUtil;
import nettransfer.metrics.MetricsExporter;
import nettransfer.metrics.PersistedEvidenceRepository;
import nettransfer.metrics.TransferContext;
import nettransfer.metrics.TransferMetrics;
import nettransfer.net.ImpairmentSettings;
import nettransfer.net.UdpChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/** Real UDP checks of the production simulator, persisted evidence, and explanation boundary. */
@Timeout(20)
class ImpairmentIntegrationTest {
    @TempDir(cleanup = CleanupMode.NEVER)
    Path temporaryRoot;

    @Test
    void activeZeroProfilePreservesBytesAndProducesObservedZeroDrops() throws Exception {
        Fixture fixture = transfer("baseline", settings(0, 0, "baseline"),
                8_197, 1_000, 5, 3_000, 150);

        assertSuccessful(fixture);
        TransferMetrics combined = reconciled(fixture);
        assertEquals(0L, combined.getPacketsDropped());
        assertEquals(0.0, combined.getPacketLossRate());
        assertEquals(0.0, combined.getDelayMs());
        assertEquals(0L, countEvents(fixture.receiverEvents(), "IMPAIRMENT_DROPPED"));
        assertNull(fixture.senderMetrics().getPacketsDropped(),
                "The sender alone cannot observe DATA suppressed at the receiver");
        assertEquals(0L, fixture.receiverMetrics().getPacketsDropped());

        RecordedSummary summary = available(fixture);
        assertEquals(0, field(summary, "packets_dropped").value().compareTo(BigDecimal.ZERO));
        assertNull(field(summary, "packets_dropped").unavailableReason());
    }

    @Test
    void seededTwoPercentLossRecoversAndReconcilesActualDropEvents() throws Exception {
        // Java Random(42) selects several drops within this many eligible DATA arrivals.
        Fixture fixture = transfer("loss", settings(2, 0, "loss-two-percent"),
                512 * 1_024 + 17, 150, 8, 3_000, 150);

        assertSuccessful(fixture);
        long drops = countEvents(fixture.receiverEvents(), "IMPAIRMENT_DROPPED");
        assertTrue(drops > 0, "This fixed seed must exercise real simulated DATA loss");
        assertTrue(fixture.senderMetrics().getRetransmissions() > 0);
        assertTrue(fixture.senderMetrics().getPacketsTimedOut() > 0);
        TransferMetrics combined = reconciled(fixture);
        assertEquals(drops, combined.getPacketsDropped());
        assertEquals(2.0, combined.getPacketLossRate(),
                "Configured probability is separate from the observed count");
        assertEquals(drops, fixture.receiverMetrics().getPacketsDropped());
        assertNull(fixture.senderMetrics().getPacketsDropped());
        assertEquals(drops, field(available(fixture), "packets_dropped").value().longValueExact());
    }

    @Test
    void delayInBothDirectionsAppearsInEligibleRttSamples() throws Exception {
        int oneWayDelay = 80;
        Fixture fixture = transfer("delay-rtt", settings(0, oneWayDelay, "delay-rtt"),
                2 * 1_024 + 17, 2_000, 5, 4_000, 200);

        assertSuccessful(fixture);
        TransferMetrics combined = reconciled(fixture);
        assertEquals(0L, combined.getRetransmissions());
        assertEquals(3L, combined.getRttSampleCount());
        assertNotNull(combined.getRttMeanMs());
        assertTrue(combined.getRttMeanMs() >= 2 * oneWayDelay - 10,
                "RTT must include delayed DATA delivery and delayed ACK delivery: "
                        + combined.getRttMeanMs());
        assertEquals((double) oneWayDelay, combined.getDelayMs());
        assertEquals(0L, combined.getPacketsDropped());
        assertEquals(3L, countEvents(fixture.receiverEvents(), "IMPAIRMENT_DELAYED"));
        assertEquals(3L, countEvents(fixture.senderEvents(), "IMPAIRMENT_DELAYED"));
        assertNotNull(field(available(fixture), "rtt_mean_ms").value());
    }

    @Test
    void delayedWindowKeepsMultiplePacketsInFlightWithoutSerializingDelivery() throws Exception {
        Fixture fixture = transfer("delay-window-four", settings(0, 40, "delay-window-four"),
                16 * 1_024, 2_000, 5, 4_000, 200, 4);

        assertSuccessful(fixture);
        TransferMetrics combined = reconciled(fixture);
        assertEquals(4L, combined.getWindowPackets());
        assertEquals(0L, combined.getRetransmissions());
        assertEquals(0L, combined.getPacketsTimedOut());
        assertEquals(0L, combined.getPacketsDropped());
        assertEquals(40.0, combined.getDelayMs());

        List<String> senderEvents = eventTypes(fixture.senderEvents());
        int firstAcceptedAck = senderEvents.indexOf("DATA_ACK_ACCEPTED");
        assertTrue(firstAcceptedAck >= 0, "The sender must accept DATA acknowledgement progress");
        assertEquals(4L, senderEvents.subList(0, firstAcceptedAck).stream()
                        .filter("DATA_SEND_ATTEMPT"::equals).count(),
                "The whole four-packet window must be sent before waiting for its first ACK");

        List<String> receiverEvents = eventTypes(fixture.receiverEvents());
        int firstDelivered = receiverEvents.indexOf("IMPAIRMENT_DELIVERED");
        assertTrue(firstDelivered >= 0, "Queued DATA must eventually reach the receiver engine");
        assertTrue(receiverEvents.subList(0, firstDelivered).stream()
                        .filter("IMPAIRMENT_DELAYED"::equals).count() >= 2,
                "The simulator must queue multiple DATA packets while the first delivery is delayed");
        assertEquals(16L, countEvents(fixture.receiverEvents(), "IMPAIRMENT_DELAYED"));
        assertEquals(16L, countEvents(fixture.senderEvents(), "IMPAIRMENT_DELAYED"));
        assertEquals(4L, field(available(fixture), "window_packets").value().longValueExact());
    }

    @Test
    void delayLongerThanDataTimeoutCausesRealRecoveryWithoutInventedRtt() throws Exception {
        Fixture fixture = transfer("delay-timeout", settings(0, 160, "delay-timeout"),
                17, 60, 10, 3_000, 450);

        assertSuccessful(fixture);
        TransferMetrics combined = reconciled(fixture);
        assertTrue(combined.getRetransmissions() > 0,
                "The sender must keep running its timeout while ACK delivery is delayed");
        assertTrue(combined.getPacketsTimedOut() > 0);
        assertEquals(0L, combined.getPacketsDropped());
        assertEquals(0L, combined.getRttSampleCount(),
                "The retransmitted sequence is not an eligible RTT observation");
        assertNull(combined.getRttMeanMs());
        assertNotNull(combined.getUnavailableReasons().get("rtt_mean_ms"));
        RecordedSummary summary = available(fixture);
        assertNull(field(summary, "rtt_mean_ms").value());
        assertNotNull(field(summary, "rtt_mean_ms").unavailableReason());
    }

    @Test
    void completeDataLossFailsWithinBoundsAndRetainsUnconfirmedIntegrity() throws Exception {
        Fixture fixture = transfer("complete-loss", settings(100, 0, "complete-loss"),
                17, 100, 2, 1_200, 150);

        assertFalse(fixture.senderResult().isSuccess());
        assertFalse(fixture.receiverResult().isSuccess());
        TransferMetrics combined = reconciled(fixture);
        long drops = countEvents(fixture.receiverEvents(), "IMPAIRMENT_DROPPED");
        assertTrue(drops > 0);
        assertEquals(fixture.senderMetrics().getPacketsSent(), drops,
                "Every emitted DATA attempt reaches the configured receive-side drop decision");
        assertEquals(drops, combined.getPacketsDropped());
        assertEquals(0L, combined.getPacketsReceived());
        assertEquals(0L, combined.getPayloadBytesDelivered());
        assertNull(combined.getIntegrityVerified(), "No FINISH integrity check was possible");
        RecordedSummary summary = available(fixture);
        assertEquals("FAILED", summary.metadata().senderTerminalOutcome());
        assertNull(summary.metadata().receiverIntegrityVerified());
        assertEquals(drops, field(summary, "packets_dropped").value().longValueExact());
    }

    @Test
    void consoleBackendProfileReachesPersistedEvidenceAndExplanationClient() throws Exception {
        Path root = evidenceRoot("service-explanation");
        Path logs = root.resolve("logs");
        Path input = writeInput(root, 2_065);
        Path output = root.resolve("received.bin");
        ImpairmentSettings profile = settings(0, 35, "service-delay");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (UdpChannel receiverChannel = new UdpChannel(0, profile);
             RealTransferService service = new RealTransferService(1_000, logs, profile)) {
            ReceiverEngine receiver = new ReceiverEngine(receiverChannel, 2_000, 3_000, 200,
                    TransferContext.builder(TransferContext.Endpoint.RECEIVER).build());
            receiver.enableEventLogging(logs);
            Future<TransferResult> receiving = worker.submit(() -> receiver.receiveFile(output.toString()));
            UUID applicationId = UUID.randomUUID();
            TransferRequest request = new TransferRequest(UUID.randomUUID(), applicationId,
                    "approved-file", "approved-receiver", input,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), receiverChannel.getLocalPort()),
                    new TransferSettings(1_024, 1_024, 1, 1_000, 5));

            assertEquals(applicationId, service.start(request).transferId());
            assertTrue(receiving.get(8, TimeUnit.SECONDS).isSuccess());
            TransferSnapshot terminal = awaitTerminal(service, applicationId);
            assertEquals(TransferState.COMPLETED, terminal.state());
            assertEquals(FileHashUtil.sha256Hex(input.toString()), FileHashUtil.sha256Hex(output.toString()));
            assertEquals(IntegrityStatus.VERIFIED, service.summary(applicationId).integrity());

            var provider = new RealMetricsSummaryProvider(new PersistedEvidenceRepository(logs));
            var lookup = provider.lookup(SummaryProvider.Selection.applicationTransfer(
                    applicationId, terminal.protocolTransferId()));
            assertEquals(SummaryProvider.Status.AVAILABLE, lookup.status(), lookup::toString);
            assertEquals(35L, field(lookup.summary(), "delay_ms").value().longValueExact());
            assertEquals(0L, field(lookup.summary(), "packets_dropped").value().longValueExact());
            AtomicReference<ExplanationRequest> captured = new AtomicReference<>();
            ExplanationFlow flow = new ExplanationFlow(provider, explanation -> {
                captured.set(explanation);
                RecordedSummary evidence = explanation.evidence();
                RecordedSummary.Field dropField = field(evidence, "packets_dropped");
                return new ExplanationDraft(evidence.runId(), evidence.transferId(),
                        List.of(new ExplanationDraft.Observation(
                                "The active receiver simulator recorded the supplied DATA drop count.",
                                List.of(new ExplanationDraft.Reference(dropField.id(),
                                        dropField.value(), dropField.unit())))),
                        List.of(), List.of("This count covers deliberate DATA drops by the configured simulator."));
            });

            var explained = flow.explain(service.summary(applicationId), "Explain the configured delay and observed drops.");

            assertEquals(ExplanationFlow.Status.EXPLAINED, explained.status(), explained::message);
            assertNotNull(captured.get());
            assertEquals(RecordedSummary.EvidenceScope.RECONCILED, captured.get().evidence().metadata().scope());
            assertEquals(applicationId, captured.get().evidence().transferId());
            assertEquals(terminal.protocolTransferId(), captured.get().evidence().protocolTransferId());
            assertEquals(35L, field(captured.get().evidence(), "delay_ms").value().longValueExact());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private Fixture transfer(String label, ImpairmentSettings profile, int inputSize,
                             int dataTimeout, int retryLimit, int receiverInactivity,
                             int completionGrace) throws Exception {
        return transfer(label, profile, inputSize, dataTimeout, retryLimit,
                receiverInactivity, completionGrace, 1);
    }

    private Fixture transfer(String label, ImpairmentSettings profile, int inputSize,
                             int dataTimeout, int retryLimit, int receiverInactivity,
                             int completionGrace, int windowPackets) throws Exception {
        Path root = evidenceRoot(label);
        Path logs = root.resolve("logs");
        Path input = writeInput(root, inputSize);
        Path output = root.resolve("received.bin");
        UUID applicationId = UUID.randomUUID();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (UdpChannel receiverChannel = new UdpChannel(0, profile);
             UdpChannel senderChannel = new UdpChannel(profile)) {
            ReceiverEngine receiver = new ReceiverEngine(receiverChannel, 2_000,
                    receiverInactivity, completionGrace,
                    TransferContext.builder(TransferContext.Endpoint.RECEIVER).build());
            SenderEngine sender = new SenderEngine(senderChannel, InetAddress.getLoopbackAddress(),
                    receiverChannel.getLocalPort(), 1_024, windowPackets, dataTimeout, retryLimit,
                    500, 2, 500, 2,
                    TransferContext.builder(TransferContext.Endpoint.SENDER)
                            .runId(applicationId.toString()).applicationTransferId(applicationId.toString())
                            .fileAttribution("approved-file:impairment-fixture").build());
            var receiverLogger = receiver.enableEventLogging(logs);
            var senderLogger = sender.enableEventLogging(logs);
            Future<TransferResult> receiving = worker.submit(() -> receiver.receiveFile(output.toString()));

            TransferResult sent = sender.sendFile(input.toString());
            TransferResult received = receiving.get(8, TimeUnit.SECONDS);
            assertNull(senderLogger.getLoggingFailure());
            assertNull(receiverLogger.getLoggingFailure());
            assertTrue(senderLogger.isFinalized());
            assertTrue(receiverLogger.isFinalized());
            return new Fixture(logs, input, output, applicationId, sender.getProtocolTransferId(),
                    senderLogger.getEventsFile(), receiverLogger.getEventsFile(),
                    sender.getMetricsSnapshot(), receiver.getMetricsSnapshot(), sent, received);
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private Path evidenceRoot(String label) throws Exception {
        String configured = System.getProperty("nettransfer.testEvidenceRoot");
        Path base = configured == null || configured.isBlank() ? temporaryRoot : Path.of(configured);
        Path root = Files.createDirectories(base.resolve(label + "-" + UUID.randomUUID()));
        System.out.println("Preserved impairment integration evidence: " + root.toAbsolutePath());
        return root;
    }

    private static Path writeInput(Path root, int size) throws Exception {
        byte[] bytes = new byte[size];
        new Random(7).nextBytes(bytes);
        return Files.write(root.resolve("source.bin"), bytes);
    }

    private static ImpairmentSettings settings(double loss, int delay, String scenario) {
        return new ImpairmentSettings(true, loss, delay, 42L, scenario);
    }

    private static void assertSuccessful(Fixture fixture) throws Exception {
        assertTrue(fixture.senderResult().isSuccess(), fixture.senderResult()::toString);
        assertTrue(fixture.receiverResult().isSuccess(), fixture.receiverResult()::toString);
        assertEquals(FileHashUtil.sha256Hex(fixture.input().toString()),
                FileHashUtil.sha256Hex(fixture.output().toString()));
        assertEquals(Files.size(fixture.input()), fixture.receiverMetrics().getPayloadBytesDelivered());
        assertEquals(Boolean.TRUE, fixture.receiverMetrics().getIntegrityVerified());
    }

    private static TransferMetrics reconciled(Fixture fixture) throws Exception {
        var lookup = new PersistedEvidenceRepository(fixture.logs()).lookup(fixture.applicationId().toString());
        assertTrue(lookup.isAvailable(), lookup::toString);
        assertTrue(lookup.evidence().isReconciled());
        var validated = MetricsExporter.readValidatedReconciled(lookup.evidence().reconciled().outputDirectory());
        return validated.summary().metrics();
    }

    private static RecordedSummary available(Fixture fixture) throws Exception {
        var lookup = new RealMetricsSummaryProvider(new PersistedEvidenceRepository(fixture.logs()))
                .lookup(SummaryProvider.Selection.applicationTransfer(fixture.applicationId(), fixture.protocolId()));
        assertEquals(SummaryProvider.Status.AVAILABLE, lookup.status(), lookup::toString);
        assertEquals(RecordedSummary.EvidenceScope.RECONCILED, lookup.summary().metadata().scope());
        return lookup.summary();
    }

    private static RecordedSummary.Field field(RecordedSummary summary, String id) {
        return summary.fields().stream().filter(value -> id.equals(value.id())).findFirst().orElseThrow();
    }

    private static long countEvents(Path events, String eventType) throws Exception {
        try (var lines = Files.lines(events)) {
            return lines.map(line -> JsonParser.parseString(line).getAsJsonObject())
                    .filter(event -> eventType.equals(event.get("event_type").getAsString())).count();
        }
    }

    private static List<String> eventTypes(Path events) throws Exception {
        try (var lines = Files.lines(events)) {
            return lines.map(line -> JsonParser.parseString(line).getAsJsonObject()
                    .get("event_type").getAsString()).toList();
        }
    }

    private static TransferSnapshot awaitTerminal(RealTransferService service, UUID id) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            TransferSnapshot snapshot = service.status(id);
            if (snapshot.state() != TransferState.RUNNING) {
                return snapshot;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        fail("Service did not publish its terminal result within the bounded wait");
        return null;
    }

    private record Fixture(Path logs, Path input, Path output, UUID applicationId, UUID protocolId,
                           Path senderEvents, Path receiverEvents,
                           TransferMetrics senderMetrics, TransferMetrics receiverMetrics,
                           TransferResult senderResult, TransferResult receiverResult) { }
}
