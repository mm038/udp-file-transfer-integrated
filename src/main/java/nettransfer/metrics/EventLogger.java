package nettransfer.metrics;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Synchronous buffered JSONL evidence writer for one endpoint run.
 *
 * <p>All methods are synchronized to preserve local observation order. Runtime write failures are
 * retained as logging diagnostics and leave the run state incomplete; they never rewrite the
 * engine's actual transfer result.
 */
public final class EventLogger implements AutoCloseable {
    public static final String RUN_STATE_SCHEMA_VERSION = MetricsSchema.RUN_STATE_SCHEMA_VERSION;

    private static final Gson JSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private final boolean enabled;
    private final Path runDirectory;
    private final Path eventsFile;
    private final Path runStateFile;
    private final Path endpointMetricsFile;
    private final String localRunId;
    private final String applicationTransferId;
    private final TransferContext.Endpoint endpoint;
    private final String loggingStartedAt;
    private final LongSupplier monotonicClock;
    private BufferedWriter writer;
    private long nextSequence;
    private String loggingFailure;
    private boolean finalized;
    private boolean closed;
    private TransferContext latestContext;

    private EventLogger() {
        enabled = false;
        runDirectory = null;
        eventsFile = null;
        runStateFile = null;
        endpointMetricsFile = null;
        localRunId = null;
        applicationTransferId = null;
        endpoint = null;
        loggingStartedAt = null;
        monotonicClock = System::nanoTime;
        closed = true;
    }

    private EventLogger(Path runDirectory, Path eventsFile, Path runStateFile,
                        TransferContext context, BufferedWriter writer,
                        LongSupplier monotonicClock) {
        enabled = true;
        this.runDirectory = runDirectory;
        this.eventsFile = eventsFile;
        this.runStateFile = runStateFile;
        endpointMetricsFile = runDirectory.resolve(
                context.getEndpoint() == TransferContext.Endpoint.SENDER
                        ? "endpoint-sender.json" : "endpoint-receiver.json");
        localRunId = context.getRunId();
        applicationTransferId = context.getApplicationTransferId();
        endpoint = context.getEndpoint();
        loggingStartedAt = Instant.now().toString();
        this.writer = writer;
        this.monotonicClock = monotonicClock;
        latestContext = context;
    }

    public static EventLogger disabled() {
        return new EventLogger();
    }

    public static EventLogger open(Path logsRoot, TransferContext context) throws IOException {
        return open(logsRoot, context, System::nanoTime);
    }

    static EventLogger open(Path logsRoot, TransferContext context,
                            LongSupplier monotonicClock) throws IOException {
        Objects.requireNonNull(logsRoot, "logs root is required");
        Objects.requireNonNull(context, "transfer context is required");
        Objects.requireNonNull(monotonicClock, "monotonic clock is required");

        Path root = logsRoot.toAbsolutePath().normalize();
        String experiment = safePathComponent(
                context.getExperimentId() == null ? "standalone" : context.getExperimentId(),
                "experiment");
        String run = safePathComponent(context.getRunId(), "run");
        Path experimentDirectory = root.resolve(experiment).normalize();
        Path runDirectory = experimentDirectory.resolve(run).normalize();
        if (!runDirectory.startsWith(root)) {
            throw new IOException("Resolved log directory escapes the configured logs root");
        }

        Files.createDirectories(experimentDirectory);
        Files.createDirectory(runDirectory);
        String endpointName = context.getEndpoint().name().toLowerCase(java.util.Locale.ROOT);
        Path eventsFile = runDirectory.resolve("events-" + endpointName + ".jsonl");
        Path stateFile = runDirectory.resolve("run-state.json");
        BufferedWriter writer = Files.newBufferedWriter(eventsFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        EventLogger logger = new EventLogger(
                runDirectory, eventsFile, stateFile, context, writer, monotonicClock);
        try {
            logger.writeState(context, "RECORDING", null, null, false, null);
            return logger;
        } catch (IOException | RuntimeException exception) {
            try {
                writer.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }

    public synchronized void record(TransferContext context, EventType eventType,
                                    TransferEvent.Direction direction,
                                    TransferEvent.Details details) {
        if (!enabled || closed || loggingFailure != null) {
            return;
        }
        try {
            validateContext(context);
            boolean protocolIdentityEstablished = latestContext.getProtocolTransferId() == null
                    && context.getProtocolTransferId() != null;
            latestContext = context;
            TransferEvent.Details value = details == null ? TransferEvent.Details.empty() : details;
            long sequence = nextSequence;
            TransferEvent event = new TransferEvent(
                    TransferEvent.SCHEMA_VERSION,
                    localRunId + ":" + sequence,
                    sequence,
                    Instant.now().toString(),
                    monotonicClock.getAsLong(),
                    context.getExperimentId(),
                    localRunId,
                    context.getApplicationTransferId(),
                    context.getProtocolTransferId() == null
                            ? null : context.getProtocolTransferId().toString(),
                    endpoint.name(),
                    Objects.requireNonNull(eventType, "event type is required"),
                    Objects.requireNonNull(direction, "direction is required"),
                    value.messageType(), value.sequenceNumber(), value.ackNumber(),
                    value.attemptNumber(), value.payloadBytes(), value.encodedUdpPayloadBytes(),
                    value.validationResult(), value.sequenceOutcome(), value.eventOutcome(),
                    value.failureReason(), value.newlyAcknowledgedPackets(),
                    value.retransmission(), value.integrityVerified(),
                    value.impairmentDecisionIndex(), value.impairmentDelayMs());
            writer.write(JSON.toJson(event));
            writer.write('\n');
            nextSequence++;
            if (protocolIdentityEstablished) {
                // Publish only the newly trusted identity while the run remains explicitly RECORDING.
                // This lets exact repository lookup report PENDING during receiver completion recovery
                // without treating the unfinished event stream or metrics as finalized evidence.
                writeState(context, "RECORDING", null, null, false, null);
            }
        } catch (IOException | RuntimeException exception) {
            markFailure(context, describe(exception));
        }
    }

    public synchronized void finalizeSession(TransferContext context, boolean localSuccess,
                                             String terminalReason) {
        finalizeSession(context, null, localSuccess, terminalReason);
    }

    /** Finalizes event evidence and persists the terminal in-memory metrics snapshot. */
    public synchronized void finalizeSession(LiveMetricsSnapshot snapshot, boolean localSuccess,
                                             String terminalReason) {
        Objects.requireNonNull(snapshot, "terminal snapshot is required");
        finalizeSession(snapshot.context(), snapshot, localSuccess, terminalReason);
    }

    private void finalizeSession(TransferContext context, LiveMetricsSnapshot snapshot,
                                 boolean localSuccess, String terminalReason) {
        if (!enabled || finalized || closed) {
            return;
        }
        validateContext(context);
        latestContext = context;
        if (loggingFailure != null) {
            closeAfterFailure(context);
            return;
        }
        try {
            writer.flush();
            writer.close();
            writer = null;
            closed = true;
            if (snapshot != null) {
                MetricsExporter.writeEndpointRecord(
                        runDirectory, eventsFile, snapshot, localSuccess, terminalReason);
            }
            String state = localSuccess ? "FINALIZED_SUCCESS" : "FINALIZED_FAILED";
            writeState(context, state, localSuccess, terminalReason, true, null);
            finalized = true;
        } catch (IOException | RuntimeException exception) {
            markFailure(context, describe(exception));
            closeAfterFailure(context);
        }
    }

    @Override
    public synchronized void close() {
        if (!enabled || closed) {
            return;
        }
        TransferContext fallback = latestContext;
        try {
            writer.flush();
            writer.close();
            writer = null;
            closed = true;
            writeState(fallback, "INCOMPLETE", null, null, true, loggingFailure);
        } catch (IOException | RuntimeException exception) {
            markFailure(fallback, describe(exception));
            closeAfterFailure(fallback);
        }
    }

    public boolean isEnabled() { return enabled; }
    public synchronized boolean isFinalized() { return finalized; }
    public synchronized String getLoggingFailure() { return loggingFailure; }
    public Path getRunDirectory() { return runDirectory; }
    public Path getEventsFile() { return eventsFile; }
    public Path getRunStateFile() { return runStateFile; }
    public Path getEndpointMetricsFile() { return endpointMetricsFile; }
    public String getLocalRunId() { return localRunId; }
    public TransferContext.Endpoint getEndpoint() { return endpoint; }

    private void validateContext(TransferContext context) {
        Objects.requireNonNull(context, "transfer context is required");
        if (!localRunId.equals(context.getRunId()) || endpoint != context.getEndpoint()
                || !Objects.equals(applicationTransferId, context.getApplicationTransferId())) {
            throw new IllegalArgumentException("event context does not belong to this log session");
        }
    }

    private void markFailure(TransferContext context, String reason) {
        if (loggingFailure == null) {
            loggingFailure = reason;
            System.err.println("[event-logger] " + reason);
            try {
                writeState(context, "LOGGING_FAILED", null, null, false, reason);
            } catch (IOException | RuntimeException ignored) {
                // The in-memory diagnostic remains available when durable state is unavailable.
            }
        }
    }

    private void closeAfterFailure(TransferContext context) {
        if (!closed && writer != null) {
            try {
                writer.close();
            } catch (IOException closeFailure) {
                if (loggingFailure == null) {
                    loggingFailure = describe(closeFailure);
                }
            } finally {
                writer = null;
                closed = true;
            }
        }
        try {
            writeState(context, "LOGGING_FAILED", null, null, false, loggingFailure);
        } catch (IOException | RuntimeException ignored) {
            // The original writer failure is exposed by getLoggingFailure().
        }
    }

    private void writeState(TransferContext context, String recordingState,
                            Boolean localSuccess, String terminalReason,
                            boolean writerFlushedAndClosed, String failure) throws IOException {
        RunState state = new RunState(
                RUN_STATE_SCHEMA_VERSION,
                endpoint.name(),
                localRunId,
                context.getProtocolTransferId() == null
                        ? null : context.getProtocolTransferId().toString(),
                loggingStartedAt,
                recordingState,
                localSuccess,
                terminalReason,
                recordingState.startsWith("FINALIZED_") ? Instant.now().toString() : null,
                writerFlushedAndClosed,
                failure,
                nextSequence);
        Path temporary = runDirectory.resolve(".run-state-" + UUID.randomUUID() + ".tmp");
        Files.writeString(temporary, JSON.toJson(state) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, runStateFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, runStateFile, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static String safePathComponent(String value, String prefix) {
        if (value.matches("[A-Za-z0-9._-]{1,128}") && !value.equals(".") && !value.equals("..")) {
            return value;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return prefix + "-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String describe(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private record RunState(
            String schemaVersion,
            String endpoint,
            String runId,
            String protocolTransferId,
            String loggingStartedAtUtc,
            String recordingState,
            Boolean localSuccess,
            String terminalReason,
            String finalizedAtUtc,
            boolean writerFlushedAndClosed,
            String loggingFailure,
            long eventCount) {}
}
