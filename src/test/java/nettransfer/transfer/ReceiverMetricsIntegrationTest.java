package nettransfer.transfer;

import nettransfer.integrity.FileHashUtil;
import nettransfer.metrics.MetricsCollector;
import nettransfer.metrics.TransferMetrics;
import nettransfer.net.UdpChannel;
import nettransfer.protocol.ControlMessage;
import nettransfer.protocol.Packet;
import nettransfer.protocol.PacketEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ReceiverMetricsIntegrationTest {
    @Test
    void countsValidatedArrivalsSequenceOutcomesWritesAndCompletionRecovery(@TempDir Path tempDir)
            throws Exception {
        Path expected = tempDir.resolve("expected.bin");
        Path output = tempDir.resolve("output.bin");
        Files.write(expected, new byte[] {'a', 'b', 'c'});

        try (UdpChannel receiverChannel = new UdpChannel(0);
             UdpChannel sender = new UdpChannel();
             UdpChannel wrongPeer = new UdpChannel()) {
            ReceiverRun run = startReceiver(receiverChannel, output, 1_000, 120);
            sender.setReceiveTimeoutMillis(50);
            wrongPeer.setReceiveTimeoutMillis(40);
            InetAddress loopback = InetAddress.getLoopbackAddress();
            int port = receiverChannel.getLocalPort();
            ControlMessage start = start(sender, loopback, port, 3, 1);
            UUID id = UUID.fromString(start.getTransferId());

            sendData(sender, loopback, port, id, 0, new byte[] {'a'});
            sender.receive();
            sendData(sender, loopback, port, id, 0, new byte[] {'a'});
            sender.receive();
            sendData(sender, loopback, port, id, 2, new byte[] {'c'});
            assertThrows(SocketTimeoutException.class, sender::receive);

            sendData(sender, loopback, port, UUID.randomUUID(), 1, new byte[] {'b'});
            assertThrows(SocketTimeoutException.class, sender::receive);
            byte[] corrupt = PacketEncoder.encode(Packet.createData(id, 1, new byte[] {'b'}));
            corrupt[corrupt.length - 1] ^= 1;
            sender.send(corrupt, loopback, port);
            assertThrows(SocketTimeoutException.class, sender::receive);
            sendData(wrongPeer, loopback, port, id, 1, new byte[] {'b'});
            assertThrows(SocketTimeoutException.class, wrongPeer::receive);

            sendControl(sender, ControlMessage.createError(start.getTransferId(), "unrelated"), loopback, port);
            sendData(sender, loopback, port, id, 1, new byte[] {'b'});
            sender.receive();
            sendData(sender, loopback, port, id, 2, new byte[] {'c'});
            sender.receive();

            ControlMessage finish = ControlMessage.createFinish(
                    start.getTransferId(), FileHashUtil.sha256Hex(expected.toString()));
            sendControl(sender, finish, loopback, port);
            assertTrue(readControl(sender).isVerified());
            sendControl(sender, finish, loopback, port);
            assertTrue(readControl(sender).isVerified());

            run.thread().join(2_000);
            assertRunCompleted(run);
            assertTrue(run.result().get().isSuccess());
            assertArrayEquals(Files.readAllBytes(expected), Files.readAllBytes(output));

            TransferMetrics metrics = run.engine().getMetricsSnapshot();
            MetricsCollector.ReceiverObservations observations = run.engine().getReceiverObservations();
            long expectedEmissionBytes = ControlMessage.createStartAck(
                            start.getTransferId(), true, null).toJson().getBytes(StandardCharsets.UTF_8).length
                    + 4L * Packet.HEADER_SIZE
                    + 2L * ControlMessage.createFinishAck(
                            start.getTransferId(), true, null).toJson().getBytes(StandardCharsets.UTF_8).length;
            assertAll(
                    () -> assertEquals(5L, metrics.getPacketsReceived()),
                    () -> assertEquals(1L, metrics.getPacketsDuplicated()),
                    () -> assertEquals(3L, metrics.getPayloadBytesDelivered()),
                    () -> assertTrue(metrics.getIntegrityVerified()),
                    () -> assertEquals("RECEIVER", metrics.getIntegrityEvidenceSource()),
                    () -> assertNull(metrics.getTransferSuccess()),
                    () -> assertNull(metrics.getTransferTimeSec()),
                    () -> assertNull(metrics.getPacketsSent()),
                    () -> assertEquals(start.getTransferId(), metrics.getProtocolTransferId()),
                    () -> assertEquals(3L, observations.dataAccepted()),
                    () -> assertEquals(1L, observations.dataDuplicated()),
                    () -> assertEquals(1L, observations.dataOutOfOrderDiscarded()),
                    () -> assertEquals(1L, observations.dataValidationFailures()),
                    () -> assertEquals(4L, observations.dataAckAttempts()),
                    () -> assertEquals(1L, observations.finishArrivals()),
                    () -> assertEquals(1L, observations.duplicateFinishArrivals()),
                    () -> assertEquals(1L, observations.cachedFinishAckResendAttempts()),
                    () -> assertTrue(observations.completionGraceExpired()),
                    () -> assertTrue(observations.localSuccess()),
                    () -> assertEquals(expectedEmissionBytes, run.engine().getEndpointEmissionObservations()
                            .localUdpPayloadBytesEmitted()),
                    () -> assertTrue(run.engine().getEndpointEmissionObservations().accountingComplete()),
                    () -> assertNull(metrics.getUdpPayloadBytesEmitted()));
        }
    }

    @Test
    void inactivityFailurePreservesPartialDeliveryWithoutIntegrityEvidence(@TempDir Path tempDir)
            throws Exception {
        Path output = tempDir.resolve("partial.bin");
        try (UdpChannel receiverChannel = new UdpChannel(0); UdpChannel sender = new UdpChannel()) {
            ReceiverRun run = startReceiver(receiverChannel, output, 90, 80);
            sender.setReceiveTimeoutMillis(200);
            ControlMessage start = start(sender, InetAddress.getLoopbackAddress(),
                    receiverChannel.getLocalPort(), 2, 1);
            sendData(sender, InetAddress.getLoopbackAddress(), receiverChannel.getLocalPort(),
                    UUID.fromString(start.getTransferId()), 0, new byte[] {'a'});
            sender.receive();

            run.thread().join(1_000);
            assertRunCompleted(run);
            TransferMetrics metrics = run.engine().getMetricsSnapshot();
            assertAll(
                    () -> assertEquals(1L, metrics.getPacketsReceived()),
                    () -> assertEquals(1L, metrics.getPayloadBytesDelivered()),
                    () -> assertNull(metrics.getIntegrityVerified()),
                    () -> assertFalse(run.engine().getReceiverObservations().localSuccess()),
                    () -> assertEquals("RECEIVER_INACTIVITY_TIMEOUT",
                            run.engine().getReceiverObservations().terminalReason()));
        }
    }

    @Test
    void actualHashMismatchRecordsFalseWithoutSenderConfirmation(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("mismatch.bin");
        try (UdpChannel receiverChannel = new UdpChannel(0); UdpChannel sender = new UdpChannel()) {
            ReceiverRun run = startReceiver(receiverChannel, output, 500, 80);
            sender.setReceiveTimeoutMillis(200);
            InetAddress loopback = InetAddress.getLoopbackAddress();
            int port = receiverChannel.getLocalPort();
            ControlMessage start = start(sender, loopback, port, 1, 1);
            sendData(sender, loopback, port, UUID.fromString(start.getTransferId()), 0, new byte[] {'x'});
            sender.receive();
            sendControl(sender, ControlMessage.createFinish(start.getTransferId(), "0".repeat(64)), loopback, port);
            assertFalse(readControl(sender).isVerified());

            run.thread().join(1_000);
            assertRunCompleted(run);
            assertFalse(run.result().get().isSuccess());
            assertAll(
                    () -> assertFalse(run.engine().getMetricsSnapshot().getIntegrityVerified()),
                    () -> assertNull(run.engine().getMetricsSnapshot().getTransferSuccess()),
                    () -> assertEquals("SHA-256 mismatch",
                            run.engine().getReceiverObservations().terminalReason()));
        }
    }

    @Test
    void emptyTransferRecordsObservedZeroDeliveredBytes(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("empty.bin");
        Path expected = tempDir.resolve("expected-empty.bin");
        Files.write(expected, new byte[0]);
        try (UdpChannel receiverChannel = new UdpChannel(0); UdpChannel sender = new UdpChannel()) {
            ReceiverRun run = startReceiver(receiverChannel, output, 500, 60);
            sender.setReceiveTimeoutMillis(200);
            InetAddress loopback = InetAddress.getLoopbackAddress();
            int port = receiverChannel.getLocalPort();
            ControlMessage start = start(sender, loopback, port, 0, 1);
            sendData(sender, loopback, port, UUID.fromString(start.getTransferId()), 0, new byte[0]);
            sender.receive();
            sendControl(sender, ControlMessage.createFinish(
                    start.getTransferId(), FileHashUtil.sha256Hex(expected.toString())), loopback, port);
            assertTrue(readControl(sender).isVerified());

            run.thread().join(1_000);
            assertRunCompleted(run);
            assertEquals(0L, run.engine().getMetricsSnapshot().getPayloadBytesDelivered());
            assertTrue(run.engine().getMetricsSnapshot().getIntegrityVerified());
        }
    }

    @Test
    void failedWriteDoesNotIncrementDeliveredBytes(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("write-failure.bin");
        try (UdpChannel receiverChannel = new UdpChannel(0); UdpChannel sender = new UdpChannel()) {
            ReceiverEngine engine = new ReceiverEngine(receiverChannel, 500, 500, 60, null) {
                @Override
                protected RandomAccessFile openOutputFile(String path) throws IOException {
                    return new RandomAccessFile(path, "rw") {
                        @Override
                        public void write(byte[] bytes) throws IOException {
                            throw new IOException("injected write failure");
                        }
                    };
                }
            };
            ReceiverRun run = startReceiver(engine, output);
            sender.setReceiveTimeoutMillis(200);
            ControlMessage start = start(sender, InetAddress.getLoopbackAddress(),
                    receiverChannel.getLocalPort(), 1, 1);
            sendData(sender, InetAddress.getLoopbackAddress(), receiverChannel.getLocalPort(),
                    UUID.fromString(start.getTransferId()), 0, new byte[] {'x'});

            run.thread().join(1_000);
            assertFalse(run.thread().isAlive());
            assertNotNull(run.failure().get());
            assertEquals(0L, engine.getMetricsSnapshot().getPayloadBytesDelivered());
            assertFalse(engine.getReceiverObservations().localSuccess());
            assertTrue(engine.getReceiverObservations().terminalReason().contains("injected write failure"));
        }
    }

    @Test
    void snapshotBeforeStartKeepsTransferMeasurementsUnknown(@TempDir Path tempDir) throws Exception {
        try (UdpChannel channel = new UdpChannel(0)) {
            ReceiverEngine receiver = new ReceiverEngine(channel, 500, 500, 60, null);
            TransferMetrics snapshot = receiver.getMetricsSnapshot();
            assertAll(
                    () -> assertNull(snapshot.getPacketsReceived()),
                    () -> assertNull(snapshot.getPacketsDuplicated()),
                    () -> assertNull(snapshot.getPayloadBytesDelivered()),
                    () -> assertNull(snapshot.getIntegrityVerified()),
                    () -> assertNull(snapshot.getTransferSuccess()),
                    () -> assertNull(snapshot.getProtocolTransferId()));
        }
    }

    @Test
    void initialTimeoutPreservesUnknownCountersAndReceiverFailure(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("never-created.bin");
        try (UdpChannel channel = new UdpChannel(0)) {
            ReceiverEngine receiver = new ReceiverEngine(channel, 40, 40, 40, null);
            TransferResult result = receiver.receiveFile(output.toString());
            TransferMetrics snapshot = receiver.getMetricsSnapshot();
            assertAll(
                    () -> assertEquals(TransferResult.FailureReason.RECEIVER_INITIAL_TIMEOUT,
                            result.getFailureReason()),
                    () -> assertNull(snapshot.getPacketsReceived()),
                    () -> assertNull(snapshot.getPayloadBytesDelivered()),
                    () -> assertNull(snapshot.getIntegrityVerified()),
                    () -> assertFalse(receiver.getReceiverObservations().localSuccess()),
                    () -> assertEquals("RECEIVER_INITIAL_TIMEOUT",
                            receiver.getReceiverObservations().terminalReason()),
                    () -> assertFalse(Files.exists(output)));
        }
    }

    private static ReceiverRun startReceiver(UdpChannel channel, Path output,
                                             int inactivityMillis, int graceMillis) {
        return startReceiver(new ReceiverEngine(channel, 500, inactivityMillis, graceMillis, null), output);
    }

    private static ReceiverRun startReceiver(ReceiverEngine engine, Path output) {
        AtomicReference<TransferResult> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                result.set(engine.receiveFile(output.toString()));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        thread.start();
        return new ReceiverRun(engine, thread, result, failure);
    }

    private static ControlMessage start(UdpChannel sender, InetAddress address, int port,
                                        long fileSize, int chunkSize) throws Exception {
        ControlMessage start = ControlMessage.createStart("input.bin", fileSize, chunkSize);
        sendControl(sender, start, address, port);
        assertTrue(readControl(sender).isAccepted());
        return start;
    }

    private static void sendData(UdpChannel channel, InetAddress address, int port,
                                 UUID transferId, int sequence, byte[] payload) throws Exception {
        channel.send(PacketEncoder.encode(Packet.createData(transferId, sequence, payload)), address, port);
    }

    private static void sendControl(UdpChannel channel, ControlMessage message,
                                    InetAddress address, int port) throws Exception {
        channel.send(message.toJson().getBytes(StandardCharsets.UTF_8), address, port);
    }

    private static ControlMessage readControl(UdpChannel channel) throws Exception {
        return ControlMessage.fromJson(new String(channel.receive().data(), StandardCharsets.UTF_8));
    }

    private static void assertRunCompleted(ReceiverRun run) {
        assertFalse(run.thread().isAlive());
        assertNull(run.failure().get(), String.valueOf(run.failure().get()));
        assertNotNull(run.result().get());
    }

    private record ReceiverRun(ReceiverEngine engine, Thread thread,
                               AtomicReference<TransferResult> result,
                               AtomicReference<Throwable> failure) {}
}
