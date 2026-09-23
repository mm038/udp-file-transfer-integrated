package nettransfer.explanation;

import nettransfer.control.EvidenceSource;
import nettransfer.control.IntegrityStatus;
import nettransfer.control.TransferState;
import nettransfer.control.TransferSummary;
import nettransfer.metrics.MetricsSchema;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
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

    /** Safe production default until application wiring supplies a trusted REAL evidence provider. */
    public static ExplanationFlow unavailable() {
        return new ExplanationFlow(SummaryProvider.unavailable(), request -> {
            throw new IllegalStateException("No explanation client configured");
        });
    }

    public enum Status {
        EXPLAINED, EVIDENCE_PENDING, EVIDENCE_INCOMPLETE,
        EVIDENCE_UNAVAILABLE, EVIDENCE_REJECTED,
        EXPLANATION_UNAVAILABLE, EXPLANATION_REJECTED
    }

    /** Rejected evidence is never exposed as a valid source; valid measurements survive client failure. */
    public record Result(Status status, String message, RecordedSummary evidence, ExplanationDraft draft) { }

    public Result explain(TransferSummary selected, String question) {
        Objects.requireNonNull(selected, "selected");
        var snapshot = selected.finalSnapshot();
        EvidenceLoad loaded = snapshot.evidenceSource() == EvidenceSource.REAL
                ? loadReal(selected) : loadSynthetic(selected);
        if (loaded.error() != null) {
            return loaded.error();
        }
        RecordedSummary evidence = loaded.evidence();
        if (!validEvidence(selected, evidence)) {
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

    private EvidenceLoad loadReal(TransferSummary selected) {
        var snapshot = selected.finalSnapshot();
        if (snapshot.protocolTransferId() == null) {
            return EvidenceLoad.error(result(Status.EVIDENCE_REJECTED,
                    "The REAL selection has no trusted protocol transfer UUID.", null));
        }
        SummaryProvider.LookupResult lookup;
        try {
            lookup = summaries.lookup(new SummaryProvider.Selection(
                    snapshot.runId(), snapshot.transferId(), snapshot.runId().toString(),
                    snapshot.protocolTransferId()));
        } catch (RuntimeException exception) {
            return EvidenceLoad.error(result(Status.EVIDENCE_UNAVAILABLE,
                    "The summary provider could not supply evidence.", null));
        }
        if (lookup == null) {
            return EvidenceLoad.error(result(Status.EVIDENCE_UNAVAILABLE,
                    "The summary provider returned no evidence result.", null));
        }
        if (lookup.status() != SummaryProvider.Status.AVAILABLE) {
            Status status = switch (lookup.status()) {
                case PENDING -> Status.EVIDENCE_PENDING;
                case INCOMPLETE -> Status.EVIDENCE_INCOMPLETE;
                case UNAVAILABLE -> Status.EVIDENCE_UNAVAILABLE;
                case REJECTED -> Status.EVIDENCE_REJECTED;
                case AVAILABLE -> throw new IllegalStateException("available lookup expected evidence");
            };
            return EvidenceLoad.error(
                    result(status, lookup.reasonCode() + ": " + lookup.reason(), null));
        }
        if (lookup.summary() == null) {
            return EvidenceLoad.error(result(Status.EVIDENCE_REJECTED,
                    "The provider marked REAL evidence available without a summary.", null));
        }
        return EvidenceLoad.available(lookup.summary());
    }

    private EvidenceLoad loadSynthetic(TransferSummary selected) {
        RecordedSummary evidence;
        try {
            evidence = summaries.load(selected.finalSnapshot().runId()).orElse(null);
        } catch (RuntimeException exception) {
            return EvidenceLoad.error(result(Status.EVIDENCE_UNAVAILABLE,
                    "The summary provider could not supply evidence.", null));
        }
        if (evidence == null) {
            return EvidenceLoad.error(result(Status.EVIDENCE_UNAVAILABLE,
                    "No recorded summary exists for the selected run.", null));
        }
        return EvidenceLoad.available(evidence);
    }

    private static boolean validEvidence(TransferSummary selected, RecordedSummary evidence) {
        var snapshot = selected.finalSnapshot();
        if (!snapshot.runId().equals(evidence.runId())
                || !snapshot.transferId().equals(evidence.transferId())
                || !Objects.equals(snapshot.protocolTransferId(), evidence.protocolTransferId())
                || snapshot.evidenceSource() != evidence.source()) {
            return false;
        }
        return snapshot.evidenceSource() == EvidenceSource.REAL
                ? validReal(selected, evidence) : validSynthetic(evidence);
    }

    private static boolean validSynthetic(RecordedSummary evidence) {
        return evidence.metadata().scope() == RecordedSummary.EvidenceScope.SYNTHETIC_FIXTURE
                && RecordedSummary.FIXTURE_DEFINITION_VERSION.equals(evidence.definitionVersion())
                && RecordedSummary.FIXTURE_DEFINITION_VERSION.equals(
                evidence.metadata().metricDefinitionVersion());
    }

    private static boolean validReal(TransferSummary selected, RecordedSummary evidence) {
        var snapshot = selected.finalSnapshot();
        var metadata = evidence.metadata();
        if (evidence.source() != EvidenceSource.REAL
                || RecordedSummary.FIXTURE_DEFINITION_VERSION.equals(evidence.definitionVersion())
                || !MetricsSchema.METRIC_DEFINITION_VERSION.equals(evidence.definitionVersion())
                || !MetricsSchema.METRIC_DEFINITION_VERSION.equals(metadata.metricDefinitionVersion())
                || metadata.completeness() != RecordedSummary.EvidenceCompleteness.COMPLETE
                || metadata.finalizationStatus() != RecordedSummary.FinalizationStatus.FINAL
                || metadata.scope() == RecordedSummary.EvidenceScope.SYNTHETIC_FIXTURE
                || !snapshot.transferId().toString().equals(metadata.applicationTransferId())
                || !snapshot.runId().toString().equals(metadata.senderRunId())
                || !snapshot.protocolTransferId().equals(metadata.protocolTransferId())
                || metadata.sourceReferences().isEmpty()
                || !supportedSchema(metadata)
                || !safeReferences(metadata)) {
            return false;
        }
        if (metadata.scope() == RecordedSummary.EvidenceScope.RECONCILED
                && (metadata.receiverRunId() == null || metadata.receiverRunId().isBlank())) {
            return false;
        }
        if (snapshot.state() == TransferState.COMPLETED
                && (!"SUCCESS".equals(metadata.senderTerminalOutcome())
                || metadata.scope() != RecordedSummary.EvidenceScope.RECONCILED
                || !Boolean.TRUE.equals(metadata.receiverIntegrityVerified()))) {
            return false;
        }
        if (snapshot.state() == TransferState.FAILED
                && metadata.scope() != RecordedSummary.EvidenceScope.RECEIVER_FINAL
                && !"FAILED".equals(metadata.senderTerminalOutcome())) {
            return false;
        }
        return selected.integrity() != IntegrityStatus.FAILED
                || metadata.receiverIntegrityVerified() == null
                || Boolean.FALSE.equals(metadata.receiverIntegrityVerified());
    }

    private static boolean supportedSchema(RecordedSummary.EvidenceMetadata metadata) {
        return switch (metadata.scope()) {
            case RECONCILED -> MetricsSchema.SUMMARY_SCHEMA_VERSION.equals(
                    metadata.metricsSchemaVersion());
            case SENDER_FINAL, RECEIVER_FINAL -> MetricsSchema.ENDPOINT_RECORD_SCHEMA_VERSION.equals(
                    metadata.metricsSchemaVersion());
            case SYNTHETIC_FIXTURE -> false;
        };
    }

    private static boolean safeReferences(RecordedSummary.EvidenceMetadata metadata) {
        try {
            return metadata.sourceReferences().stream().allMatch(reference -> {
                Path path = Path.of(reference);
                if (path.isAbsolute()) {
                    return false;
                }
                for (Path component : path) {
                    if ("..".equals(component.toString())) {
                        return false;
                    }
                }
                return true;
            });
        } catch (InvalidPathException exception) {
            return false;
        }
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

    private record EvidenceLoad(RecordedSummary evidence, Result error) {
        private static EvidenceLoad available(RecordedSummary evidence) {
            return new EvidenceLoad(Objects.requireNonNull(evidence), null);
        }

        private static EvidenceLoad error(Result error) {
            return new EvidenceLoad(null, Objects.requireNonNull(error));
        }
    }
}
