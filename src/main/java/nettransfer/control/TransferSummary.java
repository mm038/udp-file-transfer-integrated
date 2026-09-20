package nettransfer.control;

import java.util.Objects;

/**
 * Frozen terminal outcome with its request/settings and final evidence snapshot.
 * This is an in-memory result, not Person 2's future persisted experiment summary.
 */
public record TransferSummary(TransferRequest request, TransferSnapshot finalSnapshot,
                              IntegrityStatus integrity, String message) {
    public TransferSummary {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(finalSnapshot, "finalSnapshot");
        Objects.requireNonNull(integrity, "integrity");
        Objects.requireNonNull(message, "message");
        if (!request.transferId().equals(finalSnapshot.transferId())) {
            throw new IllegalArgumentException("Summary request and snapshot must identify the same transfer");
        }
        if (finalSnapshot.state() == TransferState.RUNNING) {
            throw new IllegalArgumentException("A summary requires a terminal outcome");
        }
        if (finalSnapshot.state() == TransferState.COMPLETED && integrity != IntegrityStatus.VERIFIED) {
            throw new IllegalArgumentException("Completion requires confirmed integrity");
        }
    }
}
