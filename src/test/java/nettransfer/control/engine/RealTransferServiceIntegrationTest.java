package nettransfer.control.engine;

import nettransfer.control.EvidenceSource;
import nettransfer.control.IntegrityStatus;
import nettransfer.control.TransferRequest;
import nettransfer.control.TransferSettings;
import nettransfer.control.TransferSnapshot;
import nettransfer.control.TransferState;
import nettransfer.control.TransferSummary;
import nettransfer.control.command.CommandDispatcher;
import nettransfer.control.command.CommandParser;
import nettransfer.control.command.CommandProposal;
import nettransfer.control.command.CommandValidator;
import nettransfer.control.command.DispatchResult;
import nettransfer.control.command.TransferConfiguration;
import nettransfer.integrity.FileHashUtil;
import nettransfer.net.UdpChannel;
import nettransfer.protocol.ControlMessage;
import nettransfer.protocol.MessageType;
import nettransfer.transfer.ReceiverEngine;
import nettransfer.transfer.SenderEngine;
import nettransfer.transfer.TransferResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;

import static nettransfer.control.TransferError.Code.TRANSFER_FAILED;
import static org.junit.jupiter.api.Assertions.*;

/** Local adapter checks only: these do not establish the engine's impaired-network reliability. */
@Timeout(10)
class RealTransferServiceIntegrationTest {
    @TempDir
    Path applicationRoot;
    private Path source;

    @BeforeEach
    void setUp() throws IOException {
        source = applicationRoot.resolve("data/input/report.bin");
        Files.createDirectories(source.getParent());
        byte[] bytes = new byte[8197];
        new Random(42).nextBytes(bytes);
        Files.write(source, bytes);
    }

    @Test
    void validatedCommandStartsRealEngineAndExposesRunningBeforeVerifiedMatchingHash() throws Exception {
        Path output = applicationRoot.resolve("fresh-received.bin");
        assertFalse(Files.exists(output));
        ExecutorService receiverWorker = Executors.newSingleThreadExecutor();
        try (GatedStartAckChannel channel = new GatedStartAckChannel();
             RealTransferService service = new RealTransferService(3000)) {
            channel.setReceiveTimeoutMillis(3000);
            ReceiverEngine receiverEngine = new ReceiverEngine(channel, 3000, 3000, 250, null);
            Future<TransferResult> receiver = receiverWorker.submit(
                    () -> receiverEngine.receiveFile(output.toString()));
            TransferConfiguration configuration = new TransferConfiguration(applicationRoot,
                    Map.of("report", Path.of("data/input/report.bin")),
                    Map.of("receiver-a", new InetSocketAddress("127.0.0.1", channel.getLocalPort())));
            CommandDispatcher dispatcher = new CommandDispatcher(new CommandParser(), new CommandValidator(configuration), service);

            DispatchResult.Started accepted = assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                    assertInstanceOf(DispatchResult.Started.class, dispatcher.dispatch(UUID.randomUUID(),
                            call("start_transfer", """
                                    {"file_id":"report","receiver_id":"receiver-a","window_bytes":4096,"timeout_ms":1000}
                                    """))));
            UUID id = accepted.acknowledgement().transferId();
            assertTrue(channel.startAckReady.await(2, TimeUnit.SECONDS));
            DispatchResult.Status status = assertInstanceOf(DispatchResult.Status.class,
                    dispatcher.dispatch(UUID.randomUUID(), call("status", "{\"transfer_id\":null}"),
                            new CommandDispatcher.Selection(id, id)));
            assertEquals(TransferState.RUNNING, status.snapshot().state());
            assertEquals(EvidenceSource.REAL, status.snapshot().evidenceSource());
            assertNull(status.snapshot().protocolTransferId());
            assertNull(status.snapshot().metrics().uniquePayloadBytesAcked());
            assertNull(status.snapshot().metrics().elapsedMillis());

            channel.allowStartAck.countDown();
            TransferSnapshot terminal = awaitTerminal(service, id);
            assertEquals(TransferState.COMPLETED, terminal.state());
            assertEquals(IntegrityStatus.VERIFIED, service.summary(id).integrity());
            assertTrue(receiver.get(2, TimeUnit.SECONDS).isSuccess());
            assertTrue(receiverEngine.getMetricsSnapshot().getIntegrityVerified());
            assertEquals(FileHashUtil.sha256Hex(source.toString()), FileHashUtil.sha256Hex(output.toString()));
            assertEquals(Files.size(source), terminal.metrics().fileSizeBytes());
            assertNull(terminal.metrics().totalChunks(), "The engine returns -1, not a measured zero");
            assertNull(terminal.metrics().uniquePayloadBytesAcked());
            assertNull(terminal.metrics().elapsedMillis());
            assertNull(terminal.protocolTransferId());
            assertEquals(4, service.summary(id).request().settings().windowPackets());
            assertEquals(1000, service.summary(id).request().settings().timeoutMillis());
        } finally {
            receiverWorker.shutdownNow();
            assertTrue(receiverWorker.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void silentPeerMakesInitialStartWaitFailWithinAFiniteDeadline() throws Exception {
        try (UdpChannel peer = new UdpChannel(); RealTransferService service = new RealTransferService(150)) {
            peer.setReceiveTimeoutMillis(2000);
            UUID id = service.start(request(peer.getLocalPort(), 1000)).transferId();
            ControlMessage start = message(peer.receive().data());
            assertEquals(MessageType.START, start.getType());

            TransferSnapshot terminal = awaitTerminal(service, id);
            assertEquals(TransferState.FAILED, terminal.state());
            assertEquals(TRANSFER_FAILED, terminal.error().code());
            assertEquals(IntegrityStatus.UNCONFIRMED, service.summary(id).integrity());
            assertTrue(service.summary(id).message().contains("START_HANDSHAKE_TIMEOUT"));
            assertNull(terminal.metrics().uniquePayloadBytesAcked());
            assertNull(terminal.metrics().elapsedMillis());

            int startAttempts = 1;
            peer.setReceiveTimeoutMillis(100);
            try {
                while (true) {
                    ControlMessage retry = message(peer.receive().data());
                    assertEquals(MessageType.START, retry.getType());
                    assertEquals(start.getTransferId(), retry.getTransferId());
                    startAttempts++;
                }
            } catch (SocketTimeoutException expected) {
                // All bounded retries have already been emitted before the terminal result.
            }
            assertEquals(SenderEngine.DEFAULT_START_RETRY_LIMIT + 1, startAttempts);
        }
    }

    @Test
    void closingAdapterReleasesARealBlockedReceiveAndItsUdpPort() throws Exception {
        try (UdpChannel peer = new UdpChannel(); RealTransferService service = new RealTransferService(30_000)) {
            peer.setReceiveTimeoutMillis(2000);
            UUID id = service.start(request(peer.getLocalPort(), 1000)).transferId();
            UdpChannel.ReceivedDatagram start = peer.receive();
            assertEquals(MessageType.START, message(start.data()).getType());
            assertEquals(TransferState.RUNNING, service.status(id).state());

            assertTimeoutPreemptively(Duration.ofSeconds(1), service::close);

            TransferSummary interrupted = service.summary(id);
            assertEquals(TransferState.FAILED, interrupted.finalSnapshot().state());
            assertEquals(IntegrityStatus.UNCONFIRMED, interrupted.integrity());
            assertTrue(interrupted.message().contains("interrupted"));
            try (UdpChannel rebound = new UdpChannel(start.senderPort())) {
                assertEquals(start.senderPort(), rebound.getLocalPort(), "Closed sender socket releases its port");
            }
        }
    }

    @Test
    void receiverSuccessWithoutFinishAckStillLeavesSenderFailedAndIntegrityUnconfirmed() throws Exception {
        Path output = applicationRoot.resolve("finish-ack-not-sent.bin");
        int finishTimeoutMillis = 40;
        int finishRetryLimit = 2;
        int receiverCompletionGraceMillis =
                finishTimeoutMillis * (finishRetryLimit + 1) + 100;
        ExecutorService receiverWorker = Executors.newSingleThreadExecutor();
        try (DroppedFinishAckChannel channel = new DroppedFinishAckChannel();
             RealTransferService service = serviceWithHandshakeTiming(
                     500, 2, finishTimeoutMillis, finishRetryLimit)) {
            channel.setReceiveTimeoutMillis(3000);
            ReceiverEngine receiverEngine = new ReceiverEngine(
                    channel, 3000, 3000, receiverCompletionGraceMillis, null);
            Future<TransferResult> receiver = receiverWorker.submit(
                    () -> receiverEngine.receiveFile(output.toString()));
            UUID id = service.start(request(channel.getLocalPort(), 300)).transferId();
            assertTrue(channel.finishAckDropped.await(1, TimeUnit.SECONDS));

            TransferSnapshot terminal = awaitTerminal(service, id);
            assertEquals(TransferState.FAILED, terminal.state());
            assertEquals(IntegrityStatus.UNCONFIRMED, service.summary(id).integrity());
            assertTrue(service.summary(id).message().contains("FINISH_HANDSHAKE_TIMEOUT"));
            assertNull(terminal.metrics().uniquePayloadBytesAcked());
            assertNull(terminal.metrics().elapsedMillis());

            assertTrue(receiver.get(2, TimeUnit.SECONDS).isSuccess());
            assertTrue(receiverEngine.getMetricsSnapshot().getIntegrityVerified());
            assertEquals(finishRetryLimit + 1, channel.finishAcksDropped.get());
            assertEquals(FileHashUtil.sha256Hex(source.toString()), FileHashUtil.sha256Hex(output.toString()));
        } finally {
            receiverWorker.shutdownNow();
            assertTrue(receiverWorker.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private TransferRequest request(int port, int dataTimeout) {
        return new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), "report", "receiver-a", source,
                new InetSocketAddress("127.0.0.1", port), new TransferSettings(1024, 4096, 4, dataTimeout, 5));
    }

    private static RealTransferService serviceWithHandshakeTiming(
            int startTimeoutMillis, int startRetryLimit,
            int finishTimeoutMillis, int finishRetryLimit) {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        return new RealTransferService(worker, Clock.systemUTC(), request -> {
            UdpChannel channel = new UdpChannel();
            try {
                var settings = request.settings();
                SenderEngine engine = new SenderEngine(channel, request.receiver().getAddress(),
                        request.receiver().getPort(), settings.chunkSizeBytes(), settings.windowPackets(),
                        settings.timeoutMillis(), settings.retryLimit(),
                        startTimeoutMillis, startRetryLimit,
                        finishTimeoutMillis, finishRetryLimit, null);
                return new RealTransferService.SenderSession() {
                    @Override
                    public TransferResult send() throws IOException {
                        return engine.sendFile(request.sourcePath().toString());
                    }

                    @Override
                    public void close() {
                        channel.close();
                    }
                };
            } catch (RuntimeException exception) {
                channel.close();
                throw exception;
            }
        });
    }

    private static TransferSnapshot awaitTerminal(RealTransferService service, UUID id) {
        return assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            TransferSnapshot snapshot;
            while ((snapshot = service.status(id)).state() == TransferState.RUNNING) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Terminal outcome wait interrupted");
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            return snapshot;
        });
    }

    private static CommandProposal.Calls call(String name, String arguments) {
        return new CommandProposal.Calls(List.of(new CommandProposal.ToolCall(name, arguments)));
    }

    private static ControlMessage message(byte[] bytes) {
        return ControlMessage.fromJson(new String(bytes, StandardCharsets.UTF_8));
    }

    /** Hold only START_ACK so RUNNING can be inspected deterministically without changing the engine. */
    private static final class GatedStartAckChannel extends UdpChannel {
        private final CountDownLatch startAckReady = new CountDownLatch(1);
        private final CountDownLatch allowStartAck = new CountDownLatch(1);

        private GatedStartAckChannel() throws SocketException { }

        @Override
        public void send(byte[] data, InetAddress address, int port) throws IOException {
            if (data.length > 0 && data[0] == '{' && message(data).getType() == MessageType.START_ACK) {
                startAckReady.countDown();
                try {
                    if (!allowStartAck.await(3, TimeUnit.SECONDS)) {
                        throw new IOException("Test START_ACK gate was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Test START_ACK gate interrupted", e);
                }
            }
            super.send(data, address, port);
        }

        @Override
        public void close() {
            allowStartAck.countDown();
            super.close();
        }
    }

    /** The receiver verifies locally but its final confirmation deliberately never reaches the sender. */
    private static final class DroppedFinishAckChannel extends UdpChannel {
        private final CountDownLatch finishAckDropped = new CountDownLatch(1);
        private final AtomicInteger finishAcksDropped = new AtomicInteger();

        private DroppedFinishAckChannel() throws SocketException { }

        @Override
        public void send(byte[] data, InetAddress address, int port) throws IOException {
            if (data.length > 0 && data[0] == '{' && message(data).getType() == MessageType.FINISH_ACK) {
                finishAcksDropped.incrementAndGet();
                finishAckDropped.countDown();
                return;
            }
            super.send(data, address, port);
        }
    }
}
