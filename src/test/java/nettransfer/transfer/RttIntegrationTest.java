package nettransfer.transfer;

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

class RttIntegrationTest {
    @Test
    void onlyValidSingleSequenceProgressProducesRttDespiteInvalidAndDuplicateAcks(
            @TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("one.bin");
        Files.write(input, new byte[] {1});
        AtomicReference<Throwable> peerFailure = new AtomicReference<>();

        try (UdpChannel peer = new UdpChannel(0);
             UdpChannel wrongPeer = new UdpChannel();
             UdpChannel senderChannel = new UdpChannel()) {
            peer.setReceiveTimeoutMillis(2_000);
            Thread peerThread = new Thread(() -> {
                try {
                    UdpChannel.ReceivedDatagram startDatagram = peer.receive();
                    ControlMessage start = control(startDatagram);
                    send(peer, ControlMessage.createStartAck(start.getTransferId(), true, null), startDatagram);
                    UdpChannel.ReceivedDatagram dataDatagram = peer.receive();
                    Packet data = PacketDecoder.decode(dataDatagram.data());

                    wrongPeer.send(PacketEncoder.encode(Packet.createAck(
                                    data.getTransferId(), data.getSeqNum())),
                            dataDatagram.senderAddress(), dataDatagram.senderPort());
                    sendAck(peer, UUID.randomUUID(), 0, dataDatagram);
                    byte[] corrupt = PacketEncoder.encode(Packet.createAck(data.getTransferId(), 0));
                    corrupt[23] ^= 1;
                    peer.send(corrupt, dataDatagram.senderAddress(), dataDatagram.senderPort());
                    sendAck(peer, data.getTransferId(), 99, dataDatagram);
                    peer.send(PacketEncoder.encode(Packet.createData(
                                    data.getTransferId(), 0, new byte[] {9})),
                            dataDatagram.senderAddress(), dataDatagram.senderPort());
                    sendAck(peer, data.getTransferId(), 0, dataDatagram);
                    sendAck(peer, data.getTransferId(), 0, dataDatagram); // no additional progress

                    UdpChannel.ReceivedDatagram finishDatagram = peer.receive();
                    send(peer, ControlMessage.createFinishAck(start.getTransferId(), true, null), finishDatagram);
                } catch (Throwable throwable) {
                    peerFailure.set(throwable);
                }
            });
            peerThread.start();

            SenderEngine sender = new SenderEngine(senderChannel, InetAddress.getLoopbackAddress(),
                    peer.getLocalPort(), 1024, 1, 500, 1, 500, 1, 500, 1, null);
            TransferResult result = sender.sendFile(input.toString());
            peerThread.join(3_000);

            assertFalse(peerThread.isAlive());
            assertNull(peerFailure.get(), String.valueOf(peerFailure.get()));
            assertTrue(result.isSuccess());
            assertAll(
                    () -> assertEquals(1L, sender.getMetricsSnapshot().getRttSampleCount()),
                    () -> assertNotNull(sender.getMetricsSnapshot().getRttMeanMs()),
                    () -> assertNotNull(sender.getMetricsSnapshot().getRttP95Ms()),
                    () -> assertTrue(sender.getMetricsSnapshot().getRttMeanMs() >= 0.0));
        }
    }

    @Test
    void cumulativeAckAdvancingMultipleSequencesIsExcluded(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("two.bin");
        Files.write(input, new byte[] {1, 2});
        AtomicReference<Throwable> peerFailure = new AtomicReference<>();

        try (UdpChannel peer = new UdpChannel(0); UdpChannel senderChannel = new UdpChannel()) {
            peer.setReceiveTimeoutMillis(2_000);
            Thread peerThread = new Thread(() -> {
                try {
                    UdpChannel.ReceivedDatagram startDatagram = peer.receive();
                    ControlMessage start = control(startDatagram);
                    send(peer, ControlMessage.createStartAck(start.getTransferId(), true, null), startDatagram);
                    Packet first = PacketDecoder.decode(peer.receive().data());
                    Packet second = PacketDecoder.decode(peer.receive().data());
                    assertEquals(0, first.getSeqNum());
                    assertEquals(1, second.getSeqNum());
                    sendAck(peer, second.getTransferId(), 1, startDatagram);
                    UdpChannel.ReceivedDatagram finishDatagram = peer.receive();
                    send(peer, ControlMessage.createFinishAck(start.getTransferId(), true, null), finishDatagram);
                } catch (Throwable throwable) {
                    peerFailure.set(throwable);
                }
            });
            peerThread.start();

            SenderEngine sender = new SenderEngine(senderChannel, InetAddress.getLoopbackAddress(),
                    peer.getLocalPort(), 1, 2, 500, 1, 500, 1, 500, 1, null);
            TransferResult result = sender.sendFile(input.toString());
            peerThread.join(3_000);

            assertFalse(peerThread.isAlive());
            assertNull(peerFailure.get(), String.valueOf(peerFailure.get()));
            assertTrue(result.isSuccess());
            assertAll(
                    () -> assertEquals(0L, sender.getMetricsSnapshot().getRttSampleCount()),
                    () -> assertNull(sender.getMetricsSnapshot().getRttMeanMs()),
                    () -> assertNull(sender.getMetricsSnapshot().getRttP95Ms()));
        }
    }

    private static ControlMessage control(UdpChannel.ReceivedDatagram datagram) {
        return ControlMessage.fromJson(new String(datagram.data(), StandardCharsets.UTF_8));
    }

    private static void send(UdpChannel channel, ControlMessage message,
                             UdpChannel.ReceivedDatagram destination) throws Exception {
        channel.send(message.toJson().getBytes(StandardCharsets.UTF_8),
                destination.senderAddress(), destination.senderPort());
    }

    private static void sendAck(UdpChannel channel, UUID transferId, int sequence,
                                UdpChannel.ReceivedDatagram destination) throws Exception {
        channel.send(PacketEncoder.encode(Packet.createAck(transferId, sequence)),
                destination.senderAddress(), destination.senderPort());
    }
}
