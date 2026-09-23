package nettransfer.metrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import nettransfer.net.UdpChannel;
import nettransfer.protocol.ControlMessage;
import nettransfer.protocol.MessageType;
import nettransfer.transfer.ReceiverEngine;
import nettransfer.transfer.SenderEngine;
import nettransfer.transfer.TransferResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static nettransfer.metrics.EvidenceLookupResult.Status.AVAILABLE;
import static nettransfer.metrics.EvidenceLookupResult.Status.INCOMPLETE;
import static nettransfer.metrics.EvidenceLookupResult.Status.PENDING;
import static nettransfer.metrics.EvidenceLookupResult.Status.REJECTED;
import static nettransfer.metrics.EvidenceLookupResult.Status.UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class PersistedEvidenceRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void exactSenderRunAndApplicationIdentityAreRequired() throws Exception {
        Fixture fixture = successfulTransfer(tempDir.resolve("exact"), null);
        PersistedEvidenceRepository repository = new PersistedEvidenceRepository(fixture.logsRoot());

        EvidenceLookupResult found = repository.lookupSender(
                fixture.senderRunId(), fixture.senderRunId());
        assertEquals(AVAILABLE, found.status());
        assertEquals(fixture.senderRunId(), found.evidence().sender().record().runId());
        assertEquals(fixture.protocolId().toString(),
                found.evidence().sender().record().protocolTransferId());

        EvidenceLookupResult wrongApplication = repository.lookupSender(
                fixture.senderRunId(), UUID.randomUUID().toString());
        assertEquals(REJECTED, wrongApplication.status());
        assertEquals("SENDER_IDENTITY_MISMATCH", wrongApplication.reasonCode());
    }

    @Test
    void finalizedEndpointsAreAssociatedByProtocolAndReconciled() throws Exception {
        Fixture fixture = successfulTransfer(tempDir.resolve("reconcile"), null);
        PersistedEvidenceRepository repository = new PersistedEvidenceRepository(fixture.logsRoot());

        EvidenceLookupResult receiver = repository.lookupReceiver(fixture.protocolId());
        assertEquals(AVAILABLE, receiver.status());
        assertEquals(fixture.receiverRunId(), receiver.evidence().receiver().record().runId());

        EvidenceLookupResult result = repository.lookup(fixture.senderRunId());
        assertEquals(AVAILABLE, result.status(), result::toString);
        assertTrue(result.evidence().isReconciled());
        assertEquals(fixture.protocolId().toString(),
                result.evidence().reconciled().summary().protocolTransferId());
        assertEquals(fixture.senderRunId(),
                result.evidence().reconciled().summary().applicationTransferId());
        assertEquals(fixture.senderRunId(),
                result.evidence().reconciled().summary().metrics().getApplicationTransferId());
    }

    @Test
    void existingFinalizedSummaryIsValidatedAndReused() throws Exception {
        Fixture fixture = successfulTransfer(tempDir.resolve("reuse"), null);
        PersistedEvidenceRepository repository = new PersistedEvidenceRepository(fixture.logsRoot());
        EvidenceLookupResult first = repository.lookup(fixture.senderRunId());
        Path summaryPath = first.evidence().reconciled().summaryPath();
        String original = Files.readString(summaryPath);

        EvidenceLookupResult second = repository.lookup(fixture.senderRunId());
        EvidenceLookupResult direct = repository.lookupReconciled(fixture.protocolId());

        assertEquals(AVAILABLE, second.status());
        assertEquals(AVAILABLE, direct.status());
        assertEquals(original, Files.readString(summaryPath));
        assertEquals(first.evidence().reconciled().summary().protocolTransferId(),
                second.evidence().reconciled().summary().protocolTransferId());
    }

    @Test
    void successfulSenderWithoutReceiverIsUnavailableNotGuessed() throws Exception {
        Fixture fixture = successfulTransfer(tempDir.resolve("missing-receiver"), null);
        Files.move(fixture.receiverDirectory(), tempDir.resolve("receiver-outside-root"));
        PersistedEvidenceRepository repository = new PersistedEvidenceRepository(fixture.logsRoot());

        EvidenceLookupResult result = repository.lookup(fixture.senderRunId());

        assertEquals(UNAVAILABLE, result.status());
        assertEquals("RECEIVER_NOT_FOUND", result.reasonCode());
        assertNull(result.evidence());
    }

    @Test
    void recordingAndIncompleteReceiverStatesRemainDistinct() throws Exception {
        Fixture recording = successfulTransfer(tempDir.resolve("recording"), null);
        setState(recording.receiverDirectory(), "RECORDING", null);
        EvidenceLookupResult pending = new PersistedEvidenceRepository(recording.logsRoot())
                .lookup(recording.senderRunId());
        assertEquals(PENDING, pending.status());

        Fixture incomplete = successfulTransfer(tempDir.resolve("incomplete"), null);
        setState(incomplete.receiverDirectory(), "INCOMPLETE", null);
        EvidenceLookupResult partial = new PersistedEvidenceRepository(incomplete.logsRoot())
                .lookup(incomplete.senderRunId());
        assertEquals(INCOMPLETE, partial.status());

        Fixture loggingFailed = successfulTransfer(tempDir.resolve("logging-failed"), null);
        setState(loggingFailed.receiverDirectory(), "LOGGING_FAILED", "simulated persistence failure");
        EvidenceLookupResult failed = new PersistedEvidenceRepository(loggingFailed.logsRoot())
                .lookup(loggingFailed.senderRunId());
        assertEquals(INCOMPLETE, failed.status());
    }

    @Test
    void incompleteSenderNeverExposesEndpointEvidence() throws Exception {
        Fixture fixture = successfulTransfer(tempDir.resolve("sender-incomplete"), null);
        setState(fixture.senderDirectory(), "INCOMPLETE", null);

        EvidenceLookupResult result = new PersistedEvidenceRepository(fixture.logsRoot())
                .lookup(fixture.senderRunId());

        assertEquals(INCOMPLETE, result.status());
        assertNull(result.evidence());
    }

    @Test
    void finalizedSenderFailureIsAvailableWithoutReceiver() throws Exception {
        SenderOnlyFixture fixture = startTimeout(tempDir.resolve("sender-failure"));
        PersistedEvidenceRepository repository = new PersistedEvidenceRepository(fixture.logsRoot());

        EvidenceLookupResult result = repository.lookup(fixture.senderRunId());

        assertEquals(AVAILABLE, result.status());
        assertTrue(result.evidence().isSenderOnly());
        assertEquals("FAILED", result.evidence().sender().record().terminalOutcome());
        assertFalse(result.evidence().sender().record().metrics().getTransferSuccess());
        assertEquals("SENDER_ONLY_FINALIZED_FAILURE", result.reasonCode());
    }

    @Test
    void finishAckLossPreservesReceiverLocalSuccessAndSenderFailure() throws Exception {
        Fixture fixture = finishAckLoss(tempDir.resolve("finish-loss"));

        EvidenceLookupResult result = new PersistedEvidenceRepository(fixture.logsRoot())
                .lookup(fixture.senderRunId());

        assertEquals(AVAILABLE, result.status(), result::toString);
        assertTrue(result.evidence().isReconciled());
        assertFalse(result.evidence().reconciled().summary().metrics().getTransferSuccess());
        assertTrue(result.evidence().reconciled().summary().metrics().getIntegrityVerified());
        assertEquals("FAILED", result.evidence().sender().record().terminalOutcome());
        assertEquals("SUCCESS", result.evidence().receiver().record().terminalOutcome());
    }

    @Test
    void conflictingApplicationAndProtocolIdentitiesAreRejected() throws Exception {
        Fixture applicationConflict = successfulTransfer(
                tempDir.resolve("application-conflict"), "receiver-application");
        EvidenceLookupResult appResult = new PersistedEvidenceRepository(applicationConflict.logsRoot())
                .lookup(applicationConflict.senderRunId());
        assertEquals(REJECTED, appResult.status());
        assertTrue(appResult.reasonCode().contains("EVIDENCE_CONFLICT"));

        Fixture protocolConflict = successfulTransfer(tempDir.resolve("protocol-conflict"), null);
        Path receiverRecord = protocolConflict.receiverDirectory().resolve("endpoint-receiver.json");
        JsonObject endpoint = object(receiverRecord);
        String different = UUID.randomUUID().toString();
        endpoint.addProperty("protocol_transfer_id", different);
        endpoint.getAsJsonObject("metrics").addProperty("protocol_transfer_id", different);
        Files.writeString(receiverRecord, endpoint + "\n");
        EvidenceLookupResult protocolResult = new PersistedEvidenceRepository(protocolConflict.logsRoot())
                .lookup(protocolConflict.senderRunId());
        assertEquals(REJECTED, protocolResult.status());
    }

    @Test
    void duplicateReceiverClaimsAreRejectedInsteadOfSelectingFirst() throws Exception {
        Fixture fixture = successfulTransfer(tempDir.resolve("duplicate"), null);
        Path duplicate = fixture.receiverDirectory().getParent().resolve("duplicate-receiver-run");
        copyTree(fixture.receiverDirectory(), duplicate);

        EvidenceLookupResult result = new PersistedEvidenceRepository(fixture.logsRoot())
                .lookup(fixture.senderRunId());

        assertEquals(REJECTED, result.status());
        assertEquals("AMBIGUOUS_RECEIVER_EVIDENCE", result.reasonCode());
    }

    @Test
    void malformedRecordsJsonlAndInconsistentStateAreRejected() throws Exception {
        Fixture malformed = successfulTransfer(tempDir.resolve("malformed"), null);
        Files.writeString(malformed.senderDirectory().resolve("endpoint-sender.json"), "{truncated");
        assertEquals(REJECTED, new PersistedEvidenceRepository(malformed.logsRoot())
                .lookup(malformed.senderRunId()).status());

        Fixture jsonl = successfulTransfer(tempDir.resolve("jsonl"), null);
        Files.writeString(jsonl.receiverDirectory().resolve("events-receiver.jsonl"),
                "not-json\n", java.nio.file.StandardOpenOption.APPEND);
        assertEquals(REJECTED, new PersistedEvidenceRepository(jsonl.logsRoot())
                .lookup(jsonl.senderRunId()).status());

        Fixture inconsistent = successfulTransfer(tempDir.resolve("state-mismatch"), null);
        JsonObject state = object(inconsistent.receiverDirectory().resolve("run-state.json"));
        state.addProperty("protocol_transfer_id", UUID.randomUUID().toString());
        Files.writeString(inconsistent.receiverDirectory().resolve("run-state.json"), state + "\n");
        assertEquals(REJECTED, new PersistedEvidenceRepository(inconsistent.logsRoot())
                .lookup(inconsistent.senderRunId()).status());
    }

    @Test
    void unsupportedSchemaSyntheticProvenanceAndTraversalAreRejected() throws Exception {
        Fixture schema = successfulTransfer(tempDir.resolve("schema"), null);
        JsonObject schemaRecord = object(schema.senderDirectory().resolve("endpoint-sender.json"));
        schemaRecord.addProperty("schema_version", "unsupported");
        Files.writeString(schema.senderDirectory().resolve("endpoint-sender.json"), schemaRecord + "\n");
        assertEquals(REJECTED, new PersistedEvidenceRepository(schema.logsRoot())
                .lookup(schema.senderRunId()).status());

        Fixture synthetic = successfulTransfer(tempDir.resolve("synthetic"), null);
        JsonObject syntheticRecord = object(synthetic.senderDirectory().resolve("endpoint-sender.json"));
        syntheticRecord.addProperty("evidence_source", "SYNTHETIC");
        syntheticRecord.getAsJsonObject("metrics").addProperty("evidence_source", "SYNTHETIC");
        Files.writeString(synthetic.senderDirectory().resolve("endpoint-sender.json"), syntheticRecord + "\n");
        assertEquals(REJECTED, new PersistedEvidenceRepository(synthetic.logsRoot())
                .lookup(synthetic.senderRunId()).status());

        Fixture traversal = successfulTransfer(tempDir.resolve("traversal"), null);
        JsonObject traversalRecord = object(traversal.senderDirectory().resolve("endpoint-sender.json"));
        traversalRecord.addProperty("event_log_reference", "../../outside.jsonl");
        Files.writeString(traversal.senderDirectory().resolve("endpoint-sender.json"), traversalRecord + "\n");
        assertEquals(REJECTED, new PersistedEvidenceRepository(traversal.logsRoot())
                .lookup(traversal.senderRunId()).status());
    }

    @Test
    void tamperedExistingSummaryIsRejectedAndNeverOverwritten() throws Exception {
        Fixture fixture = successfulTransfer(tempDir.resolve("tampered-summary"), null);
        PersistedEvidenceRepository repository = new PersistedEvidenceRepository(fixture.logsRoot());
        EvidenceLookupResult first = repository.lookup(fixture.senderRunId());
        Path summaryPath = first.evidence().reconciled().summaryPath();
        JsonObject summary = object(summaryPath);
        summary.getAsJsonObject("source_evidence")
                .addProperty("sender_event_log", "../../../../outside.jsonl");
        Files.writeString(summaryPath, summary + "\n");

        EvidenceLookupResult result = repository.lookup(fixture.senderRunId());

        assertEquals(REJECTED, result.status());
        assertEquals("../../../../outside.jsonl",
                object(summaryPath).getAsJsonObject("source_evidence")
                        .get("sender_event_log").getAsString());
    }

    @Test
    void lookupNeverFallsBackToLatestRunOrFilename() throws Exception {
        Path sharedRoot = tempDir.resolve("no-latest");
        Fixture first = successfulTransfer(sharedRoot, null);
        successfulTransfer(sharedRoot, null);
        PersistedEvidenceRepository repository = new PersistedEvidenceRepository(first.logsRoot());

        EvidenceLookupResult result = repository.lookup(UUID.randomUUID().toString());

        assertEquals(UNAVAILABLE, result.status());
        assertEquals("SENDER_NOT_FOUND", result.reasonCode());
    }

    private Fixture successfulTransfer(Path root, String receiverApplicationId) throws Exception {
        Path logs = root.resolve("logs");
        Files.createDirectories(root);
        Path input = Files.write(root.resolve("input.bin"), new byte[]{1, 2, 3, 4});
        Path output = root.resolve("output.bin");
        String senderRun = UUID.randomUUID().toString();
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
            SenderEngine sender = new SenderEngine(senderChannel, InetAddress.getLoopbackAddress(),
                    receiverChannel.getLocalPort(), 2, 1, 100, 2,
                    200, 2, 200, 2,
                    TransferContext.builder(TransferContext.Endpoint.SENDER)
                            .runId(senderRun).applicationTransferId(senderRun)
                            .fileAttribution("approved-file:fixture").build());
            EventLogger receiverLogger = receiver.enableEventLogging(logs);
            EventLogger senderLogger = sender.enableEventLogging(logs);
            Future<TransferResult> receiverFuture = executor.submit(
                    () -> receiver.receiveFile(output.toString()));
            TransferResult senderResult = sender.sendFile(input.toString());
            TransferResult receiverResult = receiverFuture.get(3, TimeUnit.SECONDS);
            assertTrue(senderResult.isSuccess());
            assertTrue(receiverResult.isSuccess());
            return new Fixture(logs, senderRun, receiverRun, sender.getProtocolTransferId(),
                    senderLogger.getRunDirectory(), receiverLogger.getRunDirectory());
        } finally {
            executor.shutdownNow();
        }
    }

    private SenderOnlyFixture startTimeout(Path root) throws Exception {
        Files.createDirectories(root);
        Path input = Files.writeString(root.resolve("input.txt"), "timeout");
        Path logs = root.resolve("logs");
        String runId = UUID.randomUUID().toString();
        int unusedPort;
        try (UdpChannel probe = new UdpChannel(0)) {
            unusedPort = probe.getLocalPort();
        }
        try (UdpChannel channel = new UdpChannel()) {
            SenderEngine sender = new SenderEngine(channel, InetAddress.getLoopbackAddress(),
                    unusedPort, 4, 1, 20, 1, 20, 1, 20, 1,
                    TransferContext.builder(TransferContext.Endpoint.SENDER)
                            .runId(runId).applicationTransferId(runId).build());
            EventLogger logger = sender.enableEventLogging(logs);
            TransferResult result = sender.sendFile(input.toString());
            assertFalse(result.isSuccess());
            return new SenderOnlyFixture(logs, runId, logger.getRunDirectory());
        }
    }

    private Fixture finishAckLoss(Path root) throws Exception {
        Files.createDirectories(root);
        Path input = Files.write(root.resolve("input.bin"), new byte[]{5, 6, 7});
        Path output = root.resolve("output.bin");
        Path logs = root.resolve("logs");
        String senderRun = UUID.randomUUID().toString();
        String receiverRun = "receiver-" + UUID.randomUUID();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (UdpChannel receiverChannel = new UdpChannel(0);
             DroppedFinishAckChannel senderChannel = new DroppedFinishAckChannel()) {
            ReceiverEngine receiver = new ReceiverEngine(receiverChannel, 1_000, 1_000, 100,
                    TransferContext.builder(TransferContext.Endpoint.RECEIVER)
                            .runId(receiverRun).build());
            SenderEngine sender = new SenderEngine(senderChannel, InetAddress.getLoopbackAddress(),
                    receiverChannel.getLocalPort(), 4, 1, 100, 2,
                    100, 1, 20, 1,
                    TransferContext.builder(TransferContext.Endpoint.SENDER)
                            .runId(senderRun).applicationTransferId(senderRun).build());
            EventLogger receiverLogger = receiver.enableEventLogging(logs);
            EventLogger senderLogger = sender.enableEventLogging(logs);
            Future<TransferResult> receiverFuture = executor.submit(
                    () -> receiver.receiveFile(output.toString()));
            TransferResult senderResult = sender.sendFile(input.toString());
            TransferResult receiverResult = receiverFuture.get(3, TimeUnit.SECONDS);
            assertFalse(senderResult.isSuccess());
            assertTrue(receiverResult.isSuccess());
            return new Fixture(logs, senderRun, receiverRun, sender.getProtocolTransferId(),
                    senderLogger.getRunDirectory(), receiverLogger.getRunDirectory());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void setState(Path runDirectory, String recordingState, String failure)
            throws IOException {
        Path statePath = runDirectory.resolve("run-state.json");
        JsonObject state = object(statePath);
        state.addProperty("recording_state", recordingState);
        if (failure == null) {
            state.add("logging_failure", com.google.gson.JsonNull.INSTANCE);
        } else {
            state.addProperty("logging_failure", failure);
        }
        Files.writeString(statePath, state + "\n");
    }

    private static JsonObject object(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted(Comparator.naturalOrder()).toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private record Fixture(Path logsRoot, String senderRunId, String receiverRunId,
                           UUID protocolId, Path senderDirectory, Path receiverDirectory) {}

    private record SenderOnlyFixture(Path logsRoot, String senderRunId, Path senderDirectory) {}

    private static final class DroppedFinishAckChannel extends UdpChannel {
        private DroppedFinishAckChannel() throws SocketException { }

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
                if (message.getType() != MessageType.FINISH_ACK) {
                    return datagram;
                }
            }
        }
    }
}
