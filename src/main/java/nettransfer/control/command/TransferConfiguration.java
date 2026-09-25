package nettransfer.control.command;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Trusted Java configuration, never model-supplied paths, addresses or bounds.
 * Relative catalogue paths resolve against the explicit application root and
 * must remain under storage/outgoing. These policy bounds are the team's draft values.
 */
public record TransferConfiguration(Path applicationRoot, Map<String, Path> approvedFiles,
                                    Map<String, InetSocketAddress> approvedReceivers) {
    public static final int CHUNK_SIZE_BYTES = 1024;
    public static final long DEFAULT_WINDOW_BYTES = 1024;
    public static final long MIN_WINDOW_BYTES = 1024;
    public static final long MAX_WINDOW_BYTES = 1_048_576;
    public static final int DEFAULT_TIMEOUT_MILLIS = 200;
    public static final int MIN_TIMEOUT_MILLIS = 50;
    public static final int MAX_TIMEOUT_MILLIS = 5000;
    public static final int RETRY_LIMIT = 5;

    public TransferConfiguration {
        Objects.requireNonNull(applicationRoot, "applicationRoot");
        if (!applicationRoot.isAbsolute()) {
            throw new IllegalArgumentException("Supply an absolute application root");
        }
        applicationRoot = applicationRoot.normalize();
        approvedFiles = Map.copyOf(approvedFiles);
        approvedReceivers = Map.copyOf(approvedReceivers);
        for (String id : approvedFiles.keySet()) {
            requireId(id);
        }
        for (var entry : approvedReceivers.entrySet()) {
            requireId(entry.getKey());
            InetSocketAddress endpoint = entry.getValue();
            if (endpoint.isUnresolved() || endpoint.getPort() < 1) {
                throw new IllegalArgumentException("Configured receivers need a resolved address and port 1..65535");
            }
        }
    }

    /** Existing demo destination; the caller must explicitly supply approved file IDs. */
    public static TransferConfiguration localhost(Path applicationRoot, Map<String, Path> approvedFiles) {
        return new TransferConfiguration(applicationRoot, approvedFiles,
                Map.of("receiver-a", new InetSocketAddress("127.0.0.1", 9000)));
    }

    public Path inputRoot() {
        return applicationRoot.resolve("storage/outgoing");
    }

    private static void requireId(String id) {
        if (id.isBlank() || !id.equals(id.strip())) {
            throw new IllegalArgumentException("Configured IDs must be nonblank without surrounding whitespace");
        }
    }
}
