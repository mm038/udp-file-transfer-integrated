package nettransfer.control;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable evidence as of snapshotAt (a UTC wall-clock instant). */
public record TransferSnapshot(UUID transferId, UUID runId, UUID protocolTransferId,
                               TransferState state, Instant snapshotAt,
                               EvidenceSource evidenceSource, TransferMetrics metrics,
                               TransferError error) {
    public static final String SCHEMA_VERSION = "person-3-draft-1";

    public TransferSnapshot {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(snapshotAt, "snapshotAt");
        Objects.requireNonNull(evidenceSource, "evidenceSource");
        Objects.requireNonNull(metrics, "metrics");
        if (evidenceSource == EvidenceSource.REAL
                && !snapshotAt.equals(metrics.liveSnapshot().capturedAt())) {
            throw new IllegalArgumentException("Control and engine snapshot timestamps must match");
        }
        UUID observedProtocolId = metrics.liveSnapshot().context().getProtocolTransferId();
        if (evidenceSource == EvidenceSource.REAL
                && !Objects.equals(protocolTransferId, observedProtocolId)) {
            throw new IllegalArgumentException("Control and engine protocol transfer IDs must match");
        }
        var expectedSource = evidenceSource == EvidenceSource.REAL
                ? nettransfer.metrics.TransferMetrics.EvidenceSource.REAL
                : nettransfer.metrics.TransferMetrics.EvidenceSource.SYNTHETIC;
        if (metrics.authoritativeMetrics().getEvidenceSource() != expectedSource) {
            throw new IllegalArgumentException("Control and engine evidence provenance must match");
        }
        if ((state == TransferState.FAILED) != (error != null)) {
            throw new IllegalArgumentException("Only FAILED snapshots require an error");
        }
    }

    /** Complete immutable endpoint-local engine evidence behind this control snapshot. */
    public nettransfer.metrics.LiveMetricsSnapshot liveMetrics() {
        return metrics.liveSnapshot();
    }
}
