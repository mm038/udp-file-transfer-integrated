package nettransfer.explanation;

import java.util.Optional;
import java.util.UUID;

/** Read-only boundary. Future real persistence adapters require Person 2's agreed contract. */
@FunctionalInterface
public interface SummaryProvider {
    Optional<RecordedSummary> load(UUID runId);

    static SummaryProvider unavailable() {
        return runId -> Optional.empty();
    }
}
