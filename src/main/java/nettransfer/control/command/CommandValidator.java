package nettransfer.control.command;

import nettransfer.control.TransferRequest;
import nettransfer.control.TransferServiceException;
import nettransfer.control.TransferSettings;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

import static nettransfer.control.TransferError.Code.CLARIFICATION_REQUIRED;
import static nettransfer.control.TransferError.Code.FILE_UNAVAILABLE;
import static nettransfer.control.TransferError.Code.INVALID_PARAMETER;
import static nettransfer.control.TransferError.Code.UNKNOWN_FILE;
import static nettransfer.control.TransferError.Code.UNKNOWN_RECEIVER;
import static nettransfer.control.command.TransferConfiguration.*;

/** Resolves approved IDs, checks source access and normalizes settings before service.start. */
public final class CommandValidator {
    private final TransferConfiguration configuration;

    public CommandValidator(TransferConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    public TransferRequest validateStart(TransferCommand.Start command, UUID requestId) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(requestId, "requestId");
        if (command.fileId() == null || command.fileId().isBlank()
                || command.receiverId() == null || command.receiverId().isBlank()) {
            throw new TransferServiceException(CLARIFICATION_REQUIRED,
                    "Please identify an approved file ID and receiver ID; Java will not guess them");
        }
        Path configuredFile = configuration.approvedFiles().get(command.fileId());
        if (configuredFile == null) {
            throw new TransferServiceException(UNKNOWN_FILE, "Unknown approved file ID: " + command.fileId());
        }
        InetSocketAddress receiver = configuration.approvedReceivers().get(command.receiverId());
        if (receiver == null) {
            throw new TransferServiceException(UNKNOWN_RECEIVER,
                    "Unknown approved receiver ID: " + command.receiverId());
        }

        long windowBytes = command.windowBytes() == null ? DEFAULT_WINDOW_BYTES : command.windowBytes();
        long timeoutMillis = command.timeoutMillis() == null ? DEFAULT_TIMEOUT_MILLIS : command.timeoutMillis();
        if (windowBytes < MIN_WINDOW_BYTES || windowBytes > MAX_WINDOW_BYTES) {
            throw new TransferServiceException(INVALID_PARAMETER,
                    "window_bytes must be between " + MIN_WINDOW_BYTES + " and " + MAX_WINDOW_BYTES);
        }
        if (timeoutMillis < MIN_TIMEOUT_MILLIS || timeoutMillis > MAX_TIMEOUT_MILLIS) {
            throw new TransferServiceException(INVALID_PARAMETER,
                    "timeout_ms must be between " + MIN_TIMEOUT_MILLIS + " and " + MAX_TIMEOUT_MILLIS);
        }
        // Bounds above guarantee at least one packet and safe narrowing conversions.
        int windowPackets = (int) (windowBytes / CHUNK_SIZE_BYTES);
        TransferSettings settings = new TransferSettings(CHUNK_SIZE_BYTES, windowBytes,
                windowPackets, (int) timeoutMillis, RETRY_LIMIT);
        return new TransferRequest(requestId, UUID.randomUUID(), command.fileId(), command.receiverId(),
                resolveReadableFile(configuredFile), receiver, settings);
    }

    public void validateQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new TransferServiceException(CLARIFICATION_REQUIRED,
                    "What would you like explained about the selected run?");
        }
    }

    private Path resolveReadableFile(Path configuredFile) {
        Path candidate = configuration.applicationRoot().resolve(configuredFile).normalize();
        if (!candidate.startsWith(configuration.inputRoot())) {
            throw new TransferServiceException(FILE_UNAVAILABLE, "Approved source must be under data/input");
        }
        try {
            Path realApplicationRoot = configuration.applicationRoot().toRealPath();
            Path realInputRoot = configuration.inputRoot().toRealPath();
            Path realFile = candidate.toRealPath();
            // Keep the designated input root fixed; file links may only remain inside it.
            if (!realInputRoot.equals(realApplicationRoot.resolve("data/input")) || !realFile.startsWith(realInputRoot)
                    || !Files.isRegularFile(realFile) || !Files.isReadable(realFile)) {
                throw new TransferServiceException(FILE_UNAVAILABLE,
                        "Approved source must be a readable regular file within data/input, including resolved links");
            }
            return realFile;
        } catch (IOException | SecurityException e) {
            throw new TransferServiceException(FILE_UNAVAILABLE, "Approved source is missing or cannot be read");
        }
    }
}
