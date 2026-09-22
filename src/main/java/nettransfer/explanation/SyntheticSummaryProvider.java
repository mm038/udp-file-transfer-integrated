package nettransfer.explanation;

import nettransfer.control.EvidenceSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Explicitly supplied, immutable fixtures. Never creates measurements or falls back to another run. */
public final class SyntheticSummaryProvider implements SummaryProvider {
    private final Map<UUID, RecordedSummary> summaries;

    public SyntheticSummaryProvider(List<RecordedSummary> fixtures) {
        var byRun = new HashMap<UUID, RecordedSummary>();
        for (RecordedSummary fixture : List.copyOf(fixtures)) {
            if (fixture.source() != EvidenceSource.SYNTHETIC
                    || !RecordedSummary.FIXTURE_DEFINITION_VERSION.equals(fixture.definitionVersion())) {
                throw new IllegalArgumentException("Only explicitly synthetic draft fixtures are supported");
            }
            if (byRun.putIfAbsent(fixture.runId(), fixture) != null) {
                throw new IllegalArgumentException("Duplicate fixture run ID");
            }
        }
        summaries = Map.copyOf(byRun);
    }

    @Override
    public Optional<RecordedSummary> load(UUID runId) {
        return Optional.ofNullable(summaries.get(runId));
    }
}
