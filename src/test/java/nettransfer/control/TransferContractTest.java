package nettransfer.control;

import nettransfer.control.simulation.FakeTransferService;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransferContractTest {
    @Test
    void controlMetricsAreAViewOfTheAuthoritativeMetricsSnapshot() {
        TransferMetrics metrics = TransferMetrics.synthetic(1024L, 0L, 0L);

        assertSame(metrics.liveSnapshot().metrics(), metrics.authoritativeMetrics());
        assertEquals(1024L, metrics.fileSizeBytes());
        assertEquals(0L, metrics.uniquePayloadBytesAcked());
        assertEquals(0L, metrics.elapsedMillis());
        assertNull(metrics.totalChunks(), "No separate control-layer chunk measurement is maintained");
    }

    @Test
    void completionCannotBeSummarizedWithUnconfirmedOrFailedIntegrity() {
        TransferRequest request = request();
        TransferSnapshot completed = snapshot(request.transferId(), TransferState.COMPLETED);

        for (IntegrityStatus integrity : List.of(IntegrityStatus.UNCONFIRMED, IntegrityStatus.FAILED)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new TransferSummary(request, completed, integrity, "Not verified"));
            assertThrows(IllegalArgumentException.class,
                    () -> new FakeTransferService.Scenario(List.of(metrics()), metrics(),
                            integrity, null, "Not verified"));
        }
    }

    @Test
    void summaryRejectsRunningEvidenceAndEvidenceFromAnotherTransfer() {
        TransferRequest request = request();

        assertThrows(IllegalArgumentException.class,
                () -> new TransferSummary(request, snapshot(request.transferId(), TransferState.RUNNING),
                        IntegrityStatus.UNCONFIRMED, "Still running"));
        assertThrows(IllegalArgumentException.class,
                () -> new TransferSummary(request, snapshot(UUID.randomUUID(), TransferState.COMPLETED),
                        IntegrityStatus.VERIFIED, "Wrong transfer"));
    }

    @Test
    void unavailableMeasurementsRequireAnExplanationAndSyntheticProgressMustBeConsistent() {
        assertThrows(IllegalArgumentException.class, () -> TransferMetrics.unavailable(null));
        assertThrows(IllegalArgumentException.class, () -> TransferMetrics.unavailable(" "));
        assertThrows(IllegalArgumentException.class, () -> TransferMetrics.synthetic(-1L, 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> TransferMetrics.synthetic(1024L, 2048L, 10L));
    }

    private static TransferMetrics metrics() {
        return TransferMetrics.unavailable("Synthetic fixture has no observations");
    }

    private static TransferSnapshot snapshot(UUID id, TransferState state) {
        Instant capturedAt = Instant.parse("2026-09-20T08:00:00Z");
        var lifecycle = switch (state) {
            case RUNNING -> nettransfer.metrics.LiveMetricsSnapshot.LifecycleState.TRANSFERRING;
            case COMPLETED -> nettransfer.metrics.LiveMetricsSnapshot.LifecycleState.SUCCEEDED;
            case FAILED -> nettransfer.metrics.LiveMetricsSnapshot.LifecycleState.FAILED;
        };
        TransferMetrics metrics = metrics().asSyntheticSnapshot(capturedAt, lifecycle,
                state == TransferState.RUNNING ? null : state == TransferState.COMPLETED,
                state == TransferState.FAILED ? "Synthetic failure" : null);
        TransferError error = state == TransferState.FAILED
                ? new TransferError(TransferError.Code.TRANSFER_FAILED, "Synthetic failure") : null;
        return new TransferSnapshot(id, id, null, state, Instant.parse("2026-09-20T08:00:00Z"),
                EvidenceSource.SYNTHETIC, metrics, error);
    }

    private static TransferRequest request() {
        return new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), "sample-file", "receiver-a",
                Path.of("synthetic-input", "not-created.bin"),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 9000),
                new TransferSettings(1024, 1024, 1, 200, 5));
    }
}
