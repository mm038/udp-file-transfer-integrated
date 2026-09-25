package nettransfer.control.engine;

import nettransfer.control.EvidenceSource;
import nettransfer.control.IntegrityStatus;
import nettransfer.control.TransferError;
import nettransfer.control.TransferMetrics;
import nettransfer.control.TransferRequest;
import nettransfer.control.TransferService;
import nettransfer.control.TransferServiceException;
import nettransfer.control.TransferSnapshot;
import nettransfer.control.TransferStart;
import nettransfer.control.TransferState;
import nettransfer.control.TransferSummary;
import nettransfer.net.UdpChannel;
import nettransfer.net.ImpairmentSettings;
import nettransfer.metrics.EventLogger;
import nettransfer.metrics.LiveMetricsSnapshot;
import nettransfer.metrics.MetricsSchema;
import nettransfer.metrics.TransferConfiguration;
import nettransfer.metrics.TransferContext;
import nettransfer.transfer.SenderEngine;
import nettransfer.transfer.TransferResult;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import static nettransfer.control.TransferError.Code.INVALID_PARAMETER;
import static nettransfer.control.TransferError.Code.SERVICE_CLOSED;
import static nettransfer.control.TransferError.Code.SUMMARY_NOT_READY;
import static nettransfer.control.TransferError.Code.TRANSFER_BUSY;
import static nettransfer.control.TransferError.Code.TRANSFER_FAILED;
import static nettransfer.control.TransferError.Code.UNKNOWN_TRANSFER;

/**
 * Runs the blocking sender on one worker. Each run owns its channel and simulator decisions.
 * The monitor protects only state publication/reservation, never network or file I/O.
 * No progress, protocol timing, or wire identity is inferred without engine hooks.
 */
public final class RealTransferService implements TransferService, AutoCloseable {
    public static final int DEFAULT_INITIAL_RESPONSE_TIMEOUT_MILLIS = 2000;
    private static final String UNAVAILABLE =
            "No sender-engine observation is available yet";

    private final ExecutorService worker;
    private final Clock clock;
    private final SessionFactory sessionFactory;
    private final Map<UUID, Run> runs = new HashMap<>();
    private UUID activeTransferId;
    private boolean closed;

    public RealTransferService() {
        this(DEFAULT_INITIAL_RESPONSE_TIMEOUT_MILLIS);
    }

    /** Creates a service with durable sender evidence under a trusted logging root. */
    public RealTransferService(Path loggingRoot) {
        this(DEFAULT_INITIAL_RESPONSE_TIMEOUT_MILLIS, loggingRoot);
    }

    /** Trusted startup-only simulator configuration; it cannot be set by model commands. */
    public RealTransferService(Path loggingRoot, ImpairmentSettings impairment) {
        this(DEFAULT_INITIAL_RESPONSE_TIMEOUT_MILLIS, loggingRoot, impairment);
    }

    /**
     * This per-attempt START response timeout is separate from the validated DATA timeout.
     * The sender retains its normal retry limit, so the approximate maximum START handshake
     * duration is this timeout multiplied by the total number of attempts.
     */
    public RealTransferService(int initialResponseTimeoutMillis) {
        this(initialResponseTimeoutMillis, null);
    }

    /**
     * The logging root is trusted Java startup configuration, never command or model input.
     * A null root preserves the explicitly non-persistent programmatic mode.
     */
    public RealTransferService(int initialResponseTimeoutMillis, Path loggingRoot) {
        this(initialResponseTimeoutMillis, loggingRoot, ImpairmentSettings.disabled());
    }

    public RealTransferService(int initialResponseTimeoutMillis, Path loggingRoot,
                               ImpairmentSettings impairment) {
        Objects.requireNonNull(impairment, "impairment settings");
        if (initialResponseTimeoutMillis <= 0) {
            throw new IllegalArgumentException("The initial receive timeout must be finite and positive");
        }
        Path trustedLoggingRoot = loggingRoot == null
                ? null : loggingRoot.toAbsolutePath().normalize();
        this.worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "transfer-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.clock = Clock.systemUTC();
        this.sessionFactory = request -> openEngineSession(
                request, initialResponseTimeoutMillis, trustedLoggingRoot, impairment);
    }

    /** Package-local seams let tests gate blocking work without changing the UDP engine. */
    RealTransferService(ExecutorService worker, Clock clock, SessionFactory sessionFactory) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    }

    @Override
    public synchronized TransferStart start(TransferRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed) {
            throw new TransferServiceException(SERVICE_CLOSED, "The transfer service is closed");
        }
        if (activeTransferId != null) {
            throw new TransferServiceException(TRANSFER_BUSY, "A transfer is already running");
        }
        if (runs.containsKey(request.transferId())) {
            throw new TransferServiceException(INVALID_PARAMETER, "Transfer ID has already been used");
        }
        Run run = new Run(request);
        run.snapshot = snapshot(run, TransferState.RUNNING, null);
        runs.put(request.transferId(), run);
        activeTransferId = request.transferId();
        TransferStart accepted = new TransferStart(request.transferId(), request.transferId(), null,
                run.snapshot.snapshotAt(), EvidenceSource.REAL);
        try {
            worker.execute(() -> runTransfer(run));
        } catch (RejectedExecutionException e) {
            finish(run, false, "The transfer worker could not accept the start request");
            throw new TransferServiceException(TRANSFER_FAILED, run.summary.message());
        }
        return accepted;
    }

    @Override
    public TransferSnapshot status(UUID transferId) {
        Run run;
        SenderSession session;
        synchronized (this) {
            run = findRun(transferId);
            if (run.summary != null || run.session == null) {
                return run.snapshot;
            }
            session = run.session;
        }

        // MetricsCollector builds this immutable view under its own short lock;
        // status never waits for network or file I/O and never touches the socket.
        LiveMetricsSnapshot observed = session.liveMetricsSnapshot();
        synchronized (this) {
            if (observed != null && run.summary == null && run.session == session) {
                retain(run, observed);
            }
            return run.snapshot;
        }
    }

    @Override
    public synchronized TransferSummary summary(UUID runId) {
        // Application transfer and run IDs are identical; the protocol UUID is preserved separately.
        Run run = findRun(runId);
        if (run.summary == null) {
            throw new TransferServiceException(SUMMARY_NOT_READY, "Transfer is still running");
        }
        return run.summary;
    }

    private void runTransfer(Run run) {
        synchronized (this) {
            if (run.summary != null) {
                return;
            }
        }
        try {
            // Metadata only; it is not an engine observation of bytes sent/ACKed/verified.
            Files.size(run.request.sourcePath());
            synchronized (this) {
                if (run.summary != null) {
                    return;
                }
            }
            TransferResult result;
            SenderSession completedSession;
            try (SenderSession session = sessionFactory.open(run.request)) {
                completedSession = session;
                synchronized (this) {
                    if (run.summary != null) {
                        return; // Shutdown won the race; try-with-resources closes this new session.
                    }
                    run.session = session;
                    retain(run, session.liveMetricsSnapshot());
                }
                try {
                    result = Objects.requireNonNull(session.send(), "Engine returned no result");
                } finally {
                    // Capture terminal engine evidence while its channel/session is still alive.
                    synchronized (this) {
                        retain(run, session.liveMetricsSnapshot());
                    }
                }
            } finally {
                synchronized (this) {
                    run.session = null;
                }
            }
            String evidenceFailure = completedSession.evidenceFailure();
            boolean success = result.isSuccess() && evidenceFailure == null;
            String message = result.isSuccess() && evidenceFailure != null
                    ? evidenceFailure : result.getMessage();
            finish(run, success, message);
        } catch (SocketTimeoutException e) {
            // The uninstrumented sender can throw here for START or FINISH; do not guess the phase.
            finish(run, false, "Timed out waiting for an engine control response; completion is unconfirmed");
        } catch (IOException | RuntimeException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            finish(run, false, "Transfer failed: " + detail);
        }
    }

    private synchronized void finish(Run run, boolean success, String message) {
        if (run.summary != null) {
            return; // A late engine result must not overwrite a shutdown/interruption outcome.
        }
        TransferError error = success ? null : new TransferError(TRANSFER_FAILED, message);
        run.snapshot = snapshot(run, success ? TransferState.COMPLETED : TransferState.FAILED, error);
        run.summary = new TransferSummary(run.request, run.snapshot,
                success ? IntegrityStatus.VERIFIED : IntegrityStatus.UNCONFIRMED, message);
        activeTransferId = null;
    }

    private TransferSnapshot snapshot(Run run, TransferState state, TransferError error) {
        if (run.lastLiveSnapshot != null) {
            LiveMetricsSnapshot live = run.lastLiveSnapshot;
            return new TransferSnapshot(run.request.transferId(), run.request.transferId(),
                    live.context().getProtocolTransferId(), state, live.capturedAt(),
                    EvidenceSource.REAL, TransferMetrics.fromLive(live), error);
        }
        LiveMetricsSnapshot.LifecycleState lifecycle = switch (state) {
            case RUNNING -> LiveMetricsSnapshot.LifecycleState.NOT_STARTED;
            case COMPLETED -> LiveMetricsSnapshot.LifecycleState.SUCCEEDED;
            case FAILED -> LiveMetricsSnapshot.LifecycleState.FAILED;
        };
        var metrics = TransferMetrics.unavailable(UNAVAILABLE,
                nettransfer.metrics.TransferMetrics.EvidenceSource.REAL, clock.instant(), lifecycle,
                state == TransferState.RUNNING ? null : state == TransferState.COMPLETED,
                error == null ? null : error.message());
        return new TransferSnapshot(run.request.transferId(), run.request.transferId(), null, state,
                metrics.liveSnapshot().capturedAt(), EvidenceSource.REAL, metrics, error);
    }

    private void retain(Run run, LiveMetricsSnapshot observed) {
        if (observed == null) {
            return;
        }
        run.lastLiveSnapshot = observed;
        if (run.summary == null) {
            run.snapshot = snapshot(run, TransferState.RUNNING, null);
        }
    }

    private Run findRun(UUID id) {
        Run run = runs.get(id);
        if (run == null) {
            throw new TransferServiceException(UNKNOWN_TRANSFER, "Unknown transfer/run ID: " + id);
        }
        return run;
    }

    /**
     * Used on normal idle exit, EOF or process shutdown, not as a user cancellation command.
     * Closing the socket unblocks receive; interruption alone cannot do that. Interruption
     * is published immediately in memory; when logging is configured, the unblocked engine
     * then finalizes its own truthful failed endpoint evidence.
     */
    @Override
    public void close() {
        SenderSession session = null;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            if (activeTransferId != null) {
                Run run = runs.get(activeTransferId);
                session = run.session;
                if (session != null) {
                    retain(run, session.liveMetricsSnapshot());
                }
                finish(run, false, "Transfer interrupted because the service closed; completion is unconfirmed");
            }
        }
        try {
            if (session != null) {
                session.close();
            }
        } finally {
            worker.shutdownNow();
        }
    }

    private static SenderSession openEngineSession(TransferRequest request, int initialTimeoutMillis,
                                                   Path loggingRoot, ImpairmentSettings impairment) throws IOException {
        UdpChannel channel = new UdpChannel(impairment);
        try {
            var settings = request.settings();
            SenderEngine engine = new SenderEngine(channel, request.receiver().getAddress(),
                    request.receiver().getPort(), settings.chunkSizeBytes(), settings.windowPackets(),
                    settings.timeoutMillis(), settings.retryLimit(), initialTimeoutMillis,
                    SenderEngine.DEFAULT_START_RETRY_LIMIT, senderContext(request, initialTimeoutMillis));
            EventLogger eventLogger = loggingRoot == null
                    ? null : engine.enableEventLogging(loggingRoot);
            return new SenderSession() {
                @Override
                public TransferResult send() throws IOException {
                    return engine.sendFile(request.sourcePath().toString());
                }

                @Override
                public LiveMetricsSnapshot liveMetricsSnapshot() {
                    return engine.getLiveMetricsSnapshot();
                }

                @Override
                public String evidenceFailure() {
                    if (eventLogger == null) {
                        return null;
                    }
                    if (eventLogger.getLoggingFailure() != null) {
                        return "Sender evidence logging failed: " + eventLogger.getLoggingFailure();
                    }
                    return eventLogger.isFinalized() ? null
                            : "Sender evidence logging did not reach a finalized terminal state";
                }

                @Override
                public void close() {
                    channel.close();
                }
            };
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    static TransferContext senderContext(TransferRequest request, int initialTimeoutMillis) {
        var settings = request.settings();
        TransferConfiguration configuration = TransferConfiguration.builder()
                .chunkSizeBytes((long) settings.chunkSizeBytes())
                .windowBytesRequested(settings.requestedWindowBytes())
                .windowPackets((long) settings.windowPackets())
                .timeoutMs((long) settings.timeoutMillis())
                .retryLimit((long) settings.retryLimit())
                .startHandshakeTimeoutMs((long) initialTimeoutMillis)
                .startRetryLimit((long) SenderEngine.DEFAULT_START_RETRY_LIMIT)
                .finishHandshakeTimeoutMs((long) SenderEngine.DEFAULT_FINISH_HANDSHAKE_TIMEOUT_MS)
                .finishRetryLimit((long) SenderEngine.DEFAULT_FINISH_RETRY_LIMIT)
                .build();
        String applicationId = request.transferId().toString();
        return TransferContext.builder(TransferContext.Endpoint.SENDER)
                .runId(applicationId)
                .applicationTransferId(applicationId)
                .fileAttribution("approved-file:" + request.fileId())
                .configuration(configuration)
                .schemaVersion(MetricsSchema.ENDPOINT_RECORD_SCHEMA_VERSION)
                .metricDefinitionVersion(MetricsSchema.METRIC_DEFINITION_VERSION)
                .evidenceSource(nettransfer.metrics.TransferMetrics.EvidenceSource.REAL)
                .build();
    }

    @FunctionalInterface
    interface SessionFactory {
        SenderSession open(TransferRequest request) throws IOException;
    }

    /** Implementations must allow idempotent close from another thread to release a blocked send. */
    interface SenderSession extends AutoCloseable {
        TransferResult send() throws IOException;

        /** Immutable non-blocking observation; null only for legacy test doubles. */
        default LiveMetricsSnapshot liveMetricsSnapshot() {
            return null;
        }

        /** Null means either persistence was not configured or terminal evidence finalized safely. */
        default String evidenceFailure() {
            return null;
        }

        @Override
        void close();
    }

    private static final class Run {
        private final TransferRequest request;
        private TransferSnapshot snapshot;
        private TransferSummary summary;
        private SenderSession session;
        private LiveMetricsSnapshot lastLiveSnapshot;

        private Run(TransferRequest request) {
            this.request = request;
        }
    }
}
