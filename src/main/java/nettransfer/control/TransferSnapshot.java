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
        if ((state == TransferState.FAILED) != (error != null)) {
            throw new IllegalArgumentException("Only FAILED snapshots require an error");
        }
    }
}
