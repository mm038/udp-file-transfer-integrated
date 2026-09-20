package nettransfer.control;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Trusted input to the service, after Java resolves and validates the command.
 * The calling Java controller generates both IDs; neither comes from a model.
 * transferId is also the initial run ID, and is NOT the engine's wire UUID.
 * Construction checks required fields only: it does not approve files/endpoints
 * or replace the milestone 3 command validator in nettransfer.control.command.
 */
public record TransferRequest(UUID requestId, UUID transferId, String fileId,
                              String receiverId, Path sourcePath,
                              InetSocketAddress receiver, TransferSettings settings) {
    public TransferRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(receiverId, "receiverId");
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(receiver, "receiver");
        Objects.requireNonNull(settings, "settings");
    }
}
