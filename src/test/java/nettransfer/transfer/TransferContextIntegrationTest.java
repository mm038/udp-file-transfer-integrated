package nettransfer.transfer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import nettransfer.metrics.TransferConfiguration;
import nettransfer.metrics.TransferContext;
import nettransfer.metrics.TransferMetrics;
import nettransfer.net.UdpChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransferContextIntegrationTest {
    @Test
    void senderPreservesCallerIdsAndCapturesEffectiveConfiguration() throws Exception {
        TransferConfiguration requested = TransferConfiguration.builder()
                .windowBytesRequested(2_048L)
                .windowPackets(2L)
                .timeoutMs(325L)
                .retryLimit(6L)
                .chunkSizeBytes(1_024L)
                .startHandshakeTimeoutMs(45L)
                .startRetryLimit(1L)
                .build();
        TransferContext supplied = TransferContext.builder(TransferContext.Endpoint.SENDER)
                .experimentId("experiment-label")
                .runId("trusted-run-id")
                .applicationTransferId("trusted-transfer-id")
                .configuration(requested)
                .build();

        try (UdpChannel channel = new UdpChannel()) {
            SenderEngine sender = new SenderEngine(
                    channel, InetAddress.getLoopbackAddress(), 9,
                    1_024, 2, 325, 6, 45, 1, supplied);
            TransferContext actual = sender.getTransferContext();

            assertAll(
                    () -> assertEquals("trusted-run-id", actual.getRunId()),
                    () -> assertEquals("trusted-transfer-id", actual.getApplicationTransferId()),
                    () -> assertEquals("experiment-label", actual.getExperimentId()),
                    () -> assertNull(actual.getProtocolTransferId()),
                    () -> assertEquals(1_024L, actual.getConfiguration().getChunkSizeBytes()),
                    () -> assertEquals(2_048L, actual.getConfiguration().getWindowBytesRequested()),
                    () -> assertEquals(2L, actual.getConfiguration().getWindowPackets()),
                    () -> assertEquals(325L, actual.getConfiguration().getTimeoutMs()),
                    () -> assertEquals(6L, actual.getConfiguration().getRetryLimit()),
                    () -> assertNull(actual.getConfiguration().getPacketLossRate()),
                    () -> assertEquals(
                            "no impairment configuration source",
                            actual.getUnavailableReasons().get("packet_loss_rate")));
        }
    }

    @Test
    void rejectsContextWhoseIdentityOrEffectiveConfigurationConflicts() throws Exception {
        TransferContext wrongEndpoint = TransferContext.builder(TransferContext.Endpoint.RECEIVER).build();
        TransferContext preassignedProtocol = TransferContext.builder(TransferContext.Endpoint.SENDER)
                .protocolTransferId(UUID.randomUUID())
                .build();
        TransferContext wrongTimeout = TransferContext.builder(TransferContext.Endpoint.SENDER)
                .configuration(TransferConfiguration.builder().timeoutMs(999L).build())
                .build();

        try (UdpChannel channel = new UdpChannel()) {
            assertAll(
                    () -> assertThrows(
                            IllegalArgumentException.class,
                            () -> new SenderEngine(channel, InetAddress.getLoopbackAddress(), 9,
                                    1_024, 1, 200, 5, wrongEndpoint)),
                    () -> assertThrows(
                            IllegalArgumentException.class,
                            () -> new SenderEngine(channel, InetAddress.getLoopbackAddress(), 9,
                                    1_024, 1, 200, 5, preassignedProtocol)),
                    () -> assertThrows(
                            IllegalArgumentException.class,
                            () -> new SenderEngine(channel, InetAddress.getLoopbackAddress(), 9,
                                    1_024, 1, 200, 5, wrongTimeout)));
        }
    }

    @Test
    void receiverPreservesConfigurationEstablishedByTrustedExecutionContext() throws Exception {
        TransferConfiguration configuration = TransferConfiguration.builder()
                .chunkSizeBytes(1_024L)
                .windowBytesRequested(4_096L)
                .windowPackets(4L)
                .timeoutMs(250L)
                .retryLimit(5L)
                .packetLossRate(0.0)
                .receiverInitialTimeoutMs(500L)
                .receiverInactivityTimeoutMs(400L)
                .build();
        TransferContext supplied = TransferContext.builder(TransferContext.Endpoint.RECEIVER)
                .configuration(configuration)
                .build();

        try (UdpChannel channel = new UdpChannel(0)) {
            ReceiverEngine receiver = new ReceiverEngine(channel, 500, 400, supplied);
            TransferContext actual = receiver.getTransferContext();

            assertAll(
                    () -> assertEquals(4_096L,
                            actual.getConfiguration().getWindowBytesRequested()),
                    () -> assertEquals(4L, actual.getConfiguration().getWindowPackets()),
                    () -> assertEquals(250L, actual.getConfiguration().getTimeoutMs()),
                    () -> assertEquals(0.0, actual.getConfiguration().getPacketLossRate()),
                    () -> assertFalse(
                            actual.getUnavailableReasons().containsKey("window_bytes_requested")),
                    () -> assertFalse(
                            actual.getUnavailableReasons().containsKey("packet_loss_rate")));
        }
    }

    @Test
    void failedInputLeavesFileAndProtocolEvidenceUnavailable(@TempDir Path tempDir) throws Exception {
        Path missing = tempDir.resolve("missing.bin");
        try (UdpChannel channel = new UdpChannel()) {
            SenderEngine sender = new SenderEngine(
                    channel, InetAddress.getLoopbackAddress(), 9, 1_024, 1, 200, 5);

            assertThrows(IOException.class, () -> sender.sendFile(missing.toString()));
            TransferContext context = sender.getTransferContext();
            TransferMetrics metrics = sender.getMetricsSnapshot();
            assertAll(
                    () -> assertNull(context.getProtocolTransferId()),
                    () -> assertNull(context.getFileSizeBytes()),
                    () -> assertNull(context.getOriginalFilename()),
                    () -> assertTrue(context.getUnavailableReasons().containsKey("file_size_bytes")),
                    () -> assertFalse(metrics.getTransferSuccess()),
                    () -> assertNull(metrics.getTransferTimeSec()),
                    () -> assertNull(metrics.getPacketsSent()),
                    () -> assertTrue(metrics.getFailureReason().startsWith("FileNotFoundException:")));
        }
    }

    @Test
    void receiverTimeoutLeavesWireIdentityUnavailableAndDoesNotClaimSenderIds(@TempDir Path tempDir)
            throws Exception {
        TransferContext supplied = TransferContext.builder(TransferContext.Endpoint.RECEIVER)
                .runId("receiver-run")
                .fileAttribution("storage/incoming/waiting.bin")
                .build();
        try (UdpChannel channel = new UdpChannel(0)) {
            ReceiverEngine receiver = new ReceiverEngine(channel, 35, 35, supplied);
            TransferResult result = receiver.receiveFile(tempDir.resolve("waiting.bin").toString());

            assertAll(
                    () -> assertFalse(result.isSuccess()),
                    () -> assertNull(receiver.getProtocolTransferId()),
                    () -> assertEquals("receiver-run", receiver.getTransferContext().getRunId()),
                    () -> assertNull(receiver.getTransferContext().getApplicationTransferId()),
                    () -> assertEquals(
                            "no valid START observed",
                            receiver.getTransferContext().getUnavailableReasons()
                                    .get("protocol_transfer_id")));
        }
    }

    @Test
    void senderAndReceiverReuseWireUuidWithoutFalselySharingApplicationIdentity(@TempDir Path tempDir)
            throws Exception {
        verifyTransferContext(tempDir, "payload.bin", new byte[] {1, 2, 3, 4, 5});
    }

    @Test
    void capturesObservedZeroForAnEmptyInputFile(@TempDir Path tempDir) throws Exception {
        verifyTransferContext(tempDir, "empty.bin", new byte[0]);
    }

    private void verifyTransferContext(Path tempDir, String filename, byte[] content) throws Exception {
        Path input = tempDir.resolve(filename);
        Path output = tempDir.resolve("received-" + filename);
        Files.write(input, content);

        TransferContext senderIdentity = TransferContext.builder(TransferContext.Endpoint.SENDER)
                .runId("sender-run-" + filename)
                .applicationTransferId("application-" + filename)
                .fileAttribution("source:" + filename)
                .build();
        TransferContext receiverIdentity = TransferContext.builder(TransferContext.Endpoint.RECEIVER)
                .runId("receiver-run-" + filename)
                .fileAttribution("destination:received-" + filename)
                .configuration(TransferConfiguration.builder()
                        .receiverInitialTimeoutMs(2_000L)
                        .receiverInactivityTimeoutMs(2_000L)
                        .receiverCompletionGraceMs(100L)
                        .build())
                .build();

        try (UdpChannel receiverChannel = new UdpChannel(0)) {
            ReceiverEngine receiver = new ReceiverEngine(
                    receiverChannel, 2_000, 2_000, 100, receiverIdentity);
            AtomicReference<TransferResult> receiverResult = new AtomicReference<>();
            AtomicReference<Throwable> receiverFailure = new AtomicReference<>();
            Thread receiverThread = new Thread(() -> {
                try {
                    receiverResult.set(receiver.receiveFile(output.toString()));
                } catch (Throwable throwable) {
                    receiverFailure.set(throwable);
                }
            });
            receiverThread.start();

            TransferResult senderResult;
            SenderEngine sender;
            try (UdpChannel senderChannel = new UdpChannel()) {
                sender = new SenderEngine(
                        senderChannel, InetAddress.getLoopbackAddress(), receiverChannel.getLocalPort(),
                        512, 2, 200, 5, senderIdentity);
                senderResult = sender.sendFile(input.toString());
            }

            receiverThread.join(5_000);
            assertFalse(receiverThread.isAlive());
            assertNull(receiverFailure.get());
            assertTrue(senderResult.isSuccess());
            assertTrue(receiverResult.get().isSuccess());

            TransferContext senderContext = sender.getTransferContext();
            TransferContext receiverContext = receiver.getTransferContext();
            TransferMetrics senderMetrics = sender.getMetricsSnapshot();
            long expectedChunks = Math.max(1L, (content.length + 511L) / 512L);
            assertAll(
                    () -> assertEquals(sender.getProtocolTransferId(), receiver.getProtocolTransferId()),
                    () -> assertEquals(sender.getProtocolTransferId(), senderContext.getProtocolTransferId()),
                    () -> assertEquals((long) content.length, senderContext.getFileSizeBytes()),
                    () -> assertEquals((long) content.length, receiverContext.getFileSizeBytes()),
                    () -> assertEquals(512L, senderContext.getConfiguration().getChunkSizeBytes()),
                    () -> assertEquals(512L, receiverContext.getConfiguration().getChunkSizeBytes()),
                    () -> assertEquals(2L, senderContext.getConfiguration().getWindowPackets()),
                    () -> assertNull(receiverContext.getConfiguration().getWindowPackets()),
                    () -> assertEquals("sender-run-" + filename, senderContext.getRunId()),
                    () -> assertEquals("receiver-run-" + filename, receiverContext.getRunId()),
                    () -> assertNotEquals(senderContext.getRunId(), receiverContext.getRunId()),
                    () -> assertEquals(
                            "application-" + filename, senderContext.getApplicationTransferId()),
                    () -> assertNull(receiverContext.getApplicationTransferId()),
                    () -> assertEquals(filename, receiverContext.getOriginalFilename()),
                    () -> assertEquals(TransferMetrics.EvidenceSource.REAL,
                            senderContext.getEvidenceSource()),
                    () -> assertEquals(expectedChunks, senderMetrics.getPacketsSent()),
                    () -> assertEquals(0L, senderMetrics.getRetransmissions()),
                    () -> assertEquals(expectedChunks, senderMetrics.getAcksReceived()),
                    () -> assertEquals(expectedChunks, senderMetrics.getPacketsAcked()),
                    () -> assertEquals(0L, senderMetrics.getPacketsTimedOut()),
                    () -> assertTrue(senderMetrics.getTransferSuccess()),
                    () -> assertNull(senderMetrics.getPayloadBytesDelivered()),
                    () -> assertNull(senderMetrics.getIntegrityVerified()));
        }
    }
}
