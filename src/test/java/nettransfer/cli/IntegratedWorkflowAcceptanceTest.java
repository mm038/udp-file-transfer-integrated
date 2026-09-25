package nettransfer.cli;

import nettransfer.control.TransferSnapshot;
import nettransfer.control.TransferState;
import nettransfer.control.command.CommandProposal;
import nettransfer.control.command.TransferConfiguration;
import nettransfer.control.engine.RealTransferService;
import nettransfer.explanation.ExplanationDraft;
import nettransfer.explanation.ExplanationFlow;
import nettransfer.explanation.ExplanationRequest;
import nettransfer.explanation.RealMetricsSummaryProvider;
import nettransfer.explanation.RecordedSummary;
import nettransfer.explanation.SummaryProvider;
import nettransfer.integrity.FileHashUtil;
import nettransfer.metrics.EvidenceLookupResult;
import nettransfer.metrics.LiveMetricsSnapshot;
import nettransfer.metrics.MetricsSchema;
import nettransfer.metrics.PersistedEvidenceRepository;
import nettransfer.metrics.TransferContext;
import nettransfer.net.UdpChannel;
import nettransfer.protocol.ControlMessage;
import nettransfer.protocol.MessageType;
import nettransfer.transfer.ReceiverEngine;
import nettransfer.transfer.TransferResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static nettransfer.metrics.EvidenceLookupResult.Status.AVAILABLE;
import static org.junit.jupiter.api.Assertions.*;

/** Genuine loopback acceptance across CLI, UDP, persistence, reconciliation and explanation. */
@Timeout(30)
class IntegratedWorkflowAcceptanceTest {
    private static final Pattern TRANSFER_ID = Pattern.compile("transfer_id=([0-9a-f-]{36})");

    @TempDir
    Path root;

    @Test
    void realCliTransferProducesLiveStatusReconciledEvidenceAndOfflineExplanation() throws Exception {
        Path inputRoot = Files.createDirectories(root.resolve("storage/outgoing"));
        byte[] expectedBytes = new byte[2_500];
        for (int index = 0; index < expectedBytes.length; index++) {
            expectedBytes[index] = (byte) (index * 31);
        }
        Path source = Files.write(inputRoot.resolve("acceptance.bin"), expectedBytes);
        Path received = root.resolve("received.bin");
        Path logs = root.resolve("logs");
        String receiverRunId = "receiver-" + UUID.randomUUID();
        AtomicInteger explanationCalls = new AtomicInteger();
        AtomicReference<ExplanationRequest> explained = new AtomicReference<>();
        ExecutorService receiverWorker = Executors.newSingleThreadExecutor();

        try (GatedStartAckChannel receiverChannel = new GatedStartAckChannel();
             RealTransferService service = new RealTransferService(logs)) {
            var receiverContext = TransferContext.builder(TransferContext.Endpoint.RECEIVER)
                    .runId(receiverRunId)
                    .build();
            ReceiverEngine receiver = new ReceiverEngine(
                    receiverChannel, 2_000, 2_000, 1_000, receiverContext);
            var receiverLogger = receiver.enableEventLogging(logs);
            Future<TransferResult> receiverResult = receiverWorker.submit(
                    () -> receiver.receiveFile(received.toString()));

            var repository = new PersistedEvidenceRepository(logs);
            var provider = new RealMetricsSummaryProvider(repository);
            var flow = new ExplanationFlow(provider, request -> {
                explanationCalls.incrementAndGet();
                explained.set(request);
                RecordedSummary.Field cited = request.evidence().fields().stream()
                        .filter(field -> field.value() != null)
                        .findFirst().orElseThrow();
                return new ExplanationDraft(request.evidence().runId(), request.evidence().transferId(),
                        java.util.List.of(new ExplanationDraft.Observation(
                                "Validated REAL evidence supplied this recorded value.",
                                java.util.List.of(new ExplanationDraft.Reference(
                                        cited.id(), cited.value(), cited.unit())))),
                        java.util.List.of(),
                        java.util.List.of("Offline deterministic acceptance stub; no live GPT call."));
            });
            var configuration = new TransferConfiguration(root.toAbsolutePath(),
                    Map.of("acceptance", Path.of("storage/outgoing/acceptance.bin")),
                    Map.of("receiver-a", new InetSocketAddress(
                            InetAddress.getLoopbackAddress(), receiverChannel.getLocalPort())));
            var output = new StringWriter();
            var cli = new TransferCli(service, configuration, new StringReader(""),
                    new PrintWriter(output), request -> new CommandProposal.Unsupported("unused"), flow);

            String started = command(cli, output,
                    "start_transfer {\"file_id\":\"acceptance\",\"receiver_id\":\"receiver-a\","
                            + "\"window_bytes\":1024,\"timeout_ms\":1000}");
            UUID applicationId = transferId(started);
            assertTrue(receiverChannel.startAckReady.await(3, TimeUnit.SECONDS));

            String liveText = command(cli, output, "status " + applicationId);
            TransferSnapshot liveStatus = service.status(applicationId);
            assertEquals(TransferState.RUNNING, liveStatus.state());
            assertEquals(applicationId, liveStatus.runId());
            assertNotNull(liveStatus.protocolTransferId());
            assertEquals(nettransfer.control.EvidenceSource.REAL, liveStatus.evidenceSource());
            assertEquals(LiveMetricsSnapshot.LifecycleState.AWAITING_START,
                    liveStatus.liveMetrics().lifecycleState());
            assertEquals((long) expectedBytes.length,
                    liveStatus.liveMetrics().metrics().getFileSizeBytes());
            assertTrue(liveText.contains("engine_lifecycle=AWAITING_START"), liveText);
            assertTrue(liveText.contains("sender_confirmed_acked_payload_bytes=unavailable"), liveText);
            assertTrue(liveText.contains("sender ACK progress has not been observed"), liveText);
            assertTrue(liveText.contains("retransmissions=0"), liveText);
            assertTrue(liveText.contains("sender_ack_based_rate_mbps=unavailable"), liveText);
            assertFalse(liveText.contains("reconciled_throughput"), liveText);

            receiverChannel.releaseStartAck.countDown();
            TransferSnapshot terminal = awaitTerminal(service, applicationId);
            assertEquals(TransferState.COMPLETED, terminal.state(),
                    terminal.error() == null ? "" : terminal.error().message());
            assertEquals(LiveMetricsSnapshot.LifecycleState.SUCCEEDED,
                    terminal.liveMetrics().lifecycleState());
            assertEquals((long) expectedBytes.length,
                    terminal.liveMetrics().senderAcknowledgedPayloadBytes());
            assertTrue(terminal.liveMetrics().endpointEmission().accountingComplete());
            assertSame(terminal, service.status(applicationId),
                    "terminal sender snapshot must remain retained after session closure");

            String terminalText = command(cli, output, "status " + applicationId);
            assertTrue(terminalText.contains("[REAL] COMPLETED"), terminalText);
            assertTrue(terminalText.contains("sender_ack_progress_percent=100"), terminalText);
            assertTrue(terminalText.contains("evidence_finality=FINAL"), terminalText);

            String pendingText = command(cli, output,
                    "explain {\"run_id\":\"" + applicationId
                            + "\",\"question\":\"Explain the real transfer.\"}");
            assertTrue(pendingText.contains("EVIDENCE_PENDING:"), pendingText);
            assertEquals(0, explanationCalls.get(), "PENDING evidence must not reach the client");

            TransferResult receiverTerminal = receiverResult.get(4, TimeUnit.SECONDS);
            assertTrue(receiverTerminal.isSuccess(), receiverTerminal::getMessage);
            assertArrayEquals(expectedBytes, Files.readAllBytes(received));
            assertEquals(FileHashUtil.sha256Hex(source.toString()),
                    FileHashUtil.sha256Hex(received.toString()));

            Path senderDirectory = logs.resolve("standalone").resolve(applicationId.toString());
            assertAll(
                    () -> assertTrue(Files.isRegularFile(senderDirectory.resolve("events-sender.jsonl"))),
                    () -> assertTrue(Files.isRegularFile(senderDirectory.resolve("run-state.json"))),
                    () -> assertTrue(Files.isRegularFile(senderDirectory.resolve("endpoint-sender.json"))),
                    () -> assertTrue(Files.isRegularFile(receiverLogger.getRunDirectory()
                            .resolve("events-receiver.jsonl"))),
                    () -> assertTrue(Files.isRegularFile(receiverLogger.getRunDirectory()
                            .resolve("run-state.json"))),
                    () -> assertTrue(Files.isRegularFile(receiverLogger.getRunDirectory()
                            .resolve("endpoint-receiver.json"))));

            EvidenceLookupResult reconciled = repository.lookup(
                    applicationId.toString(), applicationId.toString());
            assertEquals(AVAILABLE, reconciled.status(), reconciled::toString);
            assertTrue(reconciled.evidence().isReconciled());
            var summary = reconciled.evidence().reconciled().summary();
            var metrics = summary.metrics();
            assertEquals(applicationId.toString(), summary.applicationTransferId());
            assertEquals(applicationId.toString(), summary.senderRunId());
            assertEquals(receiverRunId, summary.receiverRunId());
            assertEquals(terminal.protocolTransferId().toString(), summary.protocolTransferId());
            assertEquals(MetricsSchema.SUMMARY_SCHEMA_VERSION, summary.schemaVersion());
            assertEquals(MetricsSchema.METRIC_DEFINITION_VERSION, summary.metricDefinitionVersion());
            assertEquals((long) expectedBytes.length, metrics.getFileSizeBytes());
            assertEquals((long) expectedBytes.length, metrics.getPayloadBytesDelivered());
            assertTrue(metrics.getTransferSuccess());
            assertTrue(metrics.getIntegrityVerified());
            assertTrue(metrics.getPacketsSent() >= 3L);
            assertTrue(metrics.getPacketsReceived() >= 3L);
            assertTrue(metrics.getAcksReceived() >= metrics.getPacketsAcked());
            assertEquals(3L, metrics.getPacketsAcked());
            assertEquals(0L, metrics.getRetransmissions());
            assertEquals(0L, metrics.getPacketsTimedOut());
            assertEquals(0L, metrics.getPacketsDuplicated());
            assertTrue(metrics.getTransferTimeSec() > 0.0);
            assertTrue(metrics.getThroughputMbps() > 0.0);
            assertTrue(metrics.getRttSampleCount() > 0L);
            assertNotNull(metrics.getRttMeanMs());
            assertNotNull(metrics.getRttP95Ms());
            assertTrue(metrics.getUdpPayloadBytesEmitted() > expectedBytes.length);
            assertEquals(metrics.getUdpPayloadBytesEmitted() - metrics.getPayloadBytesDelivered(),
                    metrics.getProtocolOverheadBytes());
            assertEquals(metrics.getProtocolOverheadBytes().doubleValue()
                            / metrics.getUdpPayloadBytesEmitted().doubleValue(),
                    metrics.getProtocolOverheadRatio(), 1.0e-12);
            assertNull(metrics.getPacketsDropped());
            assertNotNull(metrics.getUnavailableReasons().get("packets_dropped"));

            String originalSummary = Files.readString(
                    reconciled.evidence().reconciled().summaryPath());
            EvidenceLookupResult reused = repository.lookup(
                    applicationId.toString(), applicationId.toString());
            assertEquals(AVAILABLE, reused.status());
            assertEquals(originalSummary,
                    Files.readString(reused.evidence().reconciled().summaryPath()),
                    "existing reconciled output must be validated and reused without overwrite");

            SummaryProvider.LookupResult provided = provider.lookup(
                    SummaryProvider.Selection.applicationTransfer(
                            applicationId, terminal.protocolTransferId()));
            assertEquals(SummaryProvider.Status.AVAILABLE, provided.status());
            assertEquals(RecordedSummary.EvidenceScope.RECONCILED,
                    provided.summary().metadata().scope());

            String explanationText = command(cli, output,
                    "explain {\"run_id\":\"" + applicationId
                            + "\",\"question\":\"Explain the real transfer.\"}");
            assertTrue(explanationText.contains("EXPLAINED:"), explanationText);
            assertTrue(explanationText.contains("Evidence [REAL]"), explanationText);
            assertTrue(explanationText.contains("evidence_scope=RECONCILED"), explanationText);
            assertTrue(explanationText.contains("sender_outcome=SUCCESS"), explanationText);
            assertTrue(explanationText.contains("receiver_integrity_verified=true"), explanationText);
            assertTrue(explanationText.contains("Validated source references"), explanationText);
            assertEquals(1, explanationCalls.get());

            ExplanationRequest request = explained.get();
            assertNotNull(request);
            assertEquals(applicationId, request.evidence().runId());
            assertEquals(applicationId, request.evidence().transferId());
            assertEquals(terminal.protocolTransferId(), request.evidence().protocolTransferId());
            assertEquals(nettransfer.control.EvidenceSource.REAL, request.evidence().source());
            Map<String, RecordedSummary.Field> fields = request.evidence().fields().stream()
                    .collect(Collectors.toMap(RecordedSummary.Field::id, field -> field));
            assertEquals("bytes", fields.get("payload_bytes_delivered").unit());
            assertEquals(expectedBytes.length,
                    fields.get("payload_bytes_delivered").value().longValueExact());
            assertEquals(RecordedSummary.Kind.OBSERVED,
                    fields.get("payload_bytes_delivered").kind());
            assertNull(fields.get("packets_dropped").value());
            assertEquals(metrics.getUnavailableReasons().get("packets_dropped"),
                    fields.get("packets_dropped").unavailableReason());
            assertEquals(0, fields.get("retransmissions").value().signum(),
                    "observed zero must remain distinct from unavailable");
        } finally {
            receiverWorker.shutdownNow();
            assertTrue(receiverWorker.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static String command(TransferCli cli, StringWriter output, String command) {
        output.getBuffer().setLength(0);
        assertTrue(cli.handleLine(command));
        return output.toString();
    }

    private static UUID transferId(String output) {
        var matcher = TRANSFER_ID.matcher(output);
        assertTrue(matcher.find(), output);
        return UUID.fromString(matcher.group(1));
    }

    private static TransferSnapshot awaitTerminal(RealTransferService service, UUID id) {
        return assertTimeoutPreemptively(Duration.ofSeconds(6), () -> {
            TransferSnapshot snapshot;
            while ((snapshot = service.status(id)).state() == TransferState.RUNNING) {
                Thread.onSpinWait();
                Thread.sleep(2);
            }
            return snapshot;
        });
    }

    private static final class GatedStartAckChannel extends UdpChannel {
        private final CountDownLatch startAckReady = new CountDownLatch(1);
        private final CountDownLatch releaseStartAck = new CountDownLatch(1);

        private GatedStartAckChannel() throws SocketException { }

        @Override
        public void send(byte[] data, InetAddress address, int port) throws IOException {
            if (data.length > 0 && data[0] == '{') {
                ControlMessage message = ControlMessage.fromJson(
                        new String(data, StandardCharsets.UTF_8));
                if (message.getType() == MessageType.START_ACK) {
                    startAckReady.countDown();
                    try {
                        if (!releaseStartAck.await(3, TimeUnit.SECONDS)) {
                            throw new IOException("Test START_ACK gate was not released");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Test START_ACK gate interrupted", exception);
                    }
                }
            }
            super.send(data, address, port);
        }

        @Override
        public void close() {
            releaseStartAck.countDown();
            super.close();
        }
    }
}
