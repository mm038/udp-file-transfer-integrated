package nettransfer.transfer;

/** Immutable outcome of a completed (or failed) file transfer attempt. */
public final class TransferResult {

    public enum FailureReason {
        START_HANDSHAKE_TIMEOUT,
        FINISH_HANDSHAKE_TIMEOUT,
        RECEIVER_INITIAL_TIMEOUT,
        RECEIVER_INACTIVITY_TIMEOUT
    }

    private final boolean success;
    private final String message;
    private final int totalChunks;
    private final FailureReason failureReason;

    private TransferResult(boolean success, String message, int totalChunks, FailureReason failureReason) {
        this.success = success;
        this.message = message;
        this.totalChunks = totalChunks;
        this.failureReason = failureReason;
    }

    public static TransferResult success(int totalChunks) {
        return new TransferResult(true, "Transfer completed and verified", totalChunks, null);
    }

    public static TransferResult failure(String message) {
        return new TransferResult(false, message, -1, null);
    }

    public static TransferResult failure(FailureReason reason, String message) {
        return new TransferResult(false, message, -1, reason);
    }

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public int getTotalChunks() { return totalChunks; }
    public FailureReason getFailureReason() { return failureReason; }

    @Override
    public String toString() {
        return (success ? "SUCCESS" : "FAILURE") + ": " + message;
    }
}
