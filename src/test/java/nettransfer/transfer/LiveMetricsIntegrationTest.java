package nettransfer.transfer;

import nettransfer.integrity.FileHashUtil;
import nettransfer.metrics.LiveMetricsProvider;
import nettransfer.metrics.LiveMetricsSnapshot;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LiveMetricsIntegrationTest {
    @Test
    void senderExposesAtomicProgressAcrossHandshakeDataFinishAndTerminalState(@TempDir Path tempDir)
            throws Exception {
        Path input = tempDir.resolve("three.bin");
        Files.write(input, new byte[] {1, 2, 3});
        CountDownLatch startSeen = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        CountDownLatch firstDataSeen = new CountDownLatch(1);
        CountDownLatch releaseFirstAck = new CountDownLatch(1);
        CountDownLatch finishSeen = new CountDownLatch(1);
        CountDownLatch releaseFinish = new CountDownLatch(1);
        AtomicInteger firstStartLength = new AtomicInteger();
        AtomicReference<Throwable> peerFailure = new AtomicReference<>();

        try (UdpChannel peer = new UdpChannel(0); UdpChannel senderChannel = new UdpChannel()) {
            peer.setReceiveTimeoutMillis(3_000);
            Thread peerThread = new Thread(() -> {
                try {
                    UdpChannel.ReceivedDatagram startDatagram = peer.receive();
                    firstStartLength.set(startDatagram.data().length);
                    ControlMessage start = control(startDatagram);
                    startSeen.countDown();
                    assertTrue(releaseStart.await(2, TimeUnit.SECONDS));
                    sendControl(peer, ControlMessage.createStartAck(start.getTransferId(), true, null),
                            startDatagram);

                    UdpChannel.ReceivedDatagram firstDataDatagram = peer.receive();
                    Packet first = PacketDecoder.decode(firstDataDatagram.data());
                    firstDataSeen.countDown();
                    assertTrue(releaseFirstAck.await(2, TimeUnit.SECONDS));
                    sendAck(peer, first, firstDataDatagram);

                    UdpChannel.ReceivedDatagram secondDataDatagram = peer.receive();
                    Packet second = PacketDecoder.decode(secondDataDatagram.data());
                    sendAck(peer, second, secondDataDatagram);

                    UdpChannel.ReceivedDatagram finishDatagram = peer.receive();
                    finishSeen.countDown();
                    assertTrue(releaseFinish.await(2, TimeUnit.SECONDS));
                    sendControl(peer, ControlMessage.createFinishAck(start.getTransferId(), true, null),
                            finishDatagram);
                } catch (Throwable throwable) {
                    peerFailure.set(throwable);
                }
            });
            peerThread.start();

            SenderEngine sender = new SenderEngine(senderChannel, InetAddress.getLoopbackAddress(),
                    peer.getLocalPort(), 2, 1, 1_000, 1, 1_000, 1, 1_000, 1, null);
            LiveMetricsProvider provider = sender;
            assertEquals(LiveMetricsSnapshot.LifecycleState.NOT_STARTED,
                    provider.getLiveMetricsSnapshot().lifecycleState());

            AtomicReference<TransferResult> result = new AtomicReference<>();
            AtomicReference<Throwable> senderFailure = new AtomicReference<>();
            Thread senderThread = new Thread(() -> {
                try {
                    result.set(sender.sendFile(input.toString()));
                } catch (Throwable throwable) {
                    senderFailure.set(throwable);
                }
            });
            senderThread.start();

            assertTrue(startSeen.await(2, TimeUnit.SECONDS));
            LiveMetricsSnapshot awaitingStart = awaitSnapshot(provider,
                    snapshot -> snapshot.lifecycleState() == LiveMetricsSnapshot.LifecycleState.AWAITING_START
                            && snapshot.senderObservations().startAttempts() == 1);
            assertAll(
                    () -> assertEquals(firstStartLength.get(), awaitingStart.endpointEmission()
                            .localUdpPayloadBytesEmitted()),
                    () -> assertEquals(0L, awaitingStart.metrics().getPacketsSent()),
                    () -> assertNotNull(awaitingStart.liveElapsedTimeSec()),
                    () -> assertTrue(awaitingStart.isProvisional()));

            releaseStart.countDown();
            assertTrue(firstDataSeen.await(2, TimeUnit.SECONDS));
            LiveMetricsSnapshot transferring = awaitSnapshot(provider,
                    snapshot -> snapshot.lifecycleState() == LiveMetricsSnapshot.LifecycleState.TRANSFERRING
                            && Long.valueOf(1L).equals(snapshot.metrics().getPacketsSent()));
            assertAll(
                    () -> assertEquals(0L, transferring.senderAcknowledgedPayloadBytes()),
                    () -> assertNull(transferring.metrics().getPayloadBytesDelivered()),
                    () -> assertFalse(transferring.endpointEmission().accountingComplete()));

            AtomicBoolean stopReader = new AtomicBoolean();
            AtomicReference<Throwable> readerFailure = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try {
                    while (!stopReader.get()) {
                        LiveMetricsSnapshot snapshot = provider.getLiveMetricsSnapshot();
                        assertEquals(snapshot.capturedAt(), snapshot.metrics().getCaptureTimestamp());
                        if (snapshot.isTerminal()) {
                            assertTrue(snapshot.endpointEmission().accountingComplete());
                        }
                        Thread.yield();
                    }
                } catch (Throwable throwable) {
                    readerFailure.set(throwable);
                }
            });
            reader.start();

            releaseFirstAck.countDown();
            assertTrue(finishSeen.await(2, TimeUnit.SECONDS));
            LiveMetricsSnapshot awaitingFinish = awaitSnapshot(provider,
                    snapshot -> snapshot.lifecycleState() == LiveMetricsSnapshot.LifecycleState.AWAITING_FINISH);
            assertAll(
                    () -> assertEquals(3L, awaitingFinish.senderAcknowledgedPayloadBytes()),
                    () -> assertEquals(2L, awaitingFinish.metrics().getPacketsAcked()),
                    () -> assertNotNull(awaitingFinish.senderAcknowledgedRateMbps()),
                    () -> assertNull(awaitingFinish.metrics().getThroughputMbps()));

            releaseFinish.countDown();
            senderThread.join(3_000);
            stopReader.set(true);
            reader.join(2_000);
            peerThread.join(3_000);
            assertAll(
                    () -> assertFalse(senderThread.isAlive()),
                    () -> assertFalse(peerThread.isAlive()),
                    () -> assertNull(senderFailure.get(), String.valueOf(senderFailure.get())),
                    () -> assertNull(peerFailure.get(), String.valueOf(peerFailure.get())),
                    () -> assertNull(readerFailure.get(), String.valueOf(readerFailure.get())),
                    () -> assertTrue(result.get().isSuccess()));

            LiveMetricsSnapshot terminal = provider.getLiveMetricsSnapshot();
            assertAll(
                    () -> assertEquals(LiveMetricsSnapshot.LifecycleState.SUCCEEDED,
                            terminal.lifecycleState()),
                    () -> assertTrue(terminal.isTerminal()),
                    () -> assertTrue(terminal.metrics().getTransferSuccess()),
                    () -> assertEquals(3L, terminal.senderAcknowledgedPayloadBytes()),
                    () -> assertTrue(terminal.endpointEmission().accountingComplete()));
        }
    }

    @Test
    void receiverExposesDeliveryIntegrityRecoveryAndLocalTerminalState(@TempDir Path tempDir)
            throws Exception {
        Path expected = tempDir.resolve("expected.bin");
        Path output = tempDir.resolve("received.bin");
        Files.write(expected, new byte[] {9});
        try (UdpChannel receiverChannel = new UdpChannel(0); UdpChannel sender = new UdpChannel()) {
            ReceiverEngine receiver = new ReceiverEngine(receiverChannel, 500, 500, 120, null);
            AtomicReference<TransferResult> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread receiverThread = new Thread(() -> {
                try {
                    result.set(receiver.receiveFile(output.toString()));
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            receiverThread.start();
            awaitSnapshot(receiver,
                    snapshot -> snapshot.lifecycleState() == LiveMetricsSnapshot.LifecycleState.AWAITING_START);

            InetAddress loopback = InetAddress.getLoopbackAddress();
            int port = receiverChannel.getLocalPort();
            sender.setReceiveTimeoutMillis(500);
            ControlMessage start = ControlMessage.createStart("one.bin", 1, 1);
            sendControl(sender, start, loopback, port);
            assertTrue(readControl(sender).isAccepted());
            awaitSnapshot(receiver,
                    snapshot -> snapshot.lifecycleState() == LiveMetricsSnapshot.LifecycleState.TRANSFERRING);

            sender.send(PacketEncoder.encode(Packet.createData(
                    UUID.fromString(start.getTransferId()), 0, new byte[] {9})), loopback, port);
            sender.receive();
            LiveMetricsSnapshot data = receiver.getLiveMetricsSnapshot();
            assertAll(
                    () -> assertEquals(1L, data.metrics().getPacketsReceived()),
                    () -> assertEquals(1L, data.metrics().getPayloadBytesDelivered()),
                    () -> assertNull(data.metrics().getIntegrityVerified()),
                    () -> assertTrue(data.endpointEmission().localUdpPayloadBytesEmitted() > 0),
                    () -> assertNull(data.metrics().getTransferSuccess()));

            sendControl(sender, ControlMessage.createFinish(
                    start.getTransferId(), FileHashUtil.sha256Hex(expected.toString())), loopback, port);
            assertTrue(readControl(sender).isVerified());
            LiveMetricsSnapshot recovery = awaitSnapshot(receiver,
                    snapshot -> snapshot.lifecycleState()
                            == LiveMetricsSnapshot.LifecycleState.COMPLETION_RECOVERY);
            assertAll(
                    () -> assertTrue(recovery.metrics().getIntegrityVerified()),
                    () -> assertNull(recovery.receiverObservations().localSuccess()),
                    () -> assertNull(recovery.metrics().getTransferSuccess()),
                    () -> assertTrue(recovery.isProvisional()));

            receiverThread.join(2_000);
            assertAll(
                    () -> assertFalse(receiverThread.isAlive()),
                    () -> assertNull(failure.get(), String.valueOf(failure.get())),
                    () -> assertTrue(result.get().isSuccess()));
            LiveMetricsSnapshot terminal = receiver.getLiveMetricsSnapshot();
            assertAll(
                    () -> assertEquals(LiveMetricsSnapshot.LifecycleState.SUCCEEDED,
                            terminal.lifecycleState()),
                    () -> assertTrue(terminal.receiverObservations().localSuccess()),
                    () -> assertNull(terminal.metrics().getTransferSuccess()),
                    () -> assertTrue(terminal.endpointEmission().accountingComplete()));
        }
    }

    @Test
    void handledStartTimeoutLeavesAccessibleFailedSnapshot(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("timeout.bin");
        Files.write(input, new byte[] {1});
        try (UdpChannel silentPeer = new UdpChannel(0); UdpChannel senderChannel = new UdpChannel()) {
            SenderEngine sender = new SenderEngine(senderChannel, InetAddress.getLoopbackAddress(),
                    silentPeer.getLocalPort(), 1024, 1, 50, 1, 25, 0, 25, 0, null);
            TransferResult result = sender.sendFile(input.toString());
            LiveMetricsSnapshot snapshot = sender.getLiveMetricsSnapshot();
            assertAll(
                    () -> assertFalse(result.isSuccess()),
                    () -> assertEquals(LiveMetricsSnapshot.LifecycleState.FAILED,
                            snapshot.lifecycleState()),
                    () -> assertFalse(snapshot.metrics().getTransferSuccess()),
                    () -> assertEquals("START_HANDSHAKE_TIMEOUT", snapshot.metrics().getFailureReason()),
                    () -> assertTrue(snapshot.endpointEmission().localUdpPayloadBytesEmitted() > 0),
                    () -> assertTrue(snapshot.endpointEmission().accountingComplete()));
        }
    }

    private static LiveMetricsSnapshot awaitSnapshot(
            LiveMetricsProvider provider,
            java.util.function.Predicate<LiveMetricsSnapshot> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        LiveMetricsSnapshot latest;
        do {
            latest = provider.getLiveMetricsSnapshot();
            if (condition.test(latest)) {
                return latest;
            }
            Thread.yield();
        } while (System.nanoTime() < deadline);
        fail("Timed out waiting for live state; latest=" + latest.lifecycleState());
        return latest;
    }

    private static ControlMessage control(UdpChannel.ReceivedDatagram datagram) {
        return ControlMessage.fromJson(new String(datagram.data(), StandardCharsets.UTF_8));
    }

    private static ControlMessage readControl(UdpChannel channel) throws Exception {
        return control(channel.receive());
    }

    private static void sendControl(UdpChannel channel, ControlMessage message,
                                    UdpChannel.ReceivedDatagram destination) throws Exception {
        channel.send(message.toJson().getBytes(StandardCharsets.UTF_8),
                destination.senderAddress(), destination.senderPort());
    }

    private static void sendControl(UdpChannel channel, ControlMessage message,
                                    InetAddress address, int port) throws Exception {
        channel.send(message.toJson().getBytes(StandardCharsets.UTF_8), address, port);
    }

    private static void sendAck(UdpChannel channel, Packet data,
                                UdpChannel.ReceivedDatagram destination) throws Exception {
        channel.send(PacketEncoder.encode(Packet.createAck(data.getTransferId(), data.getSeqNum())),
                destination.senderAddress(), destination.senderPort());
    }
}
