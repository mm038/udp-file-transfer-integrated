package nettransfer.explanation;

import nettransfer.control.EvidenceSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Person 3's bounded analysis input, NOT an agreed Person 2 metric or storage schema. */
public record RecordedSummary(UUID runId, UUID transferId, UUID protocolTransferId,
                              EvidenceSource source, Instant capturedAt, String definitionVersion,
                              String label, List<Field> fields, EvidenceMetadata metadata) {
    public static final String FIXTURE_DEFINITION_VERSION = "person-3-explanation-fixture-1";
    public static final int MAX_FIELDS = 32;

    public RecordedSummary {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(capturedAt, "capturedAt");
        text(definitionVersion, 80);
        text(label, 160);
        Objects.requireNonNull(metadata, "evidence metadata is required");
        if (!definitionVersion.equals(metadata.metricDefinitionVersion())) {
            throw new IllegalArgumentException("Summary and metadata definition versions must match");
        }
        if (source == EvidenceSource.SYNTHETIC
                && metadata.scope() != EvidenceScope.SYNTHETIC_FIXTURE) {
            throw new IllegalArgumentException("Synthetic evidence requires synthetic fixture scope");
        }
        if (source == EvidenceSource.REAL
                && metadata.scope() == EvidenceScope.SYNTHETIC_FIXTURE) {
            throw new IllegalArgumentException("Real evidence cannot use synthetic fixture scope");
        }
        if (!Objects.equals(protocolTransferId, metadata.protocolTransferId())) {
            throw new IllegalArgumentException("Summary and metadata protocol identities must match");
        }
        fields = List.copyOf(fields);
        if (fields.isEmpty() || fields.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("Supply 1-32 evidence fields");
        }
        var ids = new HashSet<String>();
        for (Field field : fields) {
            if (!ids.add(field.id())) {
                throw new IllegalArgumentException("Evidence field IDs must be unique");
            }
        }
    }

    /** Compatibility constructor for the existing explicit synthetic fixtures and boundary tests. */
    public RecordedSummary(UUID runId, UUID transferId, UUID protocolTransferId,
                           EvidenceSource source, Instant capturedAt, String definitionVersion,
                           String label, List<Field> fields) {
        this(runId, transferId, protocolTransferId, source, capturedAt, definitionVersion,
                label, fields, legacyMetadata(runId, transferId, protocolTransferId,
                        source, definitionVersion));
    }

    public enum EvidenceScope {
        SENDER_FINAL,
        RECEIVER_FINAL,
        RECONCILED,
        SYNTHETIC_FIXTURE
    }

    public enum EvidenceCompleteness { COMPLETE }

    public enum FinalizationStatus { FINAL }

    /** Typed provenance and identities that must not be flattened into numeric fields. */
    public record EvidenceMetadata(
            EvidenceScope scope,
            EvidenceCompleteness completeness,
            FinalizationStatus finalizationStatus,
            String applicationTransferId,
            String senderRunId,
            String receiverRunId,
            UUID protocolTransferId,
            String metricsSchemaVersion,
            String metricDefinitionVersion,
            String senderTerminalOutcome,
            Boolean receiverIntegrityVerified,
            String failureCategory,
            String failureReason,
            List<String> sourceReferences) {
        public EvidenceMetadata {
            Objects.requireNonNull(scope, "evidence scope is required");
            Objects.requireNonNull(completeness, "evidence completeness is required");
            Objects.requireNonNull(finalizationStatus, "finalization status is required");
            text(applicationTransferId, 160);
            if (scope != EvidenceScope.SYNTHETIC_FIXTURE) {
                Objects.requireNonNull(protocolTransferId, "protocol transfer UUID is required");
            }
            text(metricsSchemaVersion, 80);
            text(metricDefinitionVersion, 80);
            optionalText(senderRunId, 160);
            optionalText(receiverRunId, 160);
            optionalText(senderTerminalOutcome, 80);
            optionalText(failureCategory, 120);
            optionalText(failureReason, 1000);
            sourceReferences = List.copyOf(sourceReferences);
            for (String reference : sourceReferences) {
                text(reference, 400);
            }
            if (scope == EvidenceScope.RECONCILED
                    && (senderRunId == null || receiverRunId == null || sourceReferences.isEmpty())) {
                throw new IllegalArgumentException(
                        "Reconciled evidence requires both endpoint identities and source references");
            }
            if (scope == EvidenceScope.SENDER_FINAL && senderRunId == null) {
                throw new IllegalArgumentException("Sender-final evidence requires a sender run ID");
            }
        }
    }

    /** Configuration is never presented as an observed measurement. */
    public enum Kind { OBSERVED, CONFIGURED }

    /** Null is missing evidence; zero is a supplied value. Definitions and units travel with values. */
    public record Field(String id, BigDecimal value, String unit, Kind kind,
                        String definition, String unavailableReason) {
        public Field {
            if (id == null || !id.matches("[a-z][a-z0-9_]{0,63}")) {
                throw new IllegalArgumentException("Invalid field ID");
            }
            text(unit, 64);
            Objects.requireNonNull(kind, "kind");
            text(definition, 400);
            if (value == null) {
                text(unavailableReason, 400);
            } else {
                number(value);
                if (unavailableReason != null) {
                    throw new IllegalArgumentException("Available values cannot have a missing-evidence reason");
                }
            }
        }
    }

    static void number(BigDecimal value) {
        BigDecimal absolute = value.abs();
        if (value.signum() < 0 || value.precision() > 24
                || absolute.compareTo(new BigDecimal("1E+13")) >= 0
                || (value.signum() != 0 && absolute.compareTo(new BigDecimal("1E-12")) < 0)) {
            throw new IllegalArgumentException("Evidence numbers must be bounded and nonnegative");
        }
    }

    static void text(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
            throw new IllegalArgumentException("Text is missing, too long, or contains control characters");
        }
    }

    private static void optionalText(String value, int maxLength) {
        if (value != null) {
            text(value, maxLength);
        }
    }

    private static EvidenceMetadata legacyMetadata(
            UUID runId, UUID transferId, UUID protocolTransferId,
            EvidenceSource source, String definitionVersion) {
        EvidenceScope scope = source == EvidenceSource.SYNTHETIC
                ? EvidenceScope.SYNTHETIC_FIXTURE : EvidenceScope.SENDER_FINAL;
        return new EvidenceMetadata(scope, EvidenceCompleteness.COMPLETE,
                FinalizationStatus.FINAL, transferId.toString(),
                scope == EvidenceScope.SENDER_FINAL ? runId.toString() : null,
                null, protocolTransferId, definitionVersion, definitionVersion,
                null, null, null, null, List.of());
    }
}
