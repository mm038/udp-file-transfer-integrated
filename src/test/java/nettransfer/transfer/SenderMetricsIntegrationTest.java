package nettransfer.transfer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import nettransfer.metrics.TransferMetrics;
import nettransfer.net.UdpChannel;
import nettransfer.protocol.ControlMessage;
import nettransfer.protocol.MessageType;
import nettransfer.protocol.Packet;
import nettransfer.protocol.PacketDecoder;
import nettransfer.protocol.PacketEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SenderMetricsIntegrationTest {
    @Test
    void cumulativeAndDuplicateAcksUseExistingWindowProgress(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("four-bytes.bin");
        Files.write(input, new byte[] {1, 2, 3, 4});
        AtomicReference<Throwable> peerFailure = new AtomicReference<>();

        try (UdpChannel peer = new UdpChannel(0);
             UdpChannel senderChannel = new UdpChannel()) {
            peer.setReceiveTimeoutMillis(2_000);
            Thread peerThread = new Thread(() -> {
                try {
                    UdpChannel.ReceivedDatagram startDatagram = peer.receive();
                    ControlMessage start = decodeControl(startDatagram);
                    UUID transferId = UUID.fromString(start.getTransferId());
                    sendControl(peer, ControlMessage.createStartAck(start.getTransferId(), true, null),
                            startDatagram);

                    for (int expected = 0; expected < 3; expected++) {
                        assertEquals(expected, PacketDecoder.decode(peer.receive().data()).getSeqNum());
                    }
                    sendAck(peer, transferId, 99, startDatagram);
                    sendCorruptedAck(peer, transferId, 1, startDatagram);
                    sendAck(peer, UUID.randomUUID(), 1, startDatagram);
                    peer.send(PacketEncoder.encode(Packet.createData(
                                    transferId, 0, new byte[] {9})),
                            startDatagram.senderAddress(), startDatagram.senderPort());
                    peer.send(new byte[] {(byte) 0xFF},
                            startDatagram.senderAddress(), startDatagram.senderPort());
                    sendAck(peer, transferId, 1, startDatagram);
                    sendAck(peer, transferId, 1, startDatagram);
                    assertEquals(3, PacketDecoder.decode(peer.receive().data()).getSeqNum());
                    sendAck(peer, transferId, 3, startDatagram);

                    UdpChannel.ReceivedDatagram finishDatagram = peer.receive();
                    ControlMessage finish = decodeControl(finishDatagram);
                    assertEquals(MessageType.FINISH, finish.getType());
                    sendControl(peer,
                            ControlMessage.createFinishAck(start.getTransferId(), true, null),
                            finishDatagram);
                } catch (Throwable throwable) {
                    peerFailure.set(throwable);
                }
            });
            peerThread.start();

            SenderEngine sender = new SenderEngine(
                    senderChannel, InetAddress.getLoopbackAddress(), peer.getLocalPort(),
                    1, 3, 300, 3, 500, 1);
            TransferResult result = sender.sendFile(input.toString());
            peerThread.join(3_000);

            assertFalse(peerThread.isAlive());
            assertNull(peerFailure.get());
            assertTrue(result.isSuccess());
            TransferMetrics metrics = sender.getMetricsSnapshot();
            assertAll(
                    () -> assertEquals(4L, metrics.getPacketsSent()),
                    () -> assertEquals(0L, metrics.getRetransmissions()),
                    () -> assertEquals(5L, metrics.getAcksReceived()),
                    () -> assertEquals(4L, metrics.getPacketsAcked()),
                    () -> assertEquals(0L, metrics.getPacketsTimedOut()),
                    () -> assertTrue(metrics.getTransferSuccess()),
                    () -> assertEquals(1L, sender.getSenderObservations().finishAttempts()),
                    () -> assertTrue(sender.getSenderObservations().finishAcknowledged()));
        }
    }

    @Test
    void retryExhaustionRetainsAttemptsResendsAndTimeoutTriggers(@TempDir Path tempDir)
            throws Exception {
        Path input = tempDir.resolve("three-bytes.bin");
        Files.write(input, new byte[] {1, 2, 3});
        AtomicReference<Throwable> peerFailure = new AtomicReference<>();

        try (UdpChannel peer = new UdpChannel(0);
             UdpChannel senderChannel = new UdpChannel()) {
            peer.setReceiveTimeoutMillis(2_000);
            Thread peerThread = new Thread(() -> {
                try {
                    UdpChannel.ReceivedDatagram startDatagram = peer.receive();
                    ControlMessage start = decodeControl(startDatagram);
                    sendControl(peer, ControlMessage.createStartAck(start.getTransferId(), true, null),
                            startDatagram);
                    for (int attempt = 0; attempt < 6; attempt++) {
                        Packet packet = PacketDecoder.decode(peer.receive().data());
                        assertEquals(MessageType.DATA, packet.getType());
                    }
                    sendAck(peer, UUID.randomUUID(), 0, startDatagram);
                } catch (Throwable throwable) {
                    peerFailure.set(throwable);
                }
            });
            peerThread.start();

            SenderEngine sender = new SenderEngine(
                    senderChannel, InetAddress.getLoopbackAddress(), peer.getLocalPort(),
                    1, 3, 35, 1, 500, 1);
            TransferResult result = sender.sendFile(input.toString());
            peerThread.join(3_000);

            assertFalse(peerThread.isAlive());
            assertNull(peerFailure.get());
            assertFalse(result.isSuccess());
            TransferMetrics metrics = sender.getMetricsSnapshot();
            assertAll(
                    () -> assertEquals(6L, metrics.getPacketsSent()),
                    () -> assertEquals(3L, metrics.getRetransmissions()),
                    () -> assertEquals(0.5, metrics.getRetransmissionRatio()),
                    () -> assertEquals(0L, metrics.getAcksReceived()),
                    () -> assertEquals(0L, metrics.getPacketsAcked()),
                    () -> assertEquals(2L, metrics.getPacketsTimedOut()),
                    () -> assertFalse(metrics.getTransferSuccess()),
                    () -> assertEquals(
                            "Retry limit (1) exceeded -- transfer failed",
                            metrics.getFailureReason()),
                    () -> assertNotNull(metrics.getTransferTimeSec()),
                    () -> assertEquals(2L, sender.getSenderObservations().recoveryRounds()));
        }
    }

    private static ControlMessage decodeControl(UdpChannel.ReceivedDatagram datagram) {
        return ControlMessage.fromJson(new String(datagram.data(), StandardCharsets.UTF_8));
    }

    private static void sendControl(
            UdpChannel channel,
            ControlMessage message,
            UdpChannel.ReceivedDatagram destination) throws Exception {
        channel.send(message.toJson().getBytes(StandardCharsets.UTF_8),
                destination.senderAddress(), destination.senderPort());
    }

    private static void sendAck(
            UdpChannel channel,
            UUID transferId,
            int cumulativeSequence,
            UdpChannel.ReceivedDatagram destination) throws Exception {
        channel.send(PacketEncoder.encode(Packet.createAck(transferId, cumulativeSequence)),
                destination.senderAddress(), destination.senderPort());
    }

    private static void sendCorruptedAck(
            UdpChannel channel,
            UUID transferId,
            int cumulativeSequence,
            UdpChannel.ReceivedDatagram destination) throws Exception {
        byte[] encoded = PacketEncoder.encode(Packet.createAck(transferId, cumulativeSequence));
        encoded[23] ^= 0x01;
        channel.send(encoded, destination.senderAddress(), destination.senderPort());
    }
}
