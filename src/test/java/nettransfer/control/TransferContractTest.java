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
    void engineSentinelIsUnavailableWhileZeroRemainsAnObservedZero() {
        nettransfer.transfer.TransferResult success = nettransfer.transfer.TransferResult.success(-1);
        nettransfer.transfer.TransferResult failure = nettransfer.transfer.TransferResult.failure("Failed");

        assertNull(TransferMetrics.chunkCountFromEngine(success.getTotalChunks()));
        assertNull(TransferMetrics.chunkCountFromEngine(failure.getTotalChunks()));
        assertEquals(0, TransferMetrics.chunkCountFromEngine(0));
        assertEquals(5, TransferMetrics.chunkCountFromEngine(5));
        assertThrows(IllegalArgumentException.class, () -> TransferMetrics.chunkCountFromEngine(-2));
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
    void unavailableMeasurementsRequireAnExplanationAndCannotMasqueradeAsNegativeCounts() {
        assertThrows(IllegalArgumentException.class,
                () -> new TransferMetrics(1024L, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TransferMetrics(1024L, null, null, null, " "));
        assertThrows(IllegalArgumentException.class,
                () -> new TransferMetrics(1024L, 0L, 0L, -1, "Raw engine sentinel"));
        assertThrows(IllegalArgumentException.class,
                () -> new TransferMetrics(1024L, 2048L, 10L, 1, null));
    }

    private static TransferMetrics metrics() {
        return TransferMetrics.unavailable("Synthetic fixture has no observations");
    }

    private static TransferSnapshot snapshot(UUID id, TransferState state) {
        return new TransferSnapshot(id, id, null, state, Instant.parse("2026-09-20T08:00:00Z"),
                EvidenceSource.SYNTHETIC, metrics(), null);
    }

    private static TransferRequest request() {
        return new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), "sample-file", "receiver-a",
                Path.of("synthetic-input", "not-created.bin"),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 9000),
                new TransferSettings(1024, 1024, 1, 200, 5));
    }
}
