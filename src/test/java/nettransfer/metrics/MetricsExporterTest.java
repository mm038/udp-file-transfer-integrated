package nettransfer.metrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import nettransfer.net.UdpChannel;
import nettransfer.transfer.ReceiverEngine;
import nettransfer.transfer.SenderEngine;
import nettransfer.transfer.TransferResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class MetricsExporterTest {
    @Test
    void reconcilesRealEndpointEvidenceAndCalculatesCombinedMetrics(@TempDir Path tempDir)
            throws Exception {
        Fixture fixture = successfulTransfer(tempDir);
        EndpointMetricsRecord sender = MetricsExporter.readEndpointRecord(
                fixture.senderDirectory().resolve("endpoint-sender.json"));
        EndpointMetricsRecord receiver = MetricsExporter.readEndpointRecord(
                fixture.receiverDirectory().resolve("endpoint-receiver.json"));

        MetricsExporter.ExportResult export = MetricsExporter.reconcile(
                fixture.senderDirectory(), fixture.receiverDirectory());
        FinalMetricsSummary summary = export.summary();
        long expectedEmission = Math.addExact(
                sender.endpointEmission().localUdpPayloadBytesEmitted(),
                receiver.endpointEmission().localUdpPayloadBytesEmitted());
        assertAll(
                () -> assertTrue(Files.isRegularFile(export.summaryPath())),
                () -> assertTrue(Files.isRegularFile(export.manifestPath())),
                () -> assertEquals("VERIFIED", summary.reconciliationStatus()),
                () -> assertEquals("COMPLETE", summary.evidenceCompleteness()),
                () -> assertEquals(sender.protocolTransferId(), summary.protocolTransferId()),
                () -> assertEquals("sender-export-run", summary.senderRunId()),
                () -> assertEquals("receiver-export-run", summary.receiverRunId()),
                () -> assertNull(summary.applicationTransferId()),
                () -> assertEquals(expectedEmission,
                        summary.metrics().getUdpPayloadBytesEmitted()),
                () -> assertEquals(expectedEmission - 3,
                        summary.metrics().getProtocolOverheadBytes()),
                () -> assertEquals((double) (expectedEmission - 3) / expectedEmission,
                        summary.metrics().getProtocolOverheadRatio()),
                () -> assertEquals(3L, summary.metrics().getPayloadBytesDelivered()),
                () -> assertTrue(summary.metrics().getTransferSuccess()),
                () -> assertTrue(summary.metrics().getIntegrityVerified()),
                () -> assertNotNull(summary.metrics().getTransferTimeSec()),
                () -> assertNotNull(summary.metrics().getThroughputMbps()),
                () -> assertEquals(sender.metrics().getRttSampleCount(),
                        summary.metrics().getRttSampleCount()),
                () -> assertNull(summary.metrics().getPacketsDropped()),
                () -> assertTrue(summary.metrics().getUnavailableReasons()
                        .containsKey("packets_dropped")),
                () -> assertDoesNotThrow(() -> JsonParser.parseString(
                        Files.readString(export.summaryPath())).getAsJsonObject()),
                () -> assertThrows(MetricsExporter.EvidenceException.class,
                        () -> MetricsExporter.reconcile(
                                fixture.senderDirectory(), fixture.receiverDirectory())));
    }

    @Test
    void rejectsDifferentProtocolUuidWithoutPublishingSummary(@TempDir Path tempDir)
            throws Exception {
        Fixture fixture = successfulTransfer(tempDir);
        Path receiverRecord = fixture.receiverDirectory().resolve("endpoint-receiver.json");
        JsonObject json = JsonParser.parseString(Files.readString(receiverRecord)).getAsJsonObject();
        String other = java.util.UUID.randomUUID().toString();
        json.addProperty("protocol_transfer_id", other);
        json.getAsJsonObject("metrics").addProperty("protocol_transfer_id", other);
        Files.writeString(receiverRecord, json.toString() + "\n");

        MetricsExporter.EvidenceException failure = assertThrows(
                MetricsExporter.EvidenceException.class,
                () -> MetricsExporter.reconcile(
                        fixture.senderDirectory(), fixture.receiverDirectory()));
        assertTrue(failure.getMessage().contains("protocol_transfer_id")
                || failure.getMessage().contains("protocol UUID"));
        assertFalse(Files.exists(fixture.scopeDirectory().resolve("reconciled")));
    }

    @Test
    void rejectsMalformedJsonlAndLeavesSourceEvidence(@TempDir Path tempDir) throws Exception {
        Fixture fixture = successfulTransfer(tempDir);
        Path receiverEvents = fixture.receiverDirectory().resolve("events-receiver.jsonl");
        long originalSize = Files.size(receiverEvents);
        Files.writeString(receiverEvents, "not-json\n", java.nio.file.StandardOpenOption.APPEND);

        MetricsExporter.EvidenceException failure = assertThrows(
                MetricsExporter.EvidenceException.class,
                () -> MetricsExporter.reconcile(
                        fixture.senderDirectory(), fixture.receiverDirectory()));
        assertEquals("MALFORMED_EVENT_LOG", failure.getCode());
        assertTrue(Files.size(receiverEvents) > originalSize);
    }

    @Test
    void rejectsRunningStateAndConflictingFileSize(@TempDir Path tempDir) throws Exception {
        Fixture running = successfulTransfer(tempDir.resolve("running"));
        Path statePath = running.receiverDirectory().resolve("run-state.json");
        JsonObject state = JsonParser.parseString(Files.readString(statePath)).getAsJsonObject();
        state.addProperty("recording_state", "RECORDING");
        Files.writeString(statePath, state.toString() + "\n");
        assertThrows(MetricsExporter.EvidenceException.class,
                () -> MetricsExporter.reconcile(running.senderDirectory(), running.receiverDirectory()));

        Fixture conflict = successfulTransfer(tempDir.resolve("conflict"));
        Path receiverRecord = conflict.receiverDirectory().resolve("endpoint-receiver.json");
        JsonObject endpoint = JsonParser.parseString(Files.readString(receiverRecord)).getAsJsonObject();
        endpoint.getAsJsonObject("metrics").addProperty("file_size_bytes", 99);
        Files.writeString(receiverRecord, endpoint.toString() + "\n");
        MetricsExporter.EvidenceException failure = assertThrows(
                MetricsExporter.EvidenceException.class,
                () -> MetricsExporter.reconcile(
                        conflict.senderDirectory(), conflict.receiverDirectory()));
        assertEquals("EVIDENCE_CONFLICT", failure.getCode());
    }

    @Test
    void preservesOneTrustedApplicationIdentityOrTheMatchingSharedIdentity(@TempDir Path tempDir)
            throws Exception {
        Fixture senderOnly = successfulTransfer(
                tempDir.resolve("sender-only"), "application-42", null);
        FinalMetricsSummary senderOnlySummary = MetricsExporter.reconcile(
                senderOnly.senderDirectory(), senderOnly.receiverDirectory()).summary();
        assertEquals("application-42", senderOnlySummary.applicationTransferId());
        assertEquals("application-42",
                senderOnlySummary.metrics().getApplicationTransferId());

        Fixture matching = successfulTransfer(
                tempDir.resolve("matching"), "application-43", "application-43");
        FinalMetricsSummary matchingSummary = MetricsExporter.reconcile(
                matching.senderDirectory(), matching.receiverDirectory()).summary();
        assertEquals("application-43", matchingSummary.applicationTransferId());
        assertEquals("application-43", matchingSummary.metrics().getApplicationTransferId());
    }

    @Test
    void rejectsConflictingApplicationIdentities(@TempDir Path tempDir) throws Exception {
        Fixture conflict = successfulTransfer(
                tempDir, "sender-application", "receiver-application");

        MetricsExporter.EvidenceException failure = assertThrows(
                MetricsExporter.EvidenceException.class,
                () -> MetricsExporter.reconcile(
                        conflict.senderDirectory(), conflict.receiverDirectory()));
        assertEquals("EVIDENCE_CONFLICT", failure.getCode());
        assertTrue(failure.getMessage().contains("application_transfer_id")
                || failure.getMessage().contains("application transfer ID"));
        assertFalse(Files.exists(conflict.scopeDirectory().resolve("reconciled")));
    }

    private static Fixture successfulTransfer(Path root) throws Exception {
        return successfulTransfer(root, null, null);
    }

    private static Fixture successfulTransfer(Path root, String senderApplicationId,
                                              String receiverApplicationId) throws Exception {
        Files.createDirectories(root);
        Path input = root.resolve("input.bin");
        Path output = root.resolve("output.bin");
        Files.write(input, new byte[]{1, 2, 3});
        Path logs = root.resolve("logs");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (UdpChannel receiverChannel = new UdpChannel(0);
             UdpChannel senderChannel = new UdpChannel()) {
            TransferContext.Builder receiverContext = TransferContext
                    .builder(TransferContext.Endpoint.RECEIVER)
                    .runId("receiver-export-run");
            if (receiverApplicationId != null) {
                receiverContext.applicationTransferId(receiverApplicationId);
            }
            TransferContext.Builder senderContext = TransferContext
                    .builder(TransferContext.Endpoint.SENDER)
                    .runId("sender-export-run");
            if (senderApplicationId != null) {
                senderContext.applicationTransferId(senderApplicationId);
            }
            ReceiverEngine receiver = new ReceiverEngine(receiverChannel, 1_000, 1_000, 50,
                    receiverContext.build());
            SenderEngine sender = new SenderEngine(senderChannel, InetAddress.getLoopbackAddress(),
                    receiverChannel.getLocalPort(), 2, 1, 100, 2,
                    200, 2, 200, 2,
                    senderContext.build());
            EventLogger receiverLogger = receiver.enableEventLogging(logs);
            EventLogger senderLogger = sender.enableEventLogging(logs);
            Future<TransferResult> receiverFuture = executor.submit(
                    () -> receiver.receiveFile(output.toString()));
            TransferResult senderResult = sender.sendFile(input.toString());
            TransferResult receiverResult = receiverFuture.get();
            assertTrue(senderResult.isSuccess());
            assertTrue(receiverResult.isSuccess());
            assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(output));
            return new Fixture(senderLogger.getRunDirectory(), receiverLogger.getRunDirectory(),
                    senderLogger.getRunDirectory().getParent());
        } finally {
            executor.shutdownNow();
        }
    }

    private record Fixture(Path senderDirectory, Path receiverDirectory, Path scopeDirectory) {}
}
