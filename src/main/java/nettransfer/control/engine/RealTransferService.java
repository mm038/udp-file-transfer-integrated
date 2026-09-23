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
import nettransfer.transfer.SenderEngine;
import nettransfer.transfer.TransferResult;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
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
 * Runs the unchanged blocking sender on one worker. Each run owns its channel.
 * The monitor protects only state publication/reservation, never network or file I/O.
 * No progress, protocol timing, or wire identity is inferred without engine hooks.
 */
public final class RealTransferService implements TransferService, AutoCloseable {
    public static final int DEFAULT_INITIAL_RESPONSE_TIMEOUT_MILLIS = 2000;
    private static final String UNAVAILABLE =
            "The engine exposes no ACK progress or protocol timing observations; missing chunk counts are unavailable";

    private final ExecutorService worker;
    private final Clock clock;
    private final SessionFactory sessionFactory;
    private final Map<UUID, Run> runs = new HashMap<>();
    private UUID activeTransferId;
    private boolean closed;

    public RealTransferService() {
        this(DEFAULT_INITIAL_RESPONSE_TIMEOUT_MILLIS);
    }

    /**
     * This per-attempt START response timeout is separate from the validated DATA timeout.
     * The sender retains its normal retry limit, so the approximate maximum START handshake
     * duration is this timeout multiplied by the total number of attempts.
     */
    public RealTransferService(int initialResponseTimeoutMillis) {
        if (initialResponseTimeoutMillis <= 0) {
            throw new IllegalArgumentException("The initial receive timeout must be finite and positive");
        }
        this.worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "transfer-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.clock = Clock.systemUTC();
        this.sessionFactory = request -> openEngineSession(request, initialResponseTimeoutMillis);
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
        run.snapshot = snapshot(run, TransferState.RUNNING, null, null);
        runs.put(request.transferId(), run);
        activeTransferId = request.transferId();
        TransferStart accepted = new TransferStart(request.transferId(), request.transferId(), null,
                run.snapshot.snapshotAt(), EvidenceSource.REAL);
        try {
            worker.execute(() -> runTransfer(run));
        } catch (RejectedExecutionException e) {
            finish(run, false, null, "The transfer worker could not accept the start request");
            throw new TransferServiceException(TRANSFER_FAILED, run.summary.message());
        }
        return accepted;
    }

    @Override
    public synchronized TransferSnapshot status(UUID transferId) {
        return findRun(transferId).snapshot;
    }

    @Override
    public synchronized TransferSummary summary(UUID runId) {
        // Application transfer and run IDs are identical; the engine's UUID is still unknown.
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
            long fileSize = Files.size(run.request.sourcePath());
            synchronized (this) {
                if (run.summary != null) {
                    return;
                }
                run.fileSizeBytes = fileSize;
                run.snapshot = snapshot(run, TransferState.RUNNING, null, null);
            }
            TransferResult result;
            try (SenderSession session = sessionFactory.open(run.request)) {
                synchronized (this) {
                    if (run.summary != null) {
                        return; // Shutdown won the race; try-with-resources closes this new session.
                    }
                    run.session = session;
                }
                result = Objects.requireNonNull(session.send(), "Engine returned no result");
            } finally {
                synchronized (this) {
                    run.session = null;
                }
            }
            Integer chunks = TransferMetrics.chunkCountFromEngine(result.getTotalChunks());
            finish(run, result.isSuccess(), chunks, result.getMessage());
        } catch (SocketTimeoutException e) {
            // The uninstrumented sender can throw here for START or FINISH; do not guess the phase.
            finish(run, false, null, "Timed out waiting for an engine control response; completion is unconfirmed");
        } catch (IOException | RuntimeException e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            finish(run, false, null, "Transfer failed: " + detail);
        }
    }

    private synchronized void finish(Run run, boolean success, Integer chunks, String message) {
        if (run.summary != null) {
            return; // A late engine result must not overwrite a shutdown/interruption outcome.
        }
        TransferError error = success ? null : new TransferError(TRANSFER_FAILED, message);
        run.snapshot = snapshot(run, success ? TransferState.COMPLETED : TransferState.FAILED, chunks, error);
        run.summary = new TransferSummary(run.request, run.snapshot,
                success ? IntegrityStatus.VERIFIED : IntegrityStatus.UNCONFIRMED, message);
        activeTransferId = null;
    }

    private TransferSnapshot snapshot(Run run, TransferState state, Integer chunks, TransferError error) {
        TransferMetrics metrics = new TransferMetrics(run.fileSizeBytes, null, null, chunks, UNAVAILABLE);
        return new TransferSnapshot(run.request.transferId(), run.request.transferId(), null, state,
                clock.instant(), EvidenceSource.REAL, metrics, error);
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
     * evidence is kept in memory, not persisted as Person 2's future experiment log.
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
                finish(run, false, null, "Transfer interrupted because the service closed; completion is unconfirmed");
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

    private static SenderSession openEngineSession(TransferRequest request, int initialTimeoutMillis)
            throws IOException {
        UdpChannel channel = new UdpChannel();
        try {
            var settings = request.settings();
            SenderEngine engine = new SenderEngine(channel, request.receiver().getAddress(),
                    request.receiver().getPort(), settings.chunkSizeBytes(), settings.windowPackets(),
                    settings.timeoutMillis(), settings.retryLimit(), initialTimeoutMillis,
                    SenderEngine.DEFAULT_START_RETRY_LIMIT);
            return new SenderSession() {
                @Override
                public TransferResult send() throws IOException {
                    return engine.sendFile(request.sourcePath().toString());
                }

                @Override
                public void close() {
                    channel.close();
                }
            };
        } catch (RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    @FunctionalInterface
    interface SessionFactory {
        SenderSession open(TransferRequest request) throws IOException;
    }

    /** Implementations must allow idempotent close from another thread to release a blocked send. */
    interface SenderSession extends AutoCloseable {
        TransferResult send() throws IOException;

        @Override
        void close();
    }

    private static final class Run {
        private final TransferRequest request;
        private Long fileSizeBytes;
        private TransferSnapshot snapshot;
        private TransferSummary summary;
        private SenderSession session;

        private Run(TransferRequest request) {
            this.request = request;
        }
    }
}
