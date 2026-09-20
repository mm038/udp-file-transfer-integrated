package nettransfer.control;

/**
 * Minimal draft metric contract; Person 2's full measurement schema is pending.
 * Byte counts are payload bytes. ACKed bytes are unique sender-observed progress,
 * not bytes sent and not final integrity verification. totalChunks is the engine
 * result count, not a retransmission count. All null fields share unavailableReason.
 *
 * elapsedMillis means protocol duration (first START send to the snapshot or
 * terminal outcome), measured with a monotonic clock by a future observer.
 * It must stay null without those observations; wall-clock subtraction or worker
 * lifetime is not a substitute. The fake supplies explicitly synthetic values.
 */
public record TransferMetrics(Long fileSizeBytes, Long uniquePayloadBytesAcked,
                              Long elapsedMillis, Integer totalChunks,
                              String unavailableReason) {
    public static final String DEFINITION_VERSION = "person-3-draft-1";

    public TransferMetrics {
        if ((fileSizeBytes != null && fileSizeBytes < 0)
                || (uniquePayloadBytesAcked != null && uniquePayloadBytesAcked < 0)
                || (elapsedMillis != null && elapsedMillis < 0)
                || (totalChunks != null && totalChunks < 0)) {
            throw new IllegalArgumentException("Metrics must be nonnegative or unavailable");
        }
        if (fileSizeBytes != null && uniquePayloadBytesAcked != null
                && uniquePayloadBytesAcked > fileSizeBytes) {
            throw new IllegalArgumentException("Unique ACKed bytes cannot exceed the file size");
        }
        if ((fileSizeBytes == null || uniquePayloadBytesAcked == null
                || elapsedMillis == null || totalChunks == null)
                && (unavailableReason == null || unavailableReason.isBlank())) {
            throw new IllegalArgumentException("Unavailable metrics need a reason");
        }
    }

    public static TransferMetrics unavailable(String reason) {
        return new TransferMetrics(null, null, null, null, reason);
    }

    /** Normalize Stage 10.5's result sentinel without changing the engine. */
    public static Integer chunkCountFromEngine(int count) {
        if (count < -1) {
            throw new IllegalArgumentException("Unexpected negative engine chunk count");
        }
        return count == -1 ? null : Integer.valueOf(count);
    }
}
