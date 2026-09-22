package nettransfer.transfer;

import nettransfer.metrics.TransferMetrics;
import nettransfer.net.UdpChannel;
import nettransfer.protocol.ControlMessage;
import nettransfer.protocol.Packet;
import nettransfer.protocol.PacketDecoder;
import nettransfer.protocol.PacketEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FinishHandshakeTest {
    @Test
    void retriesLostFinishAckAndKeepsDataMetricsSeparate(@TempDir Path tempDir) throws Exception {
        Scenario result = runScenario(tempDir, 40, 2, (peer, finish, number) -> {
            if (number == 2) {
                send(peer, ControlMessage.createFinishAck(finish.message().getTransferId(), true, null),
                        finish.datagram());
            }
        });

        assertTrue(result.result().isSuccess());
        assertEquals(2, result.finishCount());
        TransferMetrics metrics = result.sender().getMetricsSnapshot();
        assertAll(
                () -> assertEquals(1L, metrics.getPacketsSent()),
                () -> assertEquals(0L, metrics.getRetransmissions()),
                () -> assertEquals(1L, metrics.getAcksReceived()),
                () -> assertEquals(1L, metrics.getPacketsAcked()),
                () -> assertEquals(2L, result.sender().getSenderObservations().finishAttempts()),
                () -> assertTrue(result.sender().getSenderObservations().finishAcknowledged()),
                () -> assertTrue(metrics.getTransferTimeSec() >= 0.03));
    }

    @Test
    void exhaustsExactlyConfiguredRetryBudget(@TempDir Path tempDir) throws Exception {
        Scenario result = runScenario(tempDir, 25, 2, (peer, finish, number) -> { });

        assertFalse(result.result().isSuccess());
        assertEquals(TransferResult.FailureReason.FINISH_HANDSHAKE_TIMEOUT,
                result.result().getFailureReason());
        assertEquals(3, result.finishCount());
        assertEquals(3L, result.sender().getSenderObservations().finishAttempts());
        assertEquals("FINISH_HANDSHAKE_TIMEOUT",
                result.sender().getMetricsSnapshot().getFailureReason());
    }

    @Test
    void ignoresMalformedWrongUuidAndUnrelatedMessagesWithinFixedDeadline(@TempDir Path tempDir)
            throws Exception {
        Scenario result = runScenario(tempDir, 80, 0, (peer, finish, number) -> {
            peer.send("not-json".getBytes(StandardCharsets.UTF_8),
                    finish.datagram().senderAddress(), finish.datagram().senderPort());
            send(peer, ControlMessage.createFinishAck(UUID.randomUUID().toString(), true, null),
                    finish.datagram());
            send(peer, ControlMessage.createError(finish.message().getTransferId(), "unrelated"),
                    finish.datagram());
            send(peer, ControlMessage.createFinishAck(finish.message().getTransferId(), true, null),
                    finish.datagram());
        });

        assertTrue(result.result().isSuccess());
        assertEquals(1, result.finishCount());
    }

    @Test
    void explicitVerificationFailureIsTerminalWithoutRetry(@TempDir Path tempDir) throws Exception {
        Scenario result = runScenario(tempDir, 50, 3, (peer, finish, number) ->
                send(peer, ControlMessage.createFinishAck(
                        finish.message().getTransferId(), false, "SHA-256 mismatch"),
                        finish.datagram()));

        assertFalse(result.result().isSuccess());
        assertNull(result.result().getFailureReason());
        assertEquals("FINISH_ACK verification failed: SHA-256 mismatch", result.result().getMessage());
        assertEquals(1, result.finishCount());
    }

    @Test
    void invalidTrafficDoesNotExtendFinishAttemptDeadline(@TempDir Path tempDir) throws Exception {
        Scenario result = runScenario(tempDir, 40, 0, (peer, finish, number) -> {
            for (int i = 0; i < 200; i++) {
                peer.send("not-json".getBytes(StandardCharsets.UTF_8),
                        finish.datagram().senderAddress(), finish.datagram().senderPort());
            }
        });

        assertEquals(TransferResult.FailureReason.FINISH_HANDSHAKE_TIMEOUT,
                result.result().getFailureReason());
        assertTrue(result.elapsedMillis() < 500,
                "invalid datagrams must not reset the 40 ms attempt deadline");
    }

    private Scenario runScenario(Path tempDir, int finishTimeout, int finishRetries,
                                 FinishResponder responder) throws Exception {
        Path input = tempDir.resolve(UUID.randomUUID() + ".bin");
        Files.write(input, new byte[] {42});
        AtomicReference<Throwable> peerFailure = new AtomicReference<>();
        AtomicReference<Integer> finishCount = new AtomicReference<>(0);

        try (UdpChannel peer = new UdpChannel(0); UdpChannel senderChannel = new UdpChannel()) {
            peer.setReceiveTimeoutMillis(500);
            Thread peerThread = new Thread(() -> {
                try {
                    UdpChannel.ReceivedDatagram startDatagram = peer.receive();
                    ControlMessage start = control(startDatagram);
                    send(peer, ControlMessage.createStartAck(start.getTransferId(), true, null), startDatagram);
                    Packet data = PacketDecoder.decode(peer.receive().data());
                    peer.send(PacketEncoder.encode(Packet.createAck(data.getTransferId(), data.getSeqNum())),
                            startDatagram.senderAddress(), startDatagram.senderPort());
                    for (int i = 1; i <= finishRetries + 1; i++) {
                        UdpChannel.ReceivedDatagram datagram = peer.receive();
                        ControlMessage finish = control(datagram);
                        finishCount.set(i);
                        responder.respond(peer, new FinishArrival(finish, datagram), i);
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                    // A terminal response can make the sender stop before the configured maximum.
                } catch (Throwable throwable) {
                    peerFailure.set(throwable);
                }
            });
            peerThread.start();

            SenderEngine sender = new SenderEngine(senderChannel, InetAddress.getLoopbackAddress(),
                    peer.getLocalPort(), 1024, 1, 100, 2, 300, 1,
                    finishTimeout, finishRetries, null);
            long startedAt = System.nanoTime();
            TransferResult transferResult = sender.sendFile(input.toString());
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            peerThread.join(2_500);
            assertFalse(peerThread.isAlive());
            assertNull(peerFailure.get(), String.valueOf(peerFailure.get()));
            return new Scenario(transferResult, sender, finishCount.get(), elapsedMillis);
        }
    }

    private static ControlMessage control(UdpChannel.ReceivedDatagram datagram) {
        return ControlMessage.fromJson(new String(datagram.data(), StandardCharsets.UTF_8));
    }

    private static void send(UdpChannel peer, ControlMessage message,
                             UdpChannel.ReceivedDatagram destination) throws Exception {
        peer.send(message.toJson().getBytes(StandardCharsets.UTF_8),
                destination.senderAddress(), destination.senderPort());
    }

    private record Scenario(TransferResult result, SenderEngine sender, int finishCount,
                            long elapsedMillis) {}
    private record FinishArrival(ControlMessage message, UdpChannel.ReceivedDatagram datagram) {}
    @FunctionalInterface
    private interface FinishResponder {
        void respond(UdpChannel peer, FinishArrival finish, int number) throws Exception;
    }
}
