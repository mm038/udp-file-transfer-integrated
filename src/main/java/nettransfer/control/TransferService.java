package nettransfer.control;

import java.util.UUID;

/**
 * Common boundary for simulated transfers and the real worker-based adapter.
 * All operations return promptly, without waiting for transfer completion.
 * Callers resolve current/last selection to a concrete ID before calling here.
 */
public interface TransferService {
    /** Atomically reserve the single active slot; reject conflicts with TRANSFER_BUSY. */
    TransferStart start(TransferRequest validatedRequest) throws TransferServiceException;

    /** Read existing evidence without advancing work; unknown IDs yield UNKNOWN_TRANSFER. */
    TransferSnapshot status(UUID transferId) throws TransferServiceException;

    /**
     * Return a frozen outcome by run ID. Active runs yield SUMMARY_NOT_READY;
     * unknown IDs yield UNKNOWN_TRANSFER; missing terminal evidence may yield
     * EVIDENCE_UNAVAILABLE. Terminal history survives subsequent starts.
     */
    TransferSummary summary(UUID runId) throws TransferServiceException;
}
