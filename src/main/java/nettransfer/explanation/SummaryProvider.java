package nettransfer.explanation;

import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

/** Read-only boundary for frozen explanation evidence. */
@FunctionalInterface
public interface SummaryProvider {
    Optional<RecordedSummary> load(UUID runId);

    /**
     * Typed lookup used by provenance-aware consumers. Legacy providers retain exact-run behavior
     * through {@link #load(UUID)}; real providers override this method to validate every identity.
     */
    default LookupResult lookup(Selection selection) {
        Objects.requireNonNull(selection, "selection");
        RecordedSummary summary = load(selection.runId()).orElse(null);
        if (summary == null) {
            return LookupResult.unavailable("SUMMARY_NOT_FOUND",
                    "No recorded summary exists for the selected run");
        }
        if (!selection.runId().equals(summary.runId())
                || !selection.applicationTransferId().equals(summary.transferId())
                || !selection.protocolTransferId().equals(summary.protocolTransferId())) {
            return LookupResult.rejected("SUMMARY_IDENTITY_MISMATCH",
                    "Recorded summary identity does not match the trusted selection");
        }
        return LookupResult.available(summary);
    }

    static SummaryProvider unavailable() {
        return runId -> Optional.empty();
    }

    record Selection(UUID runId, UUID applicationTransferId,
                     String senderRunId, UUID protocolTransferId) {
        public Selection {
            Objects.requireNonNull(runId, "run ID is required");
            Objects.requireNonNull(applicationTransferId, "application transfer ID is required");
            if (senderRunId == null || senderRunId.isBlank()) {
                throw new IllegalArgumentException("sender run ID is required");
            }
            Objects.requireNonNull(protocolTransferId, "protocol transfer UUID is required");
        }

        public static Selection applicationTransfer(UUID id, UUID protocolTransferId) {
            return new Selection(id, id, id.toString(), protocolTransferId);
        }
    }

    record LookupResult(Status status, String reasonCode, String reason,
                        RecordedSummary summary) {
        public LookupResult {
            Objects.requireNonNull(status, "status is required");
            if (status == Status.AVAILABLE) {
                Objects.requireNonNull(summary, "available lookup requires a summary");
                if (reasonCode != null || reason != null) {
                    throw new IllegalArgumentException("available lookup cannot have a failure reason");
                }
            } else {
                if (summary != null) {
                    throw new IllegalArgumentException("non-available lookup cannot expose evidence");
                }
                RecordedSummary.text(reasonCode, 100);
                RecordedSummary.text(reason, 400);
            }
        }

        public static LookupResult available(RecordedSummary summary) {
            return new LookupResult(Status.AVAILABLE, null, null, summary);
        }

        public static LookupResult pending(String code, String reason) {
            return new LookupResult(Status.PENDING, code, reason, null);
        }

        public static LookupResult incomplete(String code, String reason) {
            return new LookupResult(Status.INCOMPLETE, code, reason, null);
        }

        public static LookupResult unavailable(String code, String reason) {
            return new LookupResult(Status.UNAVAILABLE, code, reason, null);
        }

        public static LookupResult rejected(String code, String reason) {
            return new LookupResult(Status.REJECTED, code, reason, null);
        }
    }

    enum Status {
        AVAILABLE,
        PENDING,
        INCOMPLETE,
        UNAVAILABLE,
        REJECTED
    }
}
