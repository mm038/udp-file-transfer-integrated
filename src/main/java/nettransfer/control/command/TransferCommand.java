package nettransfer.control.command;

import java.util.UUID;

/** Parsed proposals, still subject to Java validation. Units are bytes and milliseconds. */
public sealed interface TransferCommand {
    record Start(String fileId, String receiverId, Long windowBytes, Long timeoutMillis)
            implements TransferCommand { }

    /** A null ID asks the caller's Java selection context to choose an existing transfer. */
    record Status(UUID transferId) implements TransferCommand { }

    /** Selects evidence only; explanation generation belongs to a later milestone. */
    record Explain(UUID runId, String question) implements TransferCommand { }
}
