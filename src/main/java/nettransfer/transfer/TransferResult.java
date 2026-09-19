package nettransfer.transfer;

/** Immutable outcome of a completed (or failed) file transfer attempt. */
public final class TransferResult {

    private final boolean success;
    private final String message;
    private final int totalChunks;

    private TransferResult(boolean success, String message, int totalChunks) {
        this.success = success;
        this.message = message;
        this.totalChunks = totalChunks;
    }

    public static TransferResult success(int totalChunks) {
        return new TransferResult(true, "Transfer completed and verified", totalChunks);
    }

    public static TransferResult failure(String message) {
        return new TransferResult(false, message, -1);
    }

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
    public int getTotalChunks() { return totalChunks; }

    @Override
    public String toString() {
        return (success ? "SUCCESS" : "FAILURE") + ": " + message;
    }
}