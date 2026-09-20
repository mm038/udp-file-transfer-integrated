package nettransfer.control;

/**
 * Effective engine settings plus the original byte window budget. The retry
 * limit counts consecutive Go-Back-N rounds without progress, not packet retries.
 * Policy bounds, defaults and byte-to-packet conversion belong to the validator.
 */
public record TransferSettings(int chunkSizeBytes, long requestedWindowBytes,
                               int windowPackets, int timeoutMillis, int retryLimit) {
    public TransferSettings {
        if (chunkSizeBytes <= 0 || requestedWindowBytes <= 0 || windowPackets <= 0
                || timeoutMillis <= 0 || retryLimit <= 0) {
            throw new IllegalArgumentException("Transfer settings must be positive");
        }
    }
}
