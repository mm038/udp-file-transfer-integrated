package nettransfer.llm;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded model input: approved IDs and local selections, never file contents or paths. */
public record InterpretationRequest(UUID requestId, String text, List<String> fileIds,
                                    List<String> receiverIds, UUID currentTransferId, UUID currentRunId,
                                    UUID lastTransferId, UUID lastRunId, List<Turn> clarificationHistory) {
    public static final int MAX_TEXT_LENGTH = 4_000;
    public static final int MAX_HISTORY_TURNS = 4;
    public static final int MAX_CATALOG_IDS = 100;

    public InterpretationRequest {
        Objects.requireNonNull(requestId, "requestId");
        requireText(text);
        fileIds = checkedIds(fileIds);
        receiverIds = checkedIds(receiverIds);
        clarificationHistory = List.copyOf(clarificationHistory);
        if (clarificationHistory.size() > MAX_HISTORY_TURNS) {
            throw new IllegalArgumentException("Too much clarification history");
        }
    }

    public record Turn(String role, String text) {
        public Turn {
            if (!"user".equals(role) && !"assistant".equals(role)) {
                throw new IllegalArgumentException("History roles must be user or assistant");
            }
            requireText(text);
        }
    }

    private static List<String> checkedIds(List<String> ids) {
        List<String> copy = List.copyOf(ids);
        if (copy.size() > MAX_CATALOG_IDS) {
            throw new IllegalArgumentException("GPT supports at most 100 IDs per catalogue");
        }
        for (String id : copy) {
            if (id.isBlank() || id.length() > 128) {
                throw new IllegalArgumentException("GPT catalogue IDs must contain 1-128 characters");
            }
        }
        return copy;
    }

    private static void requireText(String text) {
        Objects.requireNonNull(text, "text");
        if (text.isBlank() || text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("GPT text must contain 1-4000 characters");
        }
    }
}
