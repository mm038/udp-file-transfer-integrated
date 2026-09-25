package nettransfer.net;

import nettransfer.protocol.Packet;
import nettransfer.protocol.PacketEncoder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class UdpChannelImpairmentTest {
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    @Test
    void lossDropsEveryEligibleArrivalIncludingRetransmissionsButBytesWereEmitted() throws Exception {
        UUID transferId = UUID.randomUUID();
        List<ImpairmentObservation> observations = new ArrayList<>();
        try (UdpChannel receiver = new UdpChannel(settings(100, 0)); UdpChannel sender = new UdpChannel()) {
            receiver.beginImpairment("receiver", LOOPBACK, sender.getLocalPort(), transferId, observations::add);
            receiver.setReceiveTimeoutMillis(60);
            AtomicLong emitted = new AtomicLong();
            sender.setSuccessfulSendObserver(emitted::addAndGet);
            byte[] data = PacketEncoder.encode(Packet.createData(transferId, 0, new byte[]{1, 2, 3}));
            sender.send(data, LOOPBACK, receiver.getLocalPort());
            sender.send(data, LOOPBACK, receiver.getLocalPort());
            assertThrows(SocketTimeoutException.class, receiver::receive);
            assertEquals(2L * data.length, emitted.get());
            assertEquals(List.of("DROP", "DROP"), actions(observations));
            assertEquals(List.of(1L, 2L), observations.stream().map(ImpairmentObservation::decisionIndex).toList());
            assertTrue(observations.stream().allMatch(item -> item.sequenceNumber() == 0));
        }
    }

    @Test
    void delayedAckSurvivesCallerTimeoutAndIsDeliveredOnce() throws Exception {
        UUID transferId = UUID.randomUUID();
        List<ImpairmentObservation> observations = new ArrayList<>();
        try (UdpChannel sender = new UdpChannel(settings(100, 140)); UdpChannel receiver = new UdpChannel()) {
            sender.beginImpairment("sender", LOOPBACK, receiver.getLocalPort(), transferId, observations::add);
            sender.setReceiveTimeoutMillis(30);
            byte[] ack = PacketEncoder.encode(Packet.createAck(transferId, 0));
            long started = System.nanoTime();
            receiver.send(ack, LOOPBACK, sender.getLocalPort());
            assertThrows(SocketTimeoutException.class, sender::receive);
            assertEquals(List.of("DELAY"), actions(observations));
            sender.setReceiveTimeoutMillis(500);
            assertArrayEquals(ack, sender.receive().data());
            assertTrue(elapsedMillis(started) >= 120, "Delay must not disappear when caller retries receive");
            assertEquals(List.of("DELAY", "DELIVER"), actions(observations));
            assertEquals(observations.get(0).decisionIndex(), observations.get(1).decisionIndex());
            sender.setReceiveTimeoutMillis(20);
            assertThrows(SocketTimeoutException.class, sender::receive);
        }
    }

    @Test
    void infiniteCallerTimeoutStillWakesForScheduledDelivery() throws Exception {
        UUID transferId = UUID.randomUUID();
        try (UdpChannel receiver = new UdpChannel(settings(0, 50)); UdpChannel sender = new UdpChannel()) {
            receiver.beginImpairment("receiver", LOOPBACK, sender.getLocalPort(), transferId, ignored -> {});
            byte[] data = PacketEncoder.encode(Packet.createData(transferId, 0, new byte[0]));
            sender.send(data, LOOPBACK, receiver.getLocalPort());
            assertArrayEquals(data, receiver.receive().data());
        }
    }

    @Test
    void delayQueuesAWindowOfAcksTogetherInsteadOfSleepingForEachPacket() throws Exception {
        UUID transferId = UUID.randomUUID();
        List<ImpairmentObservation> observations = new ArrayList<>();
        try (UdpChannel sender = new UdpChannel(settings(0, 120)); UdpChannel receiver = new UdpChannel()) {
            sender.beginImpairment("sender", LOOPBACK, receiver.getLocalPort(), transferId, observations::add);
            sender.setReceiveTimeoutMillis(1000);
            for (int sequence = 0; sequence < 4; sequence++) {
                receiver.send(PacketEncoder.encode(Packet.createAck(transferId, sequence)), LOOPBACK, sender.getLocalPort());
            }
            sender.receive();
            assertEquals(4, observations.stream().filter(item -> item.action().equals("DELAY")).count(),
                    "Every queued ACK must acquire its own due time before the first one is released");
            for (int remaining = 0; remaining < 3; remaining++) {
                sender.receive();
            }
            assertEquals(4, observations.stream().filter(item -> item.action().equals("DELIVER")).count());
            assertEquals(List.of(0, 1, 2, 3), observations.stream()
                    .filter(item -> item.action().equals("DELIVER"))
                    .map(ImpairmentObservation::sequenceNumber).toList());
        }
    }

    @Test
    void invalidNegativeAckBypassesSimulator() throws Exception {
        UUID transferId = UUID.randomUUID();
        List<ImpairmentObservation> observations = new ArrayList<>();
        try (UdpChannel sender = new UdpChannel(settings(0, 500)); UdpChannel receiver = new UdpChannel()) {
            sender.beginImpairment("sender", LOOPBACK, receiver.getLocalPort(), transferId, observations::add);
            sender.setReceiveTimeoutMillis(100);
            byte[] invalid = PacketEncoder.encode(Packet.createAck(transferId, -2));
            receiver.send(invalid, LOOPBACK, sender.getLocalPort());
            assertArrayEquals(invalid, sender.receive().data());
            assertTrue(observations.isEmpty());
        }
    }

    @Test
    void unrelatedControlMalformedAndWrongPeerPacketsPassWithoutDecisions() throws Exception {
        UUID transferId = UUID.randomUUID();
        List<ImpairmentObservation> observations = new ArrayList<>();
        try (UdpChannel receiver = new UdpChannel(settings(100, 500));
             UdpChannel sender = new UdpChannel(); UdpChannel stranger = new UdpChannel()) {
            receiver.beginImpairment("receiver", LOOPBACK, sender.getLocalPort(), transferId, observations::add);
            receiver.setReceiveTimeoutMillis(100);
            byte[] valid = PacketEncoder.encode(Packet.createData(transferId, 0, new byte[]{1}));
            byte[] corrupt = valid.clone();
            corrupt[corrupt.length - 1] ^= 1;
            byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
            for (byte[] bytes : List.of(new byte[]{'{', '}'},
                    PacketEncoder.encode(Packet.createData(UUID.randomUUID(), 0, new byte[]{1})),
                    PacketEncoder.encode(Packet.createAck(transferId, 0)), corrupt, trailing,
                    Arrays.copyOf(valid, valid.length - 1))) {
                sender.send(bytes, LOOPBACK, receiver.getLocalPort());
                assertArrayEquals(bytes, receiver.receive().data());
            }
            stranger.send(valid, LOOPBACK, receiver.getLocalPort());
            assertArrayEquals(valid, receiver.receive().data());
            assertTrue(observations.isEmpty());
        }
    }

    @Test
    void randomLossIsRepeatableAndUnaffectedByUnrelatedTraffic() throws Exception {
        List<ImpairmentObservation> first = decisions(false);
        List<ImpairmentObservation> second = decisions(true);
        assertEquals(actions(first), actions(second));
        assertEquals(24, first.size());
        Random expected = new Random(1234);
        for (int index = 0; index < first.size(); index++) {
            assertEquals(expected.nextDouble() < 0.5 ? "DROP" : "DELIVER", first.get(index).action());
            assertEquals(index + 1, first.get(index).decisionIndex());
        }
    }

    @Test
    void continuedDropsDoNotRestartTheReceiveDeadline() throws Exception {
        UUID transferId = UUID.randomUUID();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (UdpChannel receiver = new UdpChannel(settings(100, 0)); UdpChannel sender = new UdpChannel()) {
            receiver.beginImpairment("receiver", LOOPBACK, sender.getLocalPort(), transferId, ignored -> {});
            receiver.setReceiveTimeoutMillis(100);
            byte[] data = PacketEncoder.encode(Packet.createData(transferId, 0, new byte[0]));
            CountDownLatch sending = new CountDownLatch(1);
            Future<?> task = executor.submit(() -> {
                try {
                    for (int index = 0; index < 50; index++) {
                        sender.send(data, LOOPBACK, receiver.getLocalPort());
                        sending.countDown();
                        Thread.sleep(10);
                    }
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                } catch (IOException failure) {
                    throw new RuntimeException(failure);
                }
            });
            assertTrue(sending.await(1, TimeUnit.SECONDS));
            long started = System.nanoTime();
            assertThrows(SocketTimeoutException.class, receiver::receive);
            assertTrue(elapsedMillis(started) < 350, "Dropped traffic must not extend the 100 ms deadline");
            task.cancel(true);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void queueOverflowFailsExplicitlyAndPendingDataIsCancelled() throws Exception {
        UUID transferId = UUID.randomUUID();
        List<ImpairmentObservation> observations = new ArrayList<>();
        try (UdpChannel receiver = new UdpChannel(0, settings(0, 500), 1); UdpChannel sender = new UdpChannel()) {
            receiver.beginImpairment("receiver", LOOPBACK, sender.getLocalPort(), transferId, observations::add);
            receiver.setReceiveTimeoutMillis(1000);
            sender.send(PacketEncoder.encode(Packet.createData(transferId, 0, new byte[]{1})), LOOPBACK, receiver.getLocalPort());
            sender.send(PacketEncoder.encode(Packet.createData(transferId, 1, new byte[]{2})), LOOPBACK, receiver.getLocalPort());
            IOException failure = assertThrows(IOException.class, receiver::receive);
            assertTrue(failure.getMessage().contains("queue capacity"));
            receiver.finishImpairment();
            assertEquals(List.of("DELAY", "QUEUE_OVERFLOW", "CANCEL"), actions(observations));
            receiver.setReceiveTimeoutMillis(30);
            assertThrows(SocketTimeoutException.class, receiver::receive);
        }
    }

    @Test
    void finishCancelsDelayedPacketsAndNextScopeStartsWithFreshDecisions() throws Exception {
        UUID transferId = UUID.randomUUID();
        List<ImpairmentObservation> observations = new ArrayList<>();
        try (UdpChannel receiver = new UdpChannel(settings(0, 150)); UdpChannel sender = new UdpChannel()) {
            receiver.beginImpairment("receiver", LOOPBACK, sender.getLocalPort(), transferId, observations::add);
            receiver.setReceiveTimeoutMillis(20);
            sender.send(PacketEncoder.encode(Packet.createData(transferId, 0, new byte[]{1})), LOOPBACK, receiver.getLocalPort());
            assertThrows(SocketTimeoutException.class, receiver::receive);
            receiver.finishImpairment();
            assertEquals(List.of("DELAY", "CANCEL"), actions(observations));
            receiver.beginImpairment("receiver", LOOPBACK, sender.getLocalPort(), transferId, observations::add);
            receiver.setReceiveTimeoutMillis(200);
            assertThrows(SocketTimeoutException.class, receiver::receive, "The old queued packet must not reach a new scope");
            sender.send(PacketEncoder.encode(Packet.createData(transferId, 1, new byte[]{2})), LOOPBACK, receiver.getLocalPort());
            assertEquals(1, nettransfer.protocol.PacketDecoder.decode(receiver.receive().data()).getSeqNum());
            assertEquals(1, observations.get(2).decisionIndex());
        }
    }

    @Test
    void closeCancelsQueuedDatagramsExactlyOnce() throws Exception {
        UUID transferId = UUID.randomUUID();
        List<ImpairmentObservation> observations = new ArrayList<>();
        try (UdpChannel receiver = new UdpChannel(settings(0, 500)); UdpChannel sender = new UdpChannel()) {
            receiver.beginImpairment("receiver", LOOPBACK, sender.getLocalPort(), transferId, observations::add);
            receiver.setReceiveTimeoutMillis(20);
            sender.send(PacketEncoder.encode(Packet.createData(transferId, 0, new byte[0])), LOOPBACK, receiver.getLocalPort());
            assertThrows(SocketTimeoutException.class, receiver::receive);
            receiver.close();
            receiver.close();
            assertEquals(List.of("DELAY", "CANCEL"), actions(observations));
            assertThrows(IOException.class, receiver::receive);
        }
    }

    @Test
    void concurrentCloseUnblocksReceiveAndCancelsOnlyOnce() throws Exception {
        UUID transferId = UUID.randomUUID();
        List<ImpairmentObservation> observations = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch delayed = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (UdpChannel receiver = new UdpChannel(settings(0, 5000)); UdpChannel sender = new UdpChannel()) {
            receiver.beginImpairment("receiver", LOOPBACK, sender.getLocalPort(), transferId, observation -> {
                observations.add(observation);
                if (observation.action().equals("DELAY")) {
                    delayed.countDown();
                }
            });
            sender.send(PacketEncoder.encode(Packet.createData(transferId, 0, new byte[0])), LOOPBACK, receiver.getLocalPort());
            Future<?> waiting = executor.submit(() -> assertThrows(IOException.class, receiver::receive));
            assertTrue(delayed.await(1, TimeUnit.SECONDS));
            receiver.close();
            waiting.get(1, TimeUnit.SECONDS);
            receiver.close();
            assertEquals(List.of("DELAY", "CANCEL"), actions(observations));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void invalidScopeAndReplacingAnActiveScopeAreRejected() throws Exception {
        UUID transferId = UUID.randomUUID();
        try (UdpChannel channel = new UdpChannel(settings(0, 0)); UdpChannel peer = new UdpChannel()) {
            assertThrows(IllegalArgumentException.class,
                    () -> channel.beginImpairment("unknown", LOOPBACK, peer.getLocalPort(), transferId, ignored -> {}));
            assertThrows(IllegalArgumentException.class,
                    () -> channel.beginImpairment("receiver", LOOPBACK, 0, transferId, ignored -> {}));
            channel.beginImpairment("receiver", LOOPBACK, peer.getLocalPort(), transferId, ignored -> {});
            assertThrows(IllegalStateException.class,
                    () -> channel.beginImpairment("receiver", LOOPBACK, peer.getLocalPort(), transferId, ignored -> {}));
        }
    }

    private static List<ImpairmentObservation> decisions(boolean unrelatedTraffic) throws Exception {
        UUID transferId = UUID.randomUUID();
        List<ImpairmentObservation> observations = new ArrayList<>();
        try (UdpChannel receiver = new UdpChannel(settings(50, 0)); UdpChannel sender = new UdpChannel()) {
            receiver.beginImpairment("receiver", LOOPBACK, sender.getLocalPort(), transferId, observations::add);
            receiver.setReceiveTimeoutMillis(500);
            byte[] marker = new byte[]{'{', '}'};
            for (int index = 0; index < 24; index++) {
                if (unrelatedTraffic) {
                    sender.send(PacketEncoder.encode(Packet.createData(UUID.randomUUID(), index, new byte[0])), LOOPBACK,
                            receiver.getLocalPort());
                }
                sender.send(PacketEncoder.encode(Packet.createData(transferId, index, new byte[]{1})), LOOPBACK,
                        receiver.getLocalPort());
            }
            sender.send(marker, LOOPBACK, receiver.getLocalPort());
            while (!Arrays.equals(marker, receiver.receive().data())) {
                // Only eligible DATA consumes a random decision; the marker ends this bounded receive loop.
            }
        }
        return observations;
    }

    private static List<String> actions(List<ImpairmentObservation> observations) {
        return observations.stream().map(ImpairmentObservation::action).toList();
    }

    private static ImpairmentSettings settings(double loss, int delay) {
        return new ImpairmentSettings(true, loss, delay, 1234, "test-impairment");
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
