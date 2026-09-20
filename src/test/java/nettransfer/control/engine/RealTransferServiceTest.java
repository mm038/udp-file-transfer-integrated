package nettransfer.control.engine;

import nettransfer.control.EvidenceSource;
import nettransfer.control.IntegrityStatus;
import nettransfer.control.TransferError;
import nettransfer.control.TransferRequest;
import nettransfer.control.TransferServiceException;
import nettransfer.control.TransferSettings;
import nettransfer.control.TransferSnapshot;
import nettransfer.control.TransferStart;
import nettransfer.control.TransferState;
import nettransfer.control.TransferSummary;
import nettransfer.transfer.TransferResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static nettransfer.control.TransferError.Code.*;
import static org.junit.jupiter.api.Assertions.*;

/** Tests the asynchronous wrapper with gated sessions, without UDP or inferred metrics. */
@Timeout(8)
class RealTransferServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-20T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final List<ExecutorService> executors = new ArrayList<>();

    @TempDir
    Path directory;
    private Path source;

    @BeforeEach
    void setUp() throws IOException {
        source = Files.writeString(directory.resolve("report.txt"), "report\n");
    }

    @AfterEach
    void stopWorkers() throws InterruptedException {
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS), "Test worker must terminate");
        }
    }

    @Test
    void acceptanceAndStatusReturnWhileSenderIsBlockedWithoutInventingProgress() throws Exception {
        ExecutorService worker = worker();
        GateSession session = new GateSession(TransferResult.success(-1));
        try (RealTransferService service = new RealTransferService(worker, CLOCK, request -> session)) {
            TransferRequest request = request();
            TransferStart accepted = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> service.start(request));
            await(session.entered);

            TransferSnapshot running = assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> service.status(accepted.transferId()));
            assertEquals(request.transferId(), accepted.transferId());
            assertEquals(accepted.transferId(), accepted.runId());
            assertNull(accepted.protocolTransferId());
            assertEquals(NOW, accepted.acceptedAt());
            assertEquals(EvidenceSource.REAL, accepted.evidenceSource());
            assertEquals(TransferState.RUNNING, running.state());
            assertEquals(7L, running.metrics().fileSizeBytes());
            assertNull(running.metrics().uniquePayloadBytesAcked());
            assertNull(running.metrics().elapsedMillis());
            assertNull(running.metrics().totalChunks());
            assertNotNull(running.metrics().unavailableReason());
            assertSame(running, service.status(accepted.transferId()), "Status reads cannot create evidence");
            assertError(SUMMARY_NOT_READY, () -> service.summary(accepted.runId()));

            session.release.countDown();
            drain(worker);
            assertEquals(TransferState.COMPLETED, service.status(accepted.transferId()).state());
            assertEquals(TransferState.RUNNING, running.state(), "Earlier snapshots stay frozen");
            assertEquals(1, session.sendCalls.get());
            assertEquals(1, session.closeCalls.get());
        }
    }

    @Test
    void simultaneousStartsReserveExactlyOneActiveSlot() throws Exception {
        ExecutorService worker = worker();
        ExecutorService callers = worker(2);
        GateSession session = new GateSession(TransferResult.success(-1));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger opened = new AtomicInteger();
        try (RealTransferService service = new RealTransferService(worker, CLOCK, request -> {
            opened.incrementAndGet();
            return session;
        })) {
            List<Future<Object>> attempts = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                TransferRequest request = request();
                attempts.add(callers.submit(() -> {
                    ready.countDown();
                    await(go);
                    try {
                        return service.start(request);
                    } catch (TransferServiceException e) {
                        return e.error();
                    }
                }));
            }
            await(ready);
            go.countDown();
            List<Object> results = List.of(attempts.get(0).get(2, TimeUnit.SECONDS),
                    attempts.get(1).get(2, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(TransferStart.class::isInstance).count());
            TransferError rejected = results.stream().filter(TransferError.class::isInstance)
                    .map(TransferError.class::cast).findFirst().orElseThrow();
            assertEquals(TRANSFER_BUSY, rejected.code());
            await(session.entered);
            assertEquals(1, opened.get());
        } finally {
            go.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 12})
    void verifiedSuccessPreservesOnlyAvailableEngineChunkCounts(int chunks) throws Exception {
        ExecutorService worker = worker();
        try (RealTransferService service = new RealTransferService(worker, CLOCK,
                request -> immediate(TransferResult.success(chunks)))) {
            TransferRequest request = request();
            UUID id = service.start(request).transferId();
            drain(worker);

            TransferSummary summary = service.summary(id);
            TransferSnapshot terminal = summary.finalSnapshot();
            assertSame(request, summary.request());
            assertEquals(TransferState.COMPLETED, terminal.state());
            assertEquals(IntegrityStatus.VERIFIED, summary.integrity());
            assertEquals(chunks == -1 ? null : Integer.valueOf(chunks), terminal.metrics().totalChunks());
            assertNull(terminal.metrics().uniquePayloadBytesAcked());
            assertNull(terminal.metrics().elapsedMillis());
            assertNull(terminal.protocolTransferId());
            assertNull(terminal.error());
            assertEquals(EvidenceSource.REAL, terminal.evidenceSource());
            assertSame(summary, service.summary(id));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"START rejected", "Retry limit exceeded", "FINISH_ACK verification failed: SHA-256 mismatch"})
    void failedEngineResultDoesNotParseMessageIntoAnIntegrityMeasurement(String message) throws Exception {
        ExecutorService worker = worker();
        try (RealTransferService service = new RealTransferService(worker, CLOCK,
                request -> immediate(TransferResult.failure(message)))) {
            UUID id = service.start(request()).transferId();
            drain(worker);

            TransferSummary summary = service.summary(id);
            assertEquals(TransferState.FAILED, summary.finalSnapshot().state());
            assertEquals(TRANSFER_FAILED, summary.finalSnapshot().error().code());
            assertEquals(IntegrityStatus.UNCONFIRMED, summary.integrity());
            assertEquals(message, summary.message());
            assertNull(summary.finalSnapshot().metrics().totalChunks());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void checkedAndUncheckedSenderFailuresBecomeTerminalOutcomesAndCloseSession(boolean unchecked) throws Exception {
        ExecutorService worker = worker();
        AtomicInteger closes = new AtomicInteger();
        try (RealTransferService service = new RealTransferService(worker, CLOCK, request -> new RealTransferService.SenderSession() {
            @Override
            public TransferResult send() throws IOException {
                if (unchecked) {
                    throw new IllegalArgumentException("malformed engine response");
                }
                throw new IOException("network failed");
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        })) {
            UUID id = service.start(request()).transferId();
            drain(worker);
            assertEquals(TransferState.FAILED, service.status(id).state());
            assertEquals(IntegrityStatus.UNCONFIRMED, service.summary(id).integrity());
            assertTrue(service.summary(id).message().contains(unchecked ? "malformed engine response" : "network failed"));
            assertEquals(1, closes.get());
        }
    }

    @Test
    void escapedReceiveTimeoutDoesNotGuessStartOrFinishPhase() throws Exception {
        ExecutorService worker = worker();
        try (RealTransferService service = new RealTransferService(worker, CLOCK, request -> {
            throw new SocketTimeoutException("Receive timed out");
        })) {
            UUID id = service.start(request()).transferId();
            drain(worker);
            TransferSummary summary = service.summary(id);
            assertEquals(TransferState.FAILED, summary.finalSnapshot().state());
            assertEquals(IntegrityStatus.UNCONFIRMED, summary.integrity());
            assertTrue(summary.message().contains("Timed out"));
            assertFalse(summary.message().contains("START"));
            assertFalse(summary.message().contains("FINISH"));
        }
    }

    @Test
    void disappearingSourceFailsBeforeOpeningAnEngineSession() throws Exception {
        ExecutorService worker = worker();
        AtomicInteger opened = new AtomicInteger();
        TransferRequest request = request();
        Files.delete(source);
        try (RealTransferService service = new RealTransferService(worker, CLOCK, ignored -> {
            opened.incrementAndGet();
            return immediate(TransferResult.success(-1));
        })) {
            service.start(request);
            drain(worker);
            TransferSnapshot failed = service.status(request.transferId());
            assertEquals(TransferState.FAILED, failed.state());
            assertNull(failed.metrics().fileSizeBytes());
            assertEquals(0, opened.get());
        }
    }

    @Test
    void unknownIdsAndInProgressSummaryProduceTypedErrors() throws Exception {
        GateSession session = new GateSession(TransferResult.success(-1));
        try (RealTransferService service = new RealTransferService(worker(), CLOCK, request -> session)) {
            assertError(UNKNOWN_TRANSFER, () -> service.status(UUID.randomUUID()));
            assertError(UNKNOWN_TRANSFER, () -> service.summary(UUID.randomUUID()));
            assertError(UNKNOWN_TRANSFER, () -> service.status(null));
            assertError(UNKNOWN_TRANSFER, () -> service.summary(null));
            UUID id = service.start(request()).transferId();
            await(session.entered);
            assertError(SUMMARY_NOT_READY, () -> service.summary(id));
        }
    }

    @Test
    void failedRunReleasesSlotAndItsSummarySurvivesTheNextRun() throws Exception {
        ExecutorService worker = worker();
        AtomicInteger runs = new AtomicInteger();
        try (RealTransferService service = new RealTransferService(worker, CLOCK, request ->
                immediate(runs.getAndIncrement() == 0 ? TransferResult.failure("first run failed")
                        : TransferResult.success(-1)))) {
            TransferRequest first = request();
            service.start(first);
            drain(worker);
            TransferSummary failed = service.summary(first.transferId());
            assertError(INVALID_PARAMETER, () -> service.start(first));

            UUID next = service.start(request()).transferId();
            drain(worker);
            assertEquals(TransferState.COMPLETED, service.status(next).state());
            assertSame(failed, service.summary(first.transferId()));
            assertEquals(TransferState.FAILED, failed.finalSnapshot().state());
            assertEquals(2, runs.get());
        }
    }

    @Test
    void rejectedWorkerSubmissionLeavesAnExplicitFailureAndNoBusySlot() {
        ExecutorService worker = worker();
        worker.shutdown();
        try (RealTransferService service = new RealTransferService(worker, CLOCK,
                request -> immediate(TransferResult.success(-1)))) {
            TransferRequest first = request();
            assertError(TRANSFER_FAILED, () -> service.start(first));
            assertEquals(TransferState.FAILED, service.status(first.transferId()).state());
            assertEquals(IntegrityStatus.UNCONFIRMED, service.summary(first.transferId()).integrity());
            assertError(TRANSFER_FAILED, () -> service.start(request()));
        }
    }

    @Test
    void closeReleasesBlockedSendAndLateSuccessCannotOverwriteInterruption() throws Exception {
        ExecutorService worker = worker();
        GateSession session = new GateSession(TransferResult.success(-1));
        try (RealTransferService service = new RealTransferService(worker, CLOCK, request -> session)) {
            UUID id = service.start(request()).transferId();
            await(session.entered);

            assertTimeoutPreemptively(Duration.ofSeconds(1), service::close);
            TransferSummary interrupted = service.summary(id);
            assertEquals(TransferState.FAILED, interrupted.finalSnapshot().state());
            assertEquals(IntegrityStatus.UNCONFIRMED, interrupted.integrity());
            assertTrue(interrupted.message().contains("interrupted"));
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
            assertSame(interrupted, service.summary(id));
            assertEquals(1, session.sendCalls.get());
            assertEquals(1, session.closeCalls.get());
            service.close();
            assertEquals(1, session.closeCalls.get());
            assertError(SERVICE_CLOSED, () -> service.start(request()));
        }
    }

    @Test
    void closeDuringSessionCreationClosesTheNewSessionWithoutSending() throws Exception {
        ExecutorService worker = worker();
        CountDownLatch opening = new CountDownLatch(1);
        CountDownLatch allowOpen = new CountDownLatch(1);
        GateSession session = new GateSession(TransferResult.success(-1));
        try (RealTransferService service = new RealTransferService(worker, CLOCK, request -> {
            opening.countDown();
            awaitEvenWhenInterrupted(allowOpen);
            return session;
        })) {
            UUID id = service.start(request()).transferId();
            await(opening);
            service.close();
            TransferSummary interrupted = service.summary(id);
            allowOpen.countDown();

            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
            assertSame(interrupted, service.summary(id));
            assertEquals(TransferState.FAILED, interrupted.finalSnapshot().state());
            assertEquals(0, session.sendCalls.get());
            assertEquals(1, session.closeCalls.get());
        } finally {
            allowOpen.countDown();
        }
    }

    @Test
    void closeBeforeQueuedWorkerStartsPreventsSessionCreation() throws Exception {
        ExecutorService worker = worker();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        worker.submit(() -> {
            blocked.countDown();
            awaitEvenWhenInterrupted(release);
        });
        await(blocked);
        AtomicInteger opened = new AtomicInteger();
        try (RealTransferService service = new RealTransferService(worker, CLOCK, request -> {
            opened.incrementAndGet();
            return immediate(TransferResult.success(-1));
        })) {
            UUID id = service.start(request()).transferId();
            service.close();
            release.countDown();
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(0, opened.get());
            assertEquals(TransferState.FAILED, service.status(id).state());
        } finally {
            release.countDown();
        }
    }

    @Test
    void idleCloseIsIdempotentAndDoesNotRewriteCompletedEvidence() throws Exception {
        ExecutorService worker = worker();
        RealTransferService service = new RealTransferService(worker, CLOCK,
                request -> immediate(TransferResult.success(-1)));
        try (service) {
            UUID id = service.start(request()).transferId();
            drain(worker);
            TransferSummary complete = service.summary(id);
            service.close();
            service.close();
            assertSame(complete, service.summary(id));
            assertEquals(TransferState.COMPLETED, service.status(id).state());
            assertError(SERVICE_CLOSED, () -> service.start(request()));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void initialControlWaitMustBeFiniteAndPositive(int timeout) {
        assertThrows(IllegalArgumentException.class, () -> new RealTransferService(timeout));
    }

    private TransferRequest request() {
        return new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), "report", "receiver-a", source,
                new InetSocketAddress("127.0.0.1", 9000), new TransferSettings(1024, 1024, 1, 200, 5));
    }

    private ExecutorService worker() {
        return worker(1);
    }

    private ExecutorService worker(int threads) {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        executors.add(executor);
        return executor;
    }

    private static RealTransferService.SenderSession immediate(TransferResult result) {
        return new RealTransferService.SenderSession() {
            @Override
            public TransferResult send() {
                return result;
            }

            @Override
            public void close() { }
        };
    }

    private static void drain(ExecutorService worker) throws Exception {
        worker.submit(() -> { }).get(2, TimeUnit.SECONDS);
    }

    private static void assertError(TransferError.Code code, Runnable action) {
        assertEquals(code, assertThrows(TransferServiceException.class, action::run).error().code());
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Expected test synchronization signal");
    }

    /** Simulates a sender/factory that can return a late result after shutdown interrupts it. */
    private static void awaitEvenWhenInterrupted(CountDownLatch latch) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        try {
            while (latch.getCount() != 0) {
                long remaining = deadline - System.nanoTime();
                assertTrue(remaining > 0, "Test gate was not released");
                try {
                    assertTrue(latch.await(remaining, TimeUnit.NANOSECONDS), "Test gate was not released");
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class GateSession implements RealTransferService.SenderSession {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger sendCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final TransferResult result;

        private GateSession(TransferResult result) {
            this.result = result;
        }

        @Override
        public TransferResult send() {
            sendCalls.incrementAndGet();
            entered.countDown();
            awaitEvenWhenInterrupted(release);
            return result;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeCalls.incrementAndGet();
                release.countDown();
            }
        }
    }
}
