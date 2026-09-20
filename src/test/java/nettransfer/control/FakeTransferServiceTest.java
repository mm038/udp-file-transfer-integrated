package nettransfer.control;

import nettransfer.control.simulation.FakeTransferService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static nettransfer.control.TransferError.Code.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(5)
class FakeTransferServiceTest {
    private static final Instant START = Instant.parse("2026-09-20T08:00:00Z");
    private static final TransferMetrics INITIAL = new TransferMetrics(2048L, 0L, 0L, 2, null);
    private static final TransferMetrics PARTIAL = new TransferMetrics(2048L, 1024L, 50L, 2, null);
    private static final TransferMetrics COMPLETE = new TransferMetrics(2048L, 2048L, 100L, 2, null);

    @Test
    void startReturnsAcceptanceThroughTheInterfaceWhileTransferRemainsRunning() {
        TransferService service = successfulService();
        TransferRequest request = request();

        TransferStart accepted = service.start(request);

        assertEquals(request.transferId(), accepted.transferId());
        assertEquals(accepted.transferId(), accepted.runId());
        assertNull(accepted.protocolTransferId());
        assertEquals(START, accepted.acceptedAt());
        assertEquals(EvidenceSource.SYNTHETIC, accepted.evidenceSource());
        assertEquals(TransferState.RUNNING, service.status(accepted.transferId()).state());
        assertError(SUMMARY_NOT_READY, () -> service.summary(accepted.runId()));
    }

    @Test
    void onlyExplicitAdvancementChangesEvidenceAndTimestamps() {
        MutableClock clock = new MutableClock(START);
        FakeTransferService service = new FakeTransferService(
                FakeTransferService.Scenario.success(List.of(INITIAL, PARTIAL), COMPLETE), clock);
        UUID id = service.start(request()).transferId();
        TransferSnapshot first = service.status(id);

        clock.set(START.plusSeconds(1));
        assertEquals(first, service.status(id), "Reading status must not invent fresh progress or evidence");
        TransferSnapshot second = service.advance(id);

        assertEquals(PARTIAL, second.metrics());
        assertEquals(START.plusSeconds(1), second.snapshotAt());
        assertEquals(INITIAL, first.metrics(), "A previously returned snapshot must stay frozen");
        assertEquals(START, first.snapshotAt());
        assertEquals(TransferState.RUNNING, first.state());
        assertEquals(TransferState.RUNNING, second.state());
        assertNull(second.protocolTransferId());
        assertEquals(EvidenceSource.SYNTHETIC, second.evidenceSource());
        assertError(SUMMARY_NOT_READY, () -> service.summary(id));

        clock.set(START.plusSeconds(2));
        TransferSnapshot terminal = service.advance(id);
        assertEquals(TransferState.COMPLETED, terminal.state());
        assertEquals(START.plusSeconds(2), terminal.snapshotAt());
        assertEquals(COMPLETE, terminal.metrics());
        assertEquals(100L, terminal.metrics().elapsedMillis(),
                "Protocol duration is supplied evidence, not a subtraction of wall-clock timestamps");
    }

    @Test
    void verifiedSummarySurvivesLaterStartsAndTerminalAdvancement() {
        FakeTransferService service = successfulService();
        TransferRequest firstRequest = request();
        UUID firstId = service.start(firstRequest).transferId();
        service.advance(firstId);
        TransferSnapshot terminal = service.advance(firstId);
        TransferSummary summary = service.summary(firstId);

        assertEquals(firstRequest, summary.request());
        assertEquals(terminal, summary.finalSnapshot());
        assertEquals(IntegrityStatus.VERIFIED, summary.integrity());
        assertNull(terminal.error());
        assertNull(terminal.protocolTransferId());
        assertEquals(EvidenceSource.SYNTHETIC, terminal.evidenceSource());

        UUID nextId = service.start(request()).transferId();
        assertNotEquals(firstId, nextId);
        assertEquals(TransferState.RUNNING, service.status(nextId).state());
        assertEquals(terminal, service.advance(firstId));
        assertEquals(summary, service.summary(firstId));
        assertError(TRANSFER_BUSY, () -> service.start(request()));
    }

    @Test
    void engineFailureRetainsPartialEvidenceAndAllowsAnotherRun() {
        FakeTransferService service = new FakeTransferService(
                FakeTransferService.Scenario.engineFailure(List.of(INITIAL), PARTIAL));
        UUID id = service.start(request()).transferId();

        TransferSnapshot failed = service.advance(id);
        TransferSummary summary = service.summary(id);

        assertEquals(TransferState.FAILED, failed.state());
        assertEquals(TRANSFER_FAILED, failed.error().code());
        assertEquals(PARTIAL, failed.metrics());
        assertEquals(IntegrityStatus.UNCONFIRMED, summary.integrity());
        UUID next = service.start(request()).transferId();
        assertEquals(TransferState.RUNNING, service.status(next).state());
        assertEquals(summary, service.summary(id));
    }

    @Test
    void allBytesAcknowledgedDoesNotMakeAnIntegrityFailureSuccessful() {
        FakeTransferService service = new FakeTransferService(
                FakeTransferService.Scenario.integrityFailure(List.of(INITIAL), COMPLETE));
        UUID id = service.start(request()).transferId();

        TransferSnapshot failed = service.advance(id);

        assertEquals(TransferState.FAILED, failed.state());
        assertEquals(INTEGRITY_FAILED, failed.error().code());
        assertEquals(COMPLETE, failed.metrics());
        assertEquals(IntegrityStatus.FAILED, service.summary(id).integrity());
        assertEquals(EvidenceSource.SYNTHETIC, failed.evidenceSource());
    }

    @Test
    void unknownAndNullIdsReturnTypedErrorsBeforeAndDuringATransfer() {
        FakeTransferService service = successfulService();
        UUID unknown = UUID.randomUUID();
        assertError(UNKNOWN_TRANSFER, () -> service.status(unknown));
        assertError(UNKNOWN_TRANSFER, () -> service.summary(unknown));
        assertError(UNKNOWN_TRANSFER, () -> service.status(null));
        assertError(UNKNOWN_TRANSFER, () -> service.summary(null));
        UUID active = service.start(request()).transferId();
        assertError(UNKNOWN_TRANSFER, () -> service.advance(unknown));
        assertError(UNKNOWN_TRANSFER, () -> service.status(unknown));
        assertError(UNKNOWN_TRANSFER, () -> service.summary(unknown));
        assertEquals(TransferState.RUNNING, service.status(active).state());
    }

    @Test
    void conflictingStartLeavesTheActiveRunUnchanged() {
        FakeTransferService service = successfulService();
        UUID id = service.start(request()).transferId();
        TransferSnapshot before = service.status(id);

        assertError(TRANSFER_BUSY, () -> service.start(request()));

        assertEquals(before, service.status(id));
        assertEquals(PARTIAL, service.advance(id).metrics());
    }

    @Test
    void simultaneousStartsReserveExactlyOneActiveSlot() throws Exception {
        FakeTransferService service = successfulService();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> attempts = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                attempts.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("Start gate did not open");
                    }
                    try {
                        return service.start(request());
                    } catch (TransferServiceException rejected) {
                        return rejected.error().code();
                    }
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            Object first = attempts.get(0).get(2, TimeUnit.SECONDS);
            Object second = attempts.get(1).get(2, TimeUnit.SECONDS);

            assertEquals(1, List.of(first, second).stream().filter(TransferStart.class::isInstance).count());
            assertEquals(1, List.of(first, second).stream().filter(TRANSFER_BUSY::equals).count());
            TransferStart accepted = first instanceof TransferStart value ? value : (TransferStart) second;
            assertEquals(TransferState.RUNNING, service.status(accepted.transferId()).state());
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void verifiedSyntheticOutcomeDoesNotFillUnavailableMeasurements() {
        TransferMetrics missing = TransferMetrics.unavailable("Synthetic fixture has no measurements");
        FakeTransferService service = new FakeTransferService(
                FakeTransferService.Scenario.success(List.of(missing), missing));
        UUID id = service.start(request()).transferId();
        assertEquals(missing, service.status(id).metrics());

        service.advance(id);
        TransferSummary summary = service.summary(id);

        assertEquals(IntegrityStatus.VERIFIED, summary.integrity());
        assertEquals(missing, summary.finalSnapshot().metrics());
        assertNull(summary.finalSnapshot().metrics().uniquePayloadBytesAcked());
        assertNull(summary.finalSnapshot().metrics().elapsedMillis());
        assertEquals(EvidenceSource.SYNTHETIC, summary.finalSnapshot().evidenceSource());
    }

    @Test
    void reusedTransferIdentityCannotOverwriteACompletedRun() {
        FakeTransferService service = successfulService();
        TransferRequest request = request();
        UUID id = service.start(request).transferId();
        service.advance(id);
        service.advance(id);
        TransferSummary original = service.summary(id);

        TransferRequest reused = new TransferRequest(UUID.randomUUID(), id, request.fileId(),
                request.receiverId(), request.sourcePath(), request.receiver(), request.settings());
        assertError(INVALID_PARAMETER, () -> service.start(reused));

        assertEquals(original, service.summary(id));
        assertEquals(TransferState.RUNNING, service.status(service.start(request()).transferId()).state());
    }

    @Test
    void mutatingTheOriginalScenarioListCannotChangeLaterProgress() {
        List<TransferMetrics> suppliedProgress = new ArrayList<>(List.of(INITIAL, PARTIAL));
        FakeTransferService service = new FakeTransferService(
                FakeTransferService.Scenario.success(suppliedProgress, COMPLETE));
        UUID id = service.start(request()).transferId();

        suppliedProgress.clear();

        assertEquals(PARTIAL, service.advance(id).metrics());
        assertEquals(TransferState.COMPLETED, service.advance(id).state());
    }

    private static FakeTransferService successfulService() {
        return new FakeTransferService(
                FakeTransferService.Scenario.success(List.of(INITIAL, PARTIAL), COMPLETE),
                Clock.fixed(START, ZoneOffset.UTC));
    }

    private static TransferRequest request() {
        // Trusted synthetic inputs: no file is opened and no DNS lookup is needed.
        return new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), "sample-file", "receiver-a",
                Path.of("synthetic-input", "not-created.bin"),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 9000),
                new TransferSettings(1024, 1024, 1, 200, 5));
    }

    private static void assertError(TransferError.Code code, org.junit.jupiter.api.function.Executable call) {
        assertEquals(code, assertThrows(TransferServiceException.class, call).error().code());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
