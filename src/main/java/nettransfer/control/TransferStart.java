package nettransfer.control;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Prompt acceptance of a run, not confirmation of file delivery. */
public record TransferStart(UUID transferId, UUID runId, UUID protocolTransferId,
                            Instant acceptedAt, EvidenceSource evidenceSource) {
    public TransferStart {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Objects.requireNonNull(evidenceSource, "evidenceSource");
        // protocolTransferId remains null until a verified engine mapping exists.
    }
}
