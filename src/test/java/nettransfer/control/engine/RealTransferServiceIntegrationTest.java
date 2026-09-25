package nettransfer.control.engine;

import com.google.gson.JsonParser;
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
import nettransfer.metrics.EndpointMetricsRecord;
import nettransfer.metrics.MetricsExporter;
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
        source = applicationRoot.resolve("storage/outgoing/report.bin");
        Files.createDirectories(source.getParent());
        byte[] bytes = new byte[8197];
        new Random(42).nextBytes(bytes);
        Files.write(source, bytes);
    }

    @Test
    void validatedCommandStartsRealEngineAndExposesRunningBeforeVerifiedMatchingHash() throws Exception {
        Path output = applicationRoot.resolve("fresh-received.bin");
        Path logs = applicationRoot.resolve("logs");
        assertFalse(Files.exists(output));
        ExecutorService receiverWorker = Executors.newSingleThreadExecutor();
        try (GatedStartAckChannel channel = new GatedStartAckChannel();
             RealTransferService service = new RealTransferService(3000, logs)) {
            channel.setReceiveTimeoutMillis(3000);
            ReceiverEngine receiverEngine = new ReceiverEngine(channel, 3000, 3000, 250, null);
            Future<TransferResult> receiver = receiverWorker.submit(
                    () -> receiverEngine.receiveFile(output.toString()));
            TransferConfiguration configuration = new TransferConfiguration(applicationRoot,
                    Map.of("report", Path.of("storage/outgoing/report.bin")),
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
            assertNotNull(status.snapshot().protocolTransferId());
            assertEquals(status.snapshot().protocolTransferId().toString(),
                    status.snapshot().metrics().authoritativeMetrics().getProtocolTransferId());
            assertEquals(id.toString(), status.snapshot().liveMetrics().context().getRunId());
            assertEquals(id.toString(), status.snapshot().liveMetrics().context().getApplicationTransferId());
            assertEquals(nettransfer.metrics.LiveMetricsSnapshot.LifecycleState.AWAITING_START,
                    status.snapshot().liveMetrics().lifecycleState());
            assertNull(status.snapshot().metrics().uniquePayloadBytesAcked(),
                    "No DATA payload can be acknowledged before START_ACK");
            assertTrue(status.snapshot().metrics().elapsedMillis() >= 0);
            assertTrue(status.snapshot().liveMetrics().isProvisional());

            channel.allowStartAck.countDown();
            TransferSnapshot terminal = awaitTerminal(service, id);
            assertEquals(TransferState.COMPLETED, terminal.state());
            assertEquals(IntegrityStatus.VERIFIED, service.summary(id).integrity());
            assertTrue(receiver.get(2, TimeUnit.SECONDS).isSuccess());
            assertTrue(receiverEngine.getMetricsSnapshot().getIntegrityVerified());
            assertEquals(FileHashUtil.sha256Hex(source.toString()), FileHashUtil.sha256Hex(output.toString()));
            assertEquals(Files.size(source), terminal.metrics().fileSizeBytes());
            assertNull(terminal.metrics().totalChunks(), "No duplicate control-layer chunk metric exists");
            assertEquals(Files.size(source), terminal.metrics().uniquePayloadBytesAcked());
            assertTrue(terminal.metrics().elapsedMillis() >= 0);
            assertEquals(status.snapshot().protocolTransferId(), terminal.protocolTransferId());
            assertEquals(nettransfer.metrics.LiveMetricsSnapshot.LifecycleState.SUCCEEDED,
                    terminal.liveMetrics().lifecycleState());
            assertFalse(terminal.liveMetrics().isProvisional());
            assertTrue(terminal.liveMetrics().metrics().getPacketsSent() > 0);
            assertTrue(terminal.liveMetrics().metrics().getAcksReceived() > 0);
            assertTrue(terminal.liveMetrics().metrics().getPacketsAcked() > 0);
            assertTrue(terminal.liveMetrics().senderAcknowledgedRateMbps() >= 0.0);
            assertNull(terminal.liveMetrics().receiverObservations());
            assertNull(terminal.liveMetrics().metrics().getPayloadBytesDelivered());
            assertNotNull(terminal.liveMetrics().metrics().getUnavailableReasons()
                    .get("payload_bytes_delivered"));
            assertSame(terminal, service.status(id), "Final engine evidence remains retained after close");
            assertEquals(4, service.summary(id).request().settings().windowPackets());
            assertEquals(1000, service.summary(id).request().settings().timeoutMillis());

            Path runDirectory = logs.resolve("standalone").resolve(id.toString());
            assertTrue(Files.isRegularFile(runDirectory.resolve("events-sender.jsonl")));
            assertTrue(Files.isRegularFile(runDirectory.resolve("run-state.json")));
            assertTrue(Files.isRegularFile(runDirectory.resolve("endpoint-sender.json")));
            try (var lines = Files.lines(runDirectory.resolve("events-sender.jsonl"))) {
                var events = lines.map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
                assertFalse(events.isEmpty());
                assertTrue(events.stream().allMatch(event -> id.toString().equals(
                        event.get("application_transfer_id").getAsString())));
                assertTrue(events.stream().anyMatch(event -> !event.get("protocol_transfer_id").isJsonNull()
                        && terminal.protocolTransferId().toString().equals(
                        event.get("protocol_transfer_id").getAsString())));
            }
            EndpointMetricsRecord endpoint = MetricsExporter.readEndpointRecord(
                    runDirectory.resolve("endpoint-sender.json"));
            assertAll(
                    () -> assertEquals(id.toString(), endpoint.runId()),
                    () -> assertEquals(id.toString(), endpoint.applicationTransferId()),
                    () -> assertEquals(terminal.protocolTransferId().toString(),
                            endpoint.protocolTransferId()),
                    () -> assertEquals("approved-file:report", endpoint.fileAttribution()),
                    () -> assertEquals(1024L, endpoint.configuration().getChunkSizeBytes()),
                    () -> assertEquals(4096L, endpoint.configuration().getWindowBytesRequested()),
                    () -> assertEquals(4L, endpoint.configuration().getWindowPackets()),
                    () -> assertEquals(1000L, endpoint.configuration().getTimeoutMs()),
                    () -> assertEquals(5L, endpoint.configuration().getRetryLimit()),
                    () -> assertEquals(3000L, endpoint.configuration().getStartHandshakeTimeoutMs()),
                    () -> assertEquals((long) SenderEngine.DEFAULT_START_RETRY_LIMIT,
                            endpoint.configuration().getStartRetryLimit()),
                    () -> assertEquals((long) SenderEngine.DEFAULT_FINISH_HANDSHAKE_TIMEOUT_MS,
                            endpoint.configuration().getFinishHandshakeTimeoutMs()),
                    () -> assertEquals((long) SenderEngine.DEFAULT_FINISH_RETRY_LIMIT,
                            endpoint.configuration().getFinishRetryLimit()),
                    () -> assertEquals(id.toString(), endpoint.metrics().getApplicationTransferId()),
                    () -> assertEquals(endpoint.protocolTransferId(),
                            endpoint.metrics().getProtocolTransferId()));
        } finally {
            receiverWorker.shutdownNow();
            assertTrue(receiverWorker.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void silentPeerMakesInitialStartWaitFailWithinAFiniteDeadline() throws Exception {
        Path logs = applicationRoot.resolve("failed-logs");
        try (UdpChannel peer = new UdpChannel();
             RealTransferService service = new RealTransferService(150, logs)) {
            peer.setReceiveTimeoutMillis(2000);
            UUID id = service.start(request(peer.getLocalPort(), 1000)).transferId();
            ControlMessage start = message(peer.receive().data());
            assertEquals(MessageType.START, start.getType());

            TransferSnapshot terminal = awaitTerminal(service, id);
            assertEquals(TransferState.FAILED, terminal.state());
            assertEquals(TRANSFER_FAILED, terminal.error().code());
            assertEquals(IntegrityStatus.UNCONFIRMED, service.summary(id).integrity());
            assertTrue(service.summary(id).message().contains("START_HANDSHAKE_TIMEOUT"));
            assertNull(terminal.metrics().uniquePayloadBytesAcked(),
                    "A failed START establishes no DATA acknowledgement observation");
            assertTrue(terminal.metrics().elapsedMillis() >= 150L * (SenderEngine.DEFAULT_START_RETRY_LIMIT + 1));
            assertEquals(SenderEngine.DEFAULT_START_RETRY_LIMIT + 1,
                    terminal.liveMetrics().senderObservations().startAttempts());
            assertEquals(nettransfer.metrics.LiveMetricsSnapshot.LifecycleState.FAILED,
                    terminal.liveMetrics().lifecycleState());

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
            EndpointMetricsRecord endpoint = MetricsExporter.readEndpointRecord(logs.resolve("standalone")
                    .resolve(id.toString()).resolve("endpoint-sender.json"));
            assertEquals("FAILED", endpoint.terminalOutcome());
            assertTrue(endpoint.evidenceComplete());
            assertEquals(id.toString(), endpoint.applicationTransferId());
            assertEquals(terminal.protocolTransferId().toString(), endpoint.protocolTransferId());
        }
    }

    @Test
    void loggingInitializationFailureCannotBeReportedAsCompleted() throws Exception {
        Path invalidRoot = Files.writeString(applicationRoot.resolve("not-a-directory"), "occupied");
        try (RealTransferService service = new RealTransferService(150, invalidRoot)) {
            UUID id = service.start(request(9000, 1000)).transferId();
            TransferSnapshot terminal = awaitTerminal(service, id);

            assertEquals(TransferState.FAILED, terminal.state());
            assertEquals(IntegrityStatus.UNCONFIRMED, service.summary(id).integrity());
            assertTrue(service.summary(id).message().contains("Transfer failed"));
            assertNull(terminal.protocolTransferId(),
                    "The engine never starts when its required logger cannot be initialized");
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
            assertEquals(Files.size(source), terminal.metrics().uniquePayloadBytesAcked());
            assertTrue(terminal.metrics().elapsedMillis() > 0);
            assertEquals(nettransfer.metrics.LiveMetricsSnapshot.LifecycleState.FAILED,
                    terminal.liveMetrics().lifecycleState());
            assertEquals(Boolean.FALSE, terminal.metrics().authoritativeMetrics().getTransferSuccess());
            assertNull(terminal.metrics().authoritativeMetrics().getIntegrityVerified());
            assertNull(terminal.liveMetrics().receiverObservations());

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
                var baseContext = RealTransferService.senderContext(request, startTimeoutMillis);
                var context = baseContext.toBuilder().configuration(
                        baseContext.getConfiguration().toBuilder()
                                .startRetryLimit((long) startRetryLimit)
                                .finishHandshakeTimeoutMs((long) finishTimeoutMillis)
                                .finishRetryLimit((long) finishRetryLimit)
                                .build()).build();
                SenderEngine engine = new SenderEngine(channel, request.receiver().getAddress(),
                        request.receiver().getPort(), settings.chunkSizeBytes(), settings.windowPackets(),
                        settings.timeoutMillis(), settings.retryLimit(),
                        startTimeoutMillis, startRetryLimit,
                        finishTimeoutMillis, finishRetryLimit, context);
                return new RealTransferService.SenderSession() {
                    @Override
                    public TransferResult send() throws IOException {
                        return engine.sendFile(request.sourcePath().toString());
                    }

                    @Override
                    public nettransfer.metrics.LiveMetricsSnapshot liveMetricsSnapshot() {
                        return engine.getLiveMetricsSnapshot();
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
