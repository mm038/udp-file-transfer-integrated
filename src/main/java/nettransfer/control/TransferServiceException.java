package nettransfer.control;

/** A rejected command or service operation; terminal transfer failures remain in its summary. */
public final class TransferServiceException extends RuntimeException {
    private final TransferError error;

    public TransferServiceException(TransferError.Code code, String message) {
        super(message);
        this.error = new TransferError(code, message);
    }

    public TransferError error() {
        return error;
    }
}
