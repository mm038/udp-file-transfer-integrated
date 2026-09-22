package nettransfer.metrics;

import nettransfer.net.UdpChannel;
import nettransfer.protocol.ControlMessage;
import nettransfer.protocol.Packet;
import nettransfer.protocol.PacketDecoder;
import nettransfer.protocol.PacketEncoder;
import nettransfer.transfer.SenderEngine;
import nettransfer.transfer.TransferResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class UdpEmissionMetricsTest {
    @Test
    void channelCountsActualSuccessfulPayloadsOnceAndExcludesFailedSend() throws Exception {
        AtomicLong emitted = new AtomicLong();
        try (UdpChannel receiver = new UdpChannel(0); UdpChannel sender = new UdpChannel()) {
            sender.setSuccessfulSendObserver(emitted::addAndGet);
            byte[] zeroLengthData = PacketEncoder.encode(Packet.createData(
                    java.util.UUID.randomUUID(), 0, new byte[0]));
            sender.send(zeroLengthData, InetAddress.getLoopbackAddress(), receiver.getLocalPort());
            assertArrayEquals(zeroLengthData, receiver.receive().data());
            assertEquals(Packet.HEADER_SIZE, emitted.get());

            sender.close();
            assertThrows(java.io.IOException.class, () -> sender.send(
                    new byte[] {1, 2, 3}, InetAddress.getLoopbackAddress(), receiver.getLocalPort()));
            assertEquals(Packet.HEADER_SIZE, emitted.get());
        }
    }

    @Test
    void endpointObserversRemainSeparateAndCombinedMetricStaysUnavailable() throws Exception {
        MetricsCollector senderMetrics = receiverOrSenderCollector(TransferContext.Endpoint.SENDER, "sender");
        MetricsCollector receiverMetrics = receiverOrSenderCollector(TransferContext.Endpoint.RECEIVER, "receiver");
        senderMetrics.beginEndpointEmissionAccounting();
        receiverMetrics.beginEndpointEmissionAccounting();

        try (UdpChannel sender = new UdpChannel(); UdpChannel receiver = new UdpChannel(0)) {
            sender.setSuccessfulSendObserver(senderMetrics::observeUdpPayloadEmitted);
            receiver.setSuccessfulSendObserver(receiverMetrics::observeUdpPayloadEmitted);
            sender.send(new byte[] {1, 2, 3, 4},
                    InetAddress.getLoopbackAddress(), receiver.getLocalPort());
            UdpChannel.ReceivedDatagram arrival = receiver.receive();
            assertEquals(0L, receiverMetrics.endpointEmissionObservations().localUdpPayloadBytesEmitted());
            receiver.send(new byte[] {5, 6}, arrival.senderAddress(), arrival.senderPort());
            sender.receive();

            assertAll(
                    () -> assertEquals(4L, senderMetrics.endpointEmissionObservations()
                            .localUdpPayloadBytesEmitted()),
                    () -> assertEquals(2L, receiverMetrics.endpointEmissionObservations()
                            .localUdpPayloadBytesEmitted()),
                    () -> assertNull(senderMetrics.snapshot().getUdpPayloadBytesEmitted()),
                    () -> assertNull(receiverMetrics.snapshot().getUdpPayloadBytesEmitted()));
        }
    }

    @Test
    void senderTotalMatchesStartDataAndFinishRetriesAtSocketBoundary(@TempDir Path tempDir)
            throws Exception {
        Path input = tempDir.resolve("one-byte.bin");
        Files.write(input, new byte[] {7});
        AtomicLong observedBytes = new AtomicLong();
        AtomicReference<Throwable> peerFailure = new AtomicReference<>();

        try (UdpChannel peer = new UdpChannel(0); UdpChannel senderChannel = new UdpChannel()) {
            peer.setReceiveTimeoutMillis(2_000);
            Thread peerThread = new Thread(() -> {
                try {
                    UdpChannel.ReceivedDatagram firstStart = receiveAndCount(peer, observedBytes);
                    ControlMessage start = control(firstStart);
                    UdpChannel.ReceivedDatagram secondStart = receiveAndCount(peer, observedBytes);
                    assertEquals(start.getTransferId(), control(secondStart).getTransferId());
                    send(peer, ControlMessage.createStartAck(start.getTransferId(), true, null), secondStart);

                    UdpChannel.ReceivedDatagram firstData = receiveAndCount(peer, observedBytes);
                    Packet data = PacketDecoder.decode(firstData.data());
                    UdpChannel.ReceivedDatagram secondData = receiveAndCount(peer, observedBytes);
                    assertEquals(data.getSeqNum(), PacketDecoder.decode(secondData.data()).getSeqNum());
                    peer.send(PacketEncoder.encode(Packet.createAck(data.getTransferId(), data.getSeqNum())),
                            secondData.senderAddress(), secondData.senderPort());

                    UdpChannel.ReceivedDatagram firstFinish = receiveAndCount(peer, observedBytes);
                    ControlMessage finish = control(firstFinish);
                    UdpChannel.ReceivedDatagram secondFinish = receiveAndCount(peer, observedBytes);
                    assertEquals(finish.toJson(), control(secondFinish).toJson());
                    send(peer, ControlMessage.createFinishAck(start.getTransferId(), true, null), secondFinish);
                } catch (Throwable throwable) {
                    peerFailure.set(throwable);
                }
            });
            peerThread.start();

            SenderEngine sender = new SenderEngine(senderChannel, InetAddress.getLoopbackAddress(),
                    peer.getLocalPort(), 1024, 1, 30, 1, 30, 1, 30, 1, null);
            TransferResult result = sender.sendFile(input.toString());
            peerThread.join(3_000);

            assertFalse(peerThread.isAlive());
            assertNull(peerFailure.get(), String.valueOf(peerFailure.get()));
            assertTrue(result.isSuccess());
            assertAll(
                    () -> assertEquals(observedBytes.get(), sender.getEndpointEmissionObservations()
                            .localUdpPayloadBytesEmitted()),
                    () -> assertTrue(sender.getEndpointEmissionObservations().accountingComplete()),
                    () -> assertEquals(2L, sender.getMetricsSnapshot().getPacketsSent()),
                    () -> assertEquals(1L, sender.getMetricsSnapshot().getRetransmissions()),
                    () -> assertEquals(0L, sender.getMetricsSnapshot().getRttSampleCount()),
                    () -> assertNull(sender.getMetricsSnapshot().getUdpPayloadBytesEmitted()));
        }
    }

    @Test
    void failedTransferPreservesPartialEndpointEmissionEvidence(@TempDir Path tempDir)
            throws Exception {
        Path input = tempDir.resolve("start-timeout.bin");
        Files.write(input, new byte[] {1});
        try (UdpChannel silentPeer = new UdpChannel(0); UdpChannel senderChannel = new UdpChannel()) {
            SenderEngine sender = new SenderEngine(senderChannel, InetAddress.getLoopbackAddress(),
                    silentPeer.getLocalPort(), 1024, 1, 50, 1, 20, 1, 20, 1, null);
            TransferResult result = sender.sendFile(input.toString());

            assertFalse(result.isSuccess());
            assertEquals(nettransfer.transfer.TransferResult.FailureReason.START_HANDSHAKE_TIMEOUT,
                    result.getFailureReason());
            assertAll(
                    () -> assertNotNull(sender.getEndpointEmissionObservations()
                            .localUdpPayloadBytesEmitted()),
                    () -> assertTrue(sender.getEndpointEmissionObservations()
                            .localUdpPayloadBytesEmitted() > 0),
                    () -> assertTrue(sender.getEndpointEmissionObservations().accountingComplete()),
                    () -> assertNull(sender.getMetricsSnapshot().getUdpPayloadBytesEmitted()),
                    () -> assertNull(sender.getMetricsSnapshot().getRttSampleCount()));
        }
    }

    private static MetricsCollector receiverOrSenderCollector(
            TransferContext.Endpoint endpoint, String runId) {
        return new MetricsCollector(TransferContext.builder(endpoint).runId(runId).build());
    }

    private static UdpChannel.ReceivedDatagram receiveAndCount(
            UdpChannel channel, AtomicLong bytes) throws Exception {
        UdpChannel.ReceivedDatagram datagram = channel.receive();
        bytes.addAndGet(datagram.data().length);
        return datagram;
    }

    private static ControlMessage control(UdpChannel.ReceivedDatagram datagram) {
        return ControlMessage.fromJson(new String(datagram.data(), StandardCharsets.UTF_8));
    }

    private static void send(UdpChannel channel, ControlMessage message,
                             UdpChannel.ReceivedDatagram destination) throws Exception {
        channel.send(message.toJson().getBytes(StandardCharsets.UTF_8),
                destination.senderAddress(), destination.senderPort());
    }
}
