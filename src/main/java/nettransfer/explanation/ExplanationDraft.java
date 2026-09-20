package nettransfer.explanation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Untrusted analysis output. Numeric citations are checked against Java's original evidence. */
public record ExplanationDraft(UUID runId, UUID transferId, List<Observation> observations,
                               List<String> hypotheses, List<String> limitations) {
    public ExplanationDraft {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(transferId, "transferId");
        observations = List.copyOf(observations);
        hypotheses = List.copyOf(hypotheses);
        limitations = List.copyOf(limitations);
        if (observations.size() > 8 || hypotheses.size() > 4
                || limitations.isEmpty() || limitations.size() > 8) {
            throw new IllegalArgumentException("Analysis output exceeds bounds or omits limitations");
        }
        hypotheses.forEach(value -> RecordedSummary.text(value, 600));
        limitations.forEach(value -> RecordedSummary.text(value, 600));
    }

    public record Observation(String text, List<Reference> references) {
        public Observation {
            RecordedSummary.text(text, 600);
            references = List.copyOf(references);
            if (references.isEmpty() || references.size() > 8) {
                throw new IllegalArgumentException("Every observation needs 1-8 supplied evidence references");
            }
        }
    }

    public record Reference(String fieldId, BigDecimal value, String unit) {
        public Reference {
            RecordedSummary.text(fieldId, 64);
            RecordedSummary.number(Objects.requireNonNull(value, "value"));
            RecordedSummary.text(unit, 64);
        }
    }
}
