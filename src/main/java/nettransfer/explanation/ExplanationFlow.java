package nettransfer.explanation;

import nettransfer.control.EvidenceSource;
import nettransfer.control.TransferSummary;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Loads and checks evidence before analysis; has no reference to transfer execution or command tools. */
public final class ExplanationFlow {
    private final SummaryProvider summaries;
    private final ExplanationClient client;

    public ExplanationFlow(SummaryProvider summaries, ExplanationClient client) {
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.client = Objects.requireNonNull(client, "client");
    }

    /** Production default while producer records and measured evidence integration are pending. */
    public static ExplanationFlow unavailable() {
        return new ExplanationFlow(SummaryProvider.unavailable(), request -> {
            throw new IllegalStateException("No explanation client configured");
        });
    }

    public enum Status {
        EXPLAINED, EVIDENCE_UNAVAILABLE, EVIDENCE_REJECTED,
        EXPLANATION_UNAVAILABLE, EXPLANATION_REJECTED
    }

    /** Rejected evidence is never exposed as a valid source; valid measurements survive client failure. */
    public record Result(Status status, String message, RecordedSummary evidence, ExplanationDraft draft) { }

    public Result explain(TransferSummary selected, String question) {
        Objects.requireNonNull(selected, "selected");
        var snapshot = selected.finalSnapshot();
        // The complete revised field set is accepted, but producer records/serialization and verified
        // application/wire identity mapping are not integrated. Also block fixtures with matching real IDs.
        // Shared producer/engine integration must deliberately replace this gate.
        if (snapshot.evidenceSource() == EvidenceSource.REAL) {
            return result(Status.EVIDENCE_UNAVAILABLE,
                    "Real recorded measurements are unavailable. Person 2 accepted the complete revised field set;"
                            + " producer records/serialization and shared engine observation/identity integration are pending.", null);
        }
        RecordedSummary evidence;
        try {
            evidence = summaries.load(snapshot.runId()).orElse(null);
        } catch (RuntimeException exception) {
            return result(Status.EVIDENCE_UNAVAILABLE, "The summary provider could not supply evidence.", null);
        }
        if (evidence == null) {
            return result(Status.EVIDENCE_UNAVAILABLE, "No recorded summary exists for the selected run.", null);
        }
        if (!snapshot.runId().equals(evidence.runId())
                || !snapshot.transferId().equals(evidence.transferId())
                || !Objects.equals(snapshot.protocolTransferId(), evidence.protocolTransferId())
                || snapshot.evidenceSource() != evidence.source()
                || !RecordedSummary.FIXTURE_DEFINITION_VERSION.equals(evidence.definitionVersion())) {
            return result(Status.EVIDENCE_REJECTED,
                    "Summary identity, provenance or definition version does not match the supported selection.", null);
        }
        ExplanationDraft draft;
        try {
            draft = client.explain(new ExplanationRequest(UUID.randomUUID(), question, evidence,
                    snapshot.state(), selected.integrity()));
        } catch (RuntimeException exception) {
            return result(Status.EXPLANATION_UNAVAILABLE,
                    "Analysis failed or is not configured. Original evidence remains available.", evidence);
        }
        if (!valid(draft, evidence)) {
            return result(Status.EXPLANATION_REJECTED,
                    "Analysis returned invalid identity or evidence references. Original evidence remains available.", evidence);
        }
        return new Result(Status.EXPLAINED,
                "Analysis references checked against supplied evidence. Prose still requires review.", evidence, draft);
    }

    private static boolean valid(ExplanationDraft draft, RecordedSummary evidence) {
        if (draft == null || !draft.runId().equals(evidence.runId())
                || !draft.transferId().equals(evidence.transferId())) {
            return false;
        }
        var fields = evidence.fields().stream().collect(Collectors.toMap(RecordedSummary.Field::id, Function.identity()));
        for (var observation : draft.observations()) {
            for (var reference : observation.references()) {
                var field = fields.get(reference.fieldId());
                if (field == null || field.value() == null || field.value().compareTo(reference.value()) != 0
                        || !field.unit().equals(reference.unit())) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Result result(Status status, String message, RecordedSummary evidence) {
        return new Result(status, message, evidence, null);
    }
}
