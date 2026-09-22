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
                              String label, List<Field> fields) {
    public static final String FIXTURE_DEFINITION_VERSION = "person-3-explanation-fixture-1";
    public static final int MAX_FIELDS = 32;

    public RecordedSummary {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(capturedAt, "capturedAt");
        text(definitionVersion, 80);
        text(label, 160);
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
        if (value.signum() < 0 || value.precision() > 24 || Math.abs((long) value.scale()) > 12) {
            throw new IllegalArgumentException("Evidence numbers must be bounded and nonnegative");
        }
    }

    static void text(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
            throw new IllegalArgumentException("Text is missing, too long, or contains control characters");
        }
    }
}
