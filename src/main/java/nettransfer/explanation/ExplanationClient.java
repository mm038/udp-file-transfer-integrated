package nettransfer.explanation;

/** Analysis only: deliberately separate from GptClient command interpretation and dispatch. */
@FunctionalInterface
public interface ExplanationClient {
    ExplanationDraft explain(ExplanationRequest request);
}
