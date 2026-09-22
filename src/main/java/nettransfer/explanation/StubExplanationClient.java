package nettransfer.explanation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Scripted offline analysis responses; no provider connection or API credentials. */
public final class StubExplanationClient implements ExplanationClient {
    private final ArrayDeque<ExplanationDraft> drafts;
    private final List<ExplanationRequest> requests = new ArrayList<>();

    public StubExplanationClient(List<ExplanationDraft> drafts) {
        this.drafts = new ArrayDeque<>(List.copyOf(drafts));
    }

    @Override
    public synchronized ExplanationDraft explain(ExplanationRequest request) {
        requests.add(Objects.requireNonNull(request, "request"));
        if (drafts.isEmpty()) {
            throw new IllegalStateException("No scripted offline explanation remains");
        }
        return drafts.removeFirst();
    }

    public synchronized List<ExplanationRequest> requests() {
        return List.copyOf(requests);
    }
}
