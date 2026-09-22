package nettransfer.control;

import java.util.Objects;

/** Machine-readable service errors, separate from their display messages. */
public record TransferError(Code code, String message) {
    public enum Code {
        INVALID_COMMAND,
        UNKNOWN_FILE,
        UNKNOWN_RECEIVER,
        FILE_UNAVAILABLE,
        CLARIFICATION_REQUIRED,
        UNSUPPORTED_REQUEST,
        REQUEST_ALREADY_DISPATCHED,
        SERVICE_CLOSED,
        INVALID_PARAMETER,
        TRANSFER_BUSY,
        UNKNOWN_TRANSFER,
        SUMMARY_NOT_READY,
        EVIDENCE_UNAVAILABLE,
        TRANSFER_FAILED,
        INTEGRITY_FAILED
    }

    public TransferError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }
}
