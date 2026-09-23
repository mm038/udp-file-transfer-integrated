package nettransfer.metrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static nettransfer.metrics.EvidenceLookupResult.Status.AVAILABLE;
import static nettransfer.metrics.EvidenceLookupResult.Status.INCOMPLETE;
import static nettransfer.metrics.EvidenceLookupResult.Status.PENDING;
import static nettransfer.metrics.EvidenceLookupResult.Status.REJECTED;
import static nettransfer.metrics.EvidenceLookupResult.Status.UNAVAILABLE;

/**
 * Locates only exact, validated evidence beneath one trusted logging root.
 * It never joins runs by filename, timestamp, directory order, or recency.
 */
public final class PersistedEvidenceRepository {
    private final Path loggingRoot;

    public PersistedEvidenceRepository(Path loggingRoot) throws IOException {
        Objects.requireNonNull(loggingRoot, "logging root is required");
        Path normalized = loggingRoot.toAbsolutePath().normalize();
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(normalized)
                    || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("trusted logging root must be a real directory");
            }
            normalized = normalized.toRealPath();
        }
        this.loggingRoot = normalized;
    }

    public Path loggingRoot() {
        return loggingRoot;
    }

    /** Stage 2 CLI identity uses the same trusted value for run and application IDs. */
    public EvidenceLookupResult lookup(String applicationTransferId) {
        return lookup(applicationTransferId, applicationTransferId);
    }

    /** Locates a sender by both exact endpoint-run and application identities. */
    public EvidenceLookupResult lookup(String senderRunId, String applicationTransferId) {
        if (!validId(senderRunId) || !validId(applicationTransferId)) {
            return rejected("INVALID_LOOKUP_ID", "Evidence lookup IDs must be nonblank trusted identifiers");
        }
        try {
            List<Path> senderDirectories = exactRunDirectories(senderRunId);
            if (senderDirectories.isEmpty()) {
                return unavailable("SENDER_NOT_FOUND", "No persisted sender run matches the requested identity");
            }
            if (senderDirectories.size() != 1) {
                return rejected("AMBIGUOUS_SENDER_RUN",
                        "More than one scope contains the requested sender run identity");
            }
            Probe senderProbe = probeEndpoint(
                    senderDirectories.get(0), TransferContext.Endpoint.SENDER);
            if (senderProbe.status != AVAILABLE) {
                return senderProbe.toLookup();
            }
            MetricsExporter.ValidatedEndpointEvidence sender = senderProbe.endpoint;
            EndpointMetricsRecord senderRecord = sender.record();
            if (!senderRunId.equals(senderRecord.runId())
                    || !applicationTransferId.equals(senderRecord.applicationTransferId())) {
                return rejected("SENDER_IDENTITY_MISMATCH",
                        "Persisted sender identity does not match the requested run and application IDs");
            }
            UUID protocolId;
            try {
                protocolId = UUID.fromString(senderRecord.protocolTransferId());
            } catch (RuntimeException exception) {
                return rejected("INVALID_PROTOCOL_UUID", "Sender evidence has no valid protocol identity");
            }

            EvidenceLookupResult existing = existingReconciled(sender, protocolId);
            if (existing != null) {
                return existing;
            }

            ReceiverSearch receiverSearch = findReceiver(protocolId);
            if (receiverSearch.result != null) {
                if ("FAILED".equals(senderRecord.terminalOutcome())
                        && receiverSearch.result.status() != REJECTED) {
                    return senderOnly(sender,
                            "Finalized sender failure is available; matching receiver evidence is not finalized");
                }
                return receiverSearch.result;
            }
            MetricsExporter.ValidatedEndpointEvidence receiver = receiverSearch.endpoint;
            try {
                MetricsExporter.ExportResult reconciled = MetricsExporter.reconcile(
                        sender.directory(), receiver.directory());
                MetricsExporter.ExportResult validated = MetricsExporter.readValidatedReconciled(
                        reconciled.outputDirectory());
                return available(sender, receiver, validated, null, null);
            } catch (MetricsExporter.EvidenceException exception) {
                if ("SUMMARY_COLLISION".equals(exception.getCode())) {
                    EvidenceLookupResult raced = existingReconciled(sender, protocolId);
                    if (raced != null) {
                        return raced;
                    }
                }
                return validationRejected(exception);
            } catch (IOException exception) {
                return rejected("EVIDENCE_IO_FAILURE", "Persisted evidence could not be read or reconciled safely");
            }
        } catch (UnsafeEvidencePath exception) {
            return rejected("UNSAFE_EVIDENCE_PATH", exception.getMessage());
        } catch (IOException exception) {
            return rejected("EVIDENCE_IO_FAILURE", "Persisted evidence could not be read safely");
        }
    }

    /** Retrieves one exact finalized sender endpoint without attempting association. */
    public EvidenceLookupResult lookupSender(String senderRunId, String applicationTransferId) {
        if (!validId(senderRunId) || !validId(applicationTransferId)) {
            return rejected("INVALID_LOOKUP_ID", "Evidence lookup IDs must be nonblank trusted identifiers");
        }
        try {
            List<Path> directories = exactRunDirectories(senderRunId);
            if (directories.isEmpty()) {
                return unavailable("SENDER_NOT_FOUND", "No persisted sender run matches the requested identity");
            }
            if (directories.size() != 1) {
                return rejected("AMBIGUOUS_SENDER_RUN",
                        "More than one scope contains the requested sender run identity");
            }
            Probe probe = probeEndpoint(directories.get(0), TransferContext.Endpoint.SENDER);
            if (probe.status != AVAILABLE) {
                return probe.toLookup();
            }
            EndpointMetricsRecord record = probe.endpoint.record();
            if (!senderRunId.equals(record.runId())
                    || !applicationTransferId.equals(record.applicationTransferId())) {
                return rejected("SENDER_IDENTITY_MISMATCH",
                        "Persisted sender identity does not match the requested run and application IDs");
            }
            return available(probe.endpoint, null, null, null, null);
        } catch (UnsafeEvidencePath exception) {
            return rejected("UNSAFE_EVIDENCE_PATH", exception.getMessage());
        } catch (IOException exception) {
            return rejected("EVIDENCE_IO_FAILURE", "Persisted sender evidence could not be read safely");
        }
    }

    /** Retrieves one unambiguous receiver endpoint using only the exact protocol UUID. */
    public EvidenceLookupResult lookupReceiver(UUID protocolTransferId) {
        Objects.requireNonNull(protocolTransferId, "protocol transfer ID is required");
        try {
            ReceiverSearch search = findReceiver(protocolTransferId);
            if (search.result != null) {
                return search.result;
            }
            return available(null, search.endpoint, null, null, null);
        } catch (UnsafeEvidencePath exception) {
            return rejected("UNSAFE_EVIDENCE_PATH", exception.getMessage());
        } catch (IOException exception) {
            return rejected("EVIDENCE_IO_FAILURE", "Persisted receiver evidence could not be read safely");
        }
    }

    /** Retrieves an already published reconciliation by exact protocol UUID. */
    public EvidenceLookupResult lookupReconciled(UUID protocolTransferId) {
        Objects.requireNonNull(protocolTransferId, "protocol transfer ID is required");
        try {
            List<Path> matches = reconciledDirectories(protocolTransferId);
            if (matches.isEmpty()) {
                return unavailable("RECONCILED_SUMMARY_NOT_FOUND",
                        "No finalized reconciliation matches the requested protocol identity");
            }
            if (matches.size() != 1) {
                return rejected("AMBIGUOUS_RECONCILED_SUMMARY",
                        "More than one scope contains the requested reconciled protocol identity");
            }
            return validatedReconciled(matches.get(0), null, protocolTransferId);
        } catch (UnsafeEvidencePath exception) {
            return rejected("UNSAFE_EVIDENCE_PATH", exception.getMessage());
        } catch (IOException exception) {
            return rejected("EVIDENCE_IO_FAILURE", "Reconciled evidence could not be read safely");
        }
    }

    private EvidenceLookupResult existingReconciled(
            MetricsExporter.ValidatedEndpointEvidence sender, UUID protocolId)
            throws IOException, UnsafeEvidencePath {
        Path scope = sender.directory().getParent();
        Path output = scope.resolve("reconciled").resolve(protocolId.toString());
        if (!Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        requireSafeDirectory(output);
        return validatedReconciled(output, sender, protocolId);
    }

    private EvidenceLookupResult validatedReconciled(
            Path output, MetricsExporter.ValidatedEndpointEvidence expectedSender, UUID protocolId) {
        try {
            requireWithinRoot(output);
            MetricsExporter.ExportResult export = MetricsExporter.readValidatedReconciled(output);
            FinalMetricsSummary summary = export.summary();
            if (!protocolId.toString().equals(summary.protocolTransferId())) {
                return rejected("RECONCILED_PROTOCOL_MISMATCH",
                        "Reconciled evidence does not match the requested protocol identity");
            }
            Path scope = output.getParent().getParent();
            MetricsExporter.ValidatedEndpointEvidence sender = validatedRunInScope(
                    scope, summary.senderRunId(), TransferContext.Endpoint.SENDER);
            MetricsExporter.ValidatedEndpointEvidence receiver = validatedRunInScope(
                    scope, summary.receiverRunId(), TransferContext.Endpoint.RECEIVER);
            if (expectedSender != null
                    && !expectedSender.record().runId().equals(sender.record().runId())) {
                return rejected("RECONCILED_SENDER_MISMATCH",
                        "Reconciled evidence belongs to a different sender run");
            }
            return available(sender, receiver, export, null, null);
        } catch (MetricsExporter.EvidenceException exception) {
            return validationRejected(exception);
        } catch (IOException | UnsafeEvidencePath exception) {
            return rejected("UNSAFE_OR_INACCESSIBLE_RECONCILIATION",
                    "Reconciled evidence references are unsafe or inaccessible");
        }
    }

    private MetricsExporter.ValidatedEndpointEvidence validatedRunInScope(
            Path scope, String runId, TransferContext.Endpoint endpoint)
            throws IOException, MetricsExporter.EvidenceException, UnsafeEvidencePath {
        Path directory = scope.resolve(EventLogger.safePathComponent(runId, "run")).normalize();
        requireSafeDirectory(directory);
        MetricsExporter.ValidatedEndpointEvidence evidence =
                MetricsExporter.readValidatedEndpoint(directory, endpoint);
        if (!runId.equals(evidence.record().runId())) {
            throw new MetricsExporter.EvidenceException(
                    "IDENTITY_CONFLICT", "endpoint run identity does not match its directory lookup");
        }
        return evidence;
    }

    private ReceiverSearch findReceiver(UUID protocolId)
            throws IOException, UnsafeEvidencePath {
        List<Path> matches = receiverDirectories(protocolId.toString());
        if (matches.isEmpty()) {
            return new ReceiverSearch(null, unavailable("RECEIVER_NOT_FOUND",
                    "No receiver evidence matches the sender protocol identity"));
        }
        if (matches.size() != 1) {
            return new ReceiverSearch(null, rejected("AMBIGUOUS_RECEIVER_EVIDENCE",
                    "More than one receiver run claims the requested protocol identity"));
        }
        Probe probe = probeEndpoint(matches.get(0), TransferContext.Endpoint.RECEIVER);
        if (probe.status != AVAILABLE) {
            return new ReceiverSearch(null, probe.toLookup());
        }
        if (!protocolId.toString().equals(probe.endpoint.record().protocolTransferId())) {
            return new ReceiverSearch(null, rejected("RECEIVER_PROTOCOL_MISMATCH",
                    "Receiver evidence does not match the requested protocol identity"));
        }
        return new ReceiverSearch(probe.endpoint, null);
    }

    private Probe probeEndpoint(Path directory, TransferContext.Endpoint endpoint)
            throws IOException, UnsafeEvidencePath {
        requireSafeDirectory(directory);
        Path statePath = directory.resolve("run-state.json");
        if (!Files.exists(statePath, LinkOption.NOFOLLOW_LINKS)) {
            return Probe.incomplete("RUN_STATE_MISSING", "Run state is missing before finalization");
        }
        requireSafeFile(statePath);
        JsonObject state;
        try {
            state = JsonParser.parseString(Files.readString(statePath, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RuntimeException exception) {
            return Probe.rejected("MALFORMED_RUN_STATE", "Run state is malformed");
        }
        if (!jsonEquals(state, "schema_version", MetricsSchema.RUN_STATE_SCHEMA_VERSION)
                || !jsonEquals(state, "endpoint", endpoint.name())) {
            return Probe.rejected("UNSUPPORTED_OR_INCONSISTENT_RUN_STATE",
                    "Run state schema or endpoint role is inconsistent");
        }
        String recordingState = jsonString(state, "recording_state");
        if ("RECORDING".equals(recordingState)) {
            return Probe.pending("RUN_STILL_RECORDING", "Matching endpoint evidence is still recording");
        }
        if ("INCOMPLETE".equals(recordingState) || "LOGGING_FAILED".equals(recordingState)) {
            return Probe.incomplete("RUN_INCOMPLETE",
                    "Matching endpoint recording did not reach a finalized evidence boundary");
        }
        if (!("FINALIZED_SUCCESS".equals(recordingState)
                || "FINALIZED_FAILED".equals(recordingState))) {
            return Probe.rejected("UNSUPPORTED_RUN_STATE", "Run state is unsupported");
        }
        try {
            return Probe.available(MetricsExporter.readValidatedEndpoint(directory, endpoint));
        } catch (MetricsExporter.EvidenceException exception) {
            return Probe.rejected("VALIDATION_" + exception.getCode(),
                    "Finalized endpoint evidence failed validation");
        } catch (IOException exception) {
            return Probe.rejected("ENDPOINT_EVIDENCE_IO_FAILURE",
                    "Finalized endpoint evidence is inaccessible or malformed");
        }
    }

    private List<Path> exactRunDirectories(String runId)
            throws IOException, UnsafeEvidencePath {
        List<Path> matches = new ArrayList<>();
        if (!rootAvailable()) {
            return matches;
        }
        String component = EventLogger.safePathComponent(runId, "run");
        for (Path scope : scopeDirectories()) {
            Path candidate = scope.resolve(component).normalize();
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                requireSafeDirectory(candidate);
                matches.add(candidate);
            }
        }
        return matches;
    }

    private List<Path> receiverDirectories(String protocolId)
            throws IOException, UnsafeEvidencePath {
        Set<Path> matches = new LinkedHashSet<>();
        if (!rootAvailable()) {
            return List.of();
        }
        for (Path scope : scopeDirectories()) {
            try (DirectoryStream<Path> runs = Files.newDirectoryStream(scope)) {
                for (Path run : runs) {
                    if ("reconciled".equals(run.getFileName().toString())) {
                        continue;
                    }
                    if (Files.isSymbolicLink(run)) {
                        throw new UnsafeEvidencePath("Symbolic run directories are not allowed");
                    }
                    if (!Files.isDirectory(run, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    requireWithinRoot(run);
                    if (claimsReceiverProtocol(run.resolve("run-state.json"), protocolId)
                            || claimsReceiverProtocol(run.resolve("endpoint-receiver.json"), protocolId)) {
                        matches.add(run);
                    }
                }
            }
        }
        return List.copyOf(matches);
    }

    private boolean claimsReceiverProtocol(Path file, String protocolId)
            throws IOException, UnsafeEvidencePath {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        requireSafeFile(file);
        try {
            JsonObject object = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            return jsonEquals(object, "endpoint", TransferContext.Endpoint.RECEIVER.name())
                    && jsonEquals(object, "protocol_transfer_id", protocolId);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private List<Path> reconciledDirectories(UUID protocolId)
            throws IOException, UnsafeEvidencePath {
        List<Path> matches = new ArrayList<>();
        if (!rootAvailable()) {
            return matches;
        }
        for (Path scope : scopeDirectories()) {
            Path candidate = scope.resolve("reconciled").resolve(protocolId.toString()).normalize();
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                requireSafeDirectory(candidate);
                matches.add(candidate);
            }
        }
        return matches;
    }

    private List<Path> scopeDirectories() throws IOException, UnsafeEvidencePath {
        List<Path> scopes = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(loggingRoot)) {
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)) {
                    throw new UnsafeEvidencePath("Symbolic scope directories are not allowed");
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    requireWithinRoot(entry);
                    scopes.add(entry);
                }
            }
        }
        return scopes;
    }

    private boolean rootAvailable() {
        return Files.isDirectory(loggingRoot, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(loggingRoot);
    }

    private void requireSafeDirectory(Path directory) throws UnsafeEvidencePath, IOException {
        requireWithinRoot(directory);
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new UnsafeEvidencePath("Evidence directory is unsafe or unavailable");
        }
    }

    private void requireSafeFile(Path file) throws UnsafeEvidencePath, IOException {
        requireWithinRoot(file);
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new UnsafeEvidencePath("Evidence file is unsafe or unavailable");
        }
    }

    private void requireWithinRoot(Path path) throws UnsafeEvidencePath, IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(loggingRoot)) {
            throw new UnsafeEvidencePath("Evidence path escapes the trusted logging root");
        }
        Path current = loggingRoot;
        for (Path component : loggingRoot.relativize(normalized)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new UnsafeEvidencePath("Symbolic evidence paths are not allowed");
            }
        }
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
                && !normalized.toRealPath().startsWith(loggingRoot.toRealPath())) {
            throw new UnsafeEvidencePath("Evidence path escapes the trusted logging root");
        }
    }

    private static String jsonString(JsonObject object, String field) {
        return object.has(field) && !object.get(field).isJsonNull()
                ? object.get(field).getAsString() : null;
    }

    private static boolean jsonEquals(JsonObject object, String field, String expected) {
        return Objects.equals(expected, jsonString(object, field));
    }

    private static boolean validId(String value) {
        return value != null && !value.isBlank();
    }

    private static EvidenceLookupResult available(
            MetricsExporter.ValidatedEndpointEvidence sender,
            MetricsExporter.ValidatedEndpointEvidence receiver,
            MetricsExporter.ExportResult reconciled,
            String reasonCode, String reason) {
        return new EvidenceLookupResult(AVAILABLE, reasonCode, reason,
                new EvidenceLookupResult.Evidence(sender, receiver, reconciled));
    }

    private static EvidenceLookupResult senderOnly(
            MetricsExporter.ValidatedEndpointEvidence sender, String reason) {
        return available(sender, null, null, "SENDER_ONLY_FINALIZED_FAILURE", reason);
    }

    private static EvidenceLookupResult pending(String code, String reason) {
        return new EvidenceLookupResult(PENDING, code, reason, null);
    }

    private static EvidenceLookupResult incomplete(String code, String reason) {
        return new EvidenceLookupResult(INCOMPLETE, code, reason, null);
    }

    private static EvidenceLookupResult unavailable(String code, String reason) {
        return new EvidenceLookupResult(UNAVAILABLE, code, reason, null);
    }

    private static EvidenceLookupResult rejected(String code, String reason) {
        return new EvidenceLookupResult(REJECTED, code, reason, null);
    }

    private static EvidenceLookupResult validationRejected(
            MetricsExporter.EvidenceException exception) {
        return rejected("VALIDATION_" + exception.getCode(),
                "Persisted evidence failed authoritative validation");
    }

    private record ReceiverSearch(
            MetricsExporter.ValidatedEndpointEvidence endpoint,
            EvidenceLookupResult result) {}

    private record Probe(EvidenceLookupResult.Status status, String code, String reason,
                         MetricsExporter.ValidatedEndpointEvidence endpoint) {
        static Probe available(MetricsExporter.ValidatedEndpointEvidence endpoint) {
            return new Probe(AVAILABLE, null, null, endpoint);
        }

        static Probe pending(String code, String reason) {
            return new Probe(PENDING, code, reason, null);
        }

        static Probe incomplete(String code, String reason) {
            return new Probe(INCOMPLETE, code, reason, null);
        }

        static Probe rejected(String code, String reason) {
            return new Probe(REJECTED, code, reason, null);
        }

        EvidenceLookupResult toLookup() {
            return switch (status) {
                case PENDING -> PersistedEvidenceRepository.pending(code, reason);
                case INCOMPLETE -> PersistedEvidenceRepository.incomplete(code, reason);
                case REJECTED -> PersistedEvidenceRepository.rejected(code, reason);
                case UNAVAILABLE -> PersistedEvidenceRepository.unavailable(code, reason);
                case AVAILABLE -> throw new IllegalStateException("Available probe has endpoint evidence");
            };
        }
    }

    private static final class UnsafeEvidencePath extends Exception {
        private UnsafeEvidencePath(String message) {
            super(message);
        }
    }
}
