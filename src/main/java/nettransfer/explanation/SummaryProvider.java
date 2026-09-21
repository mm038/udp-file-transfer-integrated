package nettransfer.explanation;

import java.util.Optional;
import java.util.UUID;

/** Read-only boundary. Real adapters require implemented producer serialization and verified identity mapping. */
@FunctionalInterface
public interface SummaryProvider {
    Optional<RecordedSummary> load(UUID runId);

    static SummaryProvider unavailable() {
        return runId -> Optional.empty();
    }
}
