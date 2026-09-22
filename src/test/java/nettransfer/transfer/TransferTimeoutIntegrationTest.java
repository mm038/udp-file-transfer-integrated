package nettransfer.transfer;

import nettransfer.net.UdpChannel;
import nettransfer.metrics.TransferMetrics;
import nettransfer.protocol.ControlMessage;
import nettransfer.protocol.MessageType;
import nettransfer.protocol.Packet;
import nettransfer.protocol.PacketEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TransferTimeoutIntegrationTest {

    @TempDir Path tempDir;

    @Test
    void senderFailsWhenStartAckNeverArrives() throws Exception {
        Path input = tempDir.resolve("input.txt");
        Files.writeString(input, "hello");
        try (UdpChannel unusedPort = new UdpChannel(0);
             UdpChannel senderChannel = new UdpChannel()) {
            SenderEngine sender = new SenderEngine(senderChannel,
                    InetAddress.getByName("127.0.0.1"), unusedPort.getLocalPort(),
                    1024, 1, 200, 5, 30, 1);
            TransferResult result = sender.sendFile(input.toString());
            assertFalse(result.isSuccess());
            assertEquals(TransferResult.FailureReason.START_HANDSHAKE_TIMEOUT,
                    result.getFailureReason());
            TransferMetrics metrics = sender.getMetricsSnapshot();
            assertAll(
                    () -> assertFalse(metrics.getTransferSuccess()),
                    () -> assertEquals("START_HANDSHAKE_TIMEOUT", metrics.getFailureReason()),
                    () -> assertEquals(0L, metrics.getPacketsSent()),
                    () -> assertEquals(0L, metrics.getRetransmissions()),
                    () -> assertEquals(0L, metrics.getPacketsTimedOut()),
                    () -> assertNotNull(metrics.getTransferTimeSec()),
                    () -> assertEquals(2L, sender.getSenderObservations().startAttempts()));
        }
    }

    @Test
    void startRetriesUseSameTransferIdAndExactAttemptCount() throws Exception {
        Path input = tempDir.resolve("input.txt");
        Files.writeString(input, "hello");
        List<String> transferIds = new ArrayList<>();
        AtomicReference<Exception> listenerError = new AtomicReference<>();
        try (UdpChannel listener = new UdpChannel(0);
             UdpChannel senderChannel = new UdpChannel()) {
            listener.setReceiveTimeoutMillis(500);
            Thread listenerThread = new Thread(() -> {
                try {
                    for (int i = 0; i < 3; i++) {
                        UdpChannel.ReceivedDatagram datagram = listener.receive();
                        ControlMessage start = ControlMessage.fromJson(
                                new String(datagram.data(), StandardCharsets.UTF_8));
                        assertEquals(MessageType.START, start.getType());
                        transferIds.add(start.getTransferId());
                        ControlMessage unrelated = ControlMessage.createStartAck(
                                UUID.randomUUID().toString(), true, null);
                        listener.send(unrelated.toJson().getBytes(StandardCharsets.UTF_8),
                                datagram.senderAddress(), datagram.senderPort());
                    }
                } catch (Exception e) {
                    listenerError.set(e);
                }
            });
            listenerThread.start();
            SenderEngine sender = new SenderEngine(senderChannel,
                    InetAddress.getByName("127.0.0.1"), listener.getLocalPort(),
                    1024, 1, 200, 5, 40, 2);
            TransferResult result = sender.sendFile(input.toString());
            listenerThread.join(2_000);
            assertFalse(listenerThread.isAlive());
            assertNull(listenerError.get());
            assertEquals(3, transferIds.size()); // initial attempt plus two retries
            assertEquals(1, transferIds.stream().distinct().count());
            assertEquals(TransferResult.FailureReason.START_HANDSHAKE_TIMEOUT,
                    result.getFailureReason());
            assertEquals(3L, sender.getSenderObservations().startAttempts());
        }
    }

    @Test
    void receiverTimesOutBeforeStartWithoutCreatingFile() throws Exception {
        Path output = tempDir.resolve("output.txt");
        try (UdpChannel receiverChannel = new UdpChannel(0)) {
            TransferResult result = new ReceiverEngine(receiverChannel, 40, 40)
                    .receiveFile(output.toString());
            assertEquals(TransferResult.FailureReason.RECEIVER_INITIAL_TIMEOUT,
                    result.getFailureReason());
            assertFalse(Files.exists(output));
        }
    }

    @Test
    void invalidTrafficCannotExtendInitialWaitingDeadline() throws Exception {
        Path output = tempDir.resolve("output.txt");
        try (UdpChannel receiverChannel = new UdpChannel(0);
             UdpChannel senderChannel = new UdpChannel()) {
            AtomicReference<TransferResult> result = new AtomicReference<>();
            Thread receiverThread = new Thread(() -> {
                try {
                    result.set(new ReceiverEngine(receiverChannel, 80, 80)
                            .receiveFile(output.toString()));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            receiverThread.start();
            ScheduledExecutorService flood = Executors.newSingleThreadScheduledExecutor();
            try {
                flood.scheduleAtFixedRate(() -> {
                    try {
                        senderChannel.send(new byte[] { 99 },
                                InetAddress.getByName("127.0.0.1"), receiverChannel.getLocalPort());
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                }, 0, 5, TimeUnit.MILLISECONDS);
                receiverThread.join(1_000);
                assertFalse(receiverThread.isAlive());
                assertNotNull(result.get());
                assertEquals(TransferResult.FailureReason.RECEIVER_INITIAL_TIMEOUT,
                        result.get().getFailureReason());
                assertFalse(Files.exists(output));
            } finally {
                flood.shutdownNow();
            }
        }
    }

    @Test
    void receiverTimesOutAfterAcceptedDataWhenFinishNeverArrives() throws Exception {
        Path output = tempDir.resolve("partial.txt");
        try (UdpChannel receiverChannel = new UdpChannel(0);
             UdpChannel senderChannel = new UdpChannel()) {
            AtomicReference<TransferResult> result = new AtomicReference<>();
            Thread receiverThread = new Thread(() -> {
                try {
                    result.set(new ReceiverEngine(receiverChannel, 500, 60)
                            .receiveFile(output.toString()));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            receiverThread.start();
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            ControlMessage start = ControlMessage.createStart("input.txt", 2, 1);
            senderChannel.send(start.toJson().getBytes(StandardCharsets.UTF_8),
                    loopback, receiverChannel.getLocalPort());
            senderChannel.setReceiveTimeoutMillis(500);
            assertEquals(MessageType.START_ACK, readControl(senderChannel).getType());
            senderChannel.send(PacketEncoder.encode(Packet.createData(
                    UUID.fromString(start.getTransferId()), 0, new byte[] { 'a' })),
                    loopback, receiverChannel.getLocalPort());
            senderChannel.receive(); // DATA ACK
            receiverThread.join(2_000);
            assertFalse(receiverThread.isAlive());
            assertNotNull(result.get());
            assertEquals(TransferResult.FailureReason.RECEIVER_INACTIVITY_TIMEOUT,
                    result.get().getFailureReason());
        }
    }

    @Test
    void unrelatedAndDuplicateTrafficCannotExtendInactivity() throws Exception {
        Path output = tempDir.resolve("partial.txt");
        try (UdpChannel receiverChannel = new UdpChannel(0);
             UdpChannel senderChannel = new UdpChannel();
             UdpChannel unrelatedChannel = new UdpChannel()) {
            AtomicReference<TransferResult> result = new AtomicReference<>();
            Thread receiverThread = new Thread(() -> {
                try {
                    result.set(new ReceiverEngine(receiverChannel, 500, 80)
                            .receiveFile(output.toString()));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            receiverThread.start();
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            ControlMessage start = ControlMessage.createStart("input.txt", 2, 1);
            byte[] startBytes = start.toJson().getBytes(StandardCharsets.UTF_8);
            senderChannel.send(startBytes, loopback, receiverChannel.getLocalPort());
            senderChannel.setReceiveTimeoutMillis(500);
            assertEquals(MessageType.START_ACK, readControl(senderChannel).getType());
            byte[] firstChunk = PacketEncoder.encode(Packet.createData(
                    UUID.fromString(start.getTransferId()), 0, new byte[] {'a'}));
            senderChannel.send(firstChunk, loopback, receiverChannel.getLocalPort());
            senderChannel.receive(); // ACK for accepted sequence 0

            ScheduledExecutorService flood = Executors.newSingleThreadScheduledExecutor();
            try {
                flood.scheduleAtFixedRate(() -> {
                    try {
                        senderChannel.send(startBytes, loopback, receiverChannel.getLocalPort());
                        senderChannel.send(firstChunk, loopback, receiverChannel.getLocalPort());
                        unrelatedChannel.send(new byte[] { 99 }, loopback, receiverChannel.getLocalPort());
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                }, 0, 5, TimeUnit.MILLISECONDS);
                receiverThread.join(1_000);
                assertFalse(receiverThread.isAlive(), "duplicate DATA, START and unrelated traffic must not extend deadline");
                assertNotNull(result.get());
                assertEquals(TransferResult.FailureReason.RECEIVER_INACTIVITY_TIMEOUT,
                        result.get().getFailureReason());
            } finally {
                flood.shutdownNow();
            }
        }
    }

    @Test
    void duplicateStartIsReAcknowledgedWithoutResettingProgress() throws Exception {
        Path output = tempDir.resolve("output.txt");
        try (UdpChannel receiverChannel = new UdpChannel(0);
             UdpChannel senderChannel = new UdpChannel()) {
            AtomicReference<TransferResult> result = new AtomicReference<>();
            Thread receiverThread = new Thread(() -> {
                try {
                    result.set(new ReceiverEngine(receiverChannel, 500, 500, 100, null)
                            .receiveFile(output.toString()));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            receiverThread.start();
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            int port = receiverChannel.getLocalPort();
            ControlMessage start = ControlMessage.createStart("input.txt", 2, 1);
            byte[] startBytes = start.toJson().getBytes(StandardCharsets.UTF_8);
            UUID id = UUID.fromString(start.getTransferId());
            senderChannel.setReceiveTimeoutMillis(500);
            senderChannel.send(startBytes, loopback, port);
            assertTrue(readControl(senderChannel).isAccepted());
            senderChannel.send(PacketEncoder.encode(Packet.createData(id, 0, new byte[] {'a'})),
                    loopback, port);
            senderChannel.receive(); // ACK for sequence 0
            senderChannel.send(startBytes, loopback, port);
            assertTrue(readControl(senderChannel).isAccepted());
            senderChannel.send(PacketEncoder.encode(Packet.createData(id, 1, new byte[] {'b'})),
                    loopback, port);
            senderChannel.receive(); // ACK for sequence 1
            ControlMessage finish = ControlMessage.createFinish(start.getTransferId(),
                    nettransfer.integrity.FileHashUtil.sha256Hex(output.toString()));
            senderChannel.send(finish.toJson().getBytes(StandardCharsets.UTF_8), loopback, port);
            assertTrue(readControl(senderChannel).isVerified());
            receiverThread.join(2_000);
            assertFalse(receiverThread.isAlive());
            assertNotNull(result.get());
            assertTrue(result.get().isSuccess());
            assertArrayEquals(new byte[] {'a', 'b'}, Files.readAllBytes(output));
        }
    }

    private static ControlMessage readControl(UdpChannel channel) throws Exception {
        return ControlMessage.fromJson(new String(channel.receive().data(), StandardCharsets.UTF_8));
    }
}
