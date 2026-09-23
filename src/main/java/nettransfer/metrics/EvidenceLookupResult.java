package nettransfer.metrics;

import java.util.Objects;

/** Typed outcome of a persisted-evidence lookup. Only AVAILABLE carries evidence. */
public record EvidenceLookupResult(
        Status status,
        String reasonCode,
        String reason,
        Evidence evidence) {

    public EvidenceLookupResult {
        Objects.requireNonNull(status, "status is required");
        if (status == Status.AVAILABLE) {
            Objects.requireNonNull(evidence, "available lookup requires evidence");
        } else {
            if (evidence != null) {
                throw new IllegalArgumentException("non-available lookup cannot expose evidence");
            }
            if (reasonCode == null || reasonCode.isBlank() || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("non-available lookup requires a safe reason");
            }
        }
    }

    public boolean isAvailable() {
        return status == Status.AVAILABLE;
    }

    public enum Status {
        AVAILABLE,
        PENDING,
        INCOMPLETE,
        UNAVAILABLE,
        REJECTED
    }

    /** Validated endpoint evidence and, when present, its validated reconciled summary. */
    public record Evidence(
            MetricsExporter.ValidatedEndpointEvidence sender,
            MetricsExporter.ValidatedEndpointEvidence receiver,
            MetricsExporter.ExportResult reconciled) {
        public Evidence {
            if (sender == null && receiver == null && reconciled == null) {
                throw new IllegalArgumentException("available evidence cannot be empty");
            }
        }

        public boolean isReconciled() {
            return reconciled != null;
        }

        public boolean isSenderOnly() {
            return sender != null && receiver == null && reconciled == null;
        }
    }
}
