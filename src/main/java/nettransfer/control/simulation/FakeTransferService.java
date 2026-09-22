package nettransfer.control.simulation;

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

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static nettransfer.control.TransferError.Code.INTEGRITY_FAILED;
import static nettransfer.control.TransferError.Code.INVALID_PARAMETER;
import static nettransfer.control.TransferError.Code.SUMMARY_NOT_READY;
import static nettransfer.control.TransferError.Code.TRANSFER_BUSY;
import static nettransfer.control.TransferError.Code.TRANSFER_FAILED;
import static nettransfer.control.TransferError.Code.UNKNOWN_TRANSFER;

/**
 * In-memory synthetic service: no files, sockets, workers, sleeps or GPT calls.
 * start exposes the first progress fixture; each explicit advance exposes the
 * next fixture, then the predefined outcome. Reading status never advances it.
 * A scenario is reused for later runs; previous snapshots/summaries stay frozen.
 */
public final class FakeTransferService implements TransferService {
    private final Scenario scenario;
    private final Clock clock;
    private final Map<UUID, Run> runs = new HashMap<>();
    private UUID activeTransferId;

    public FakeTransferService(Scenario scenario) {
        this(scenario, Clock.systemUTC());
    }

    public FakeTransferService(Scenario scenario, Clock clock) {
        this.scenario = Objects.requireNonNull(scenario, "scenario");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized TransferStart start(TransferRequest request) {
        Objects.requireNonNull(request, "request");
        if (activeTransferId != null) {
            throw new TransferServiceException(TRANSFER_BUSY, "A transfer is already running");
        }
        if (runs.containsKey(request.transferId())) {
            throw new TransferServiceException(INVALID_PARAMETER, "Transfer ID has already been used");
        }
        TransferSnapshot initial = snapshot(request, TransferState.RUNNING, scenario.progress().get(0), null);
        runs.put(request.transferId(), new Run(request, initial));
        activeTransferId = request.transferId();
        return new TransferStart(initial.transferId(), initial.runId(), null,
                initial.snapshotAt(), EvidenceSource.SYNTHETIC);
    }

    @Override
    public synchronized TransferSnapshot status(UUID transferId) {
        return findRun(transferId).snapshot;
    }

    @Override
    public synchronized TransferSummary summary(UUID runId) {
        // In this first contract runId equals the application transferId.
        Run run = findRun(runId);
        if (run.summary == null) {
            throw new TransferServiceException(SUMMARY_NOT_READY, "Transfer is still running");
        }
        return run.summary;
    }

    /** Test/demo control only; deliberately absent from TransferService. Terminal calls are no-ops. */
    public synchronized TransferSnapshot advance(UUID transferId) {
        Run run = findRun(transferId);
        if (run.summary != null) {
            return run.snapshot;
        }
        run.progressIndex++;
        if (run.progressIndex < scenario.progress().size()) {
            run.snapshot = snapshot(run.request, TransferState.RUNNING,
                    scenario.progress().get(run.progressIndex), null);
        } else {
            TransferState terminal = scenario.failure() == null ? TransferState.COMPLETED : TransferState.FAILED;
            run.snapshot = snapshot(run.request, terminal, scenario.finalMetrics(), scenario.failure());
            run.summary = new TransferSummary(run.request, run.snapshot, scenario.integrity(), scenario.message());
            activeTransferId = null;
        }
        return run.snapshot;
    }

    private Run findRun(UUID id) {
        Run run = runs.get(id);
        if (run == null) {
            throw new TransferServiceException(UNKNOWN_TRANSFER, "Unknown transfer/run ID: " + id);
        }
        return run;
    }

    private TransferSnapshot snapshot(TransferRequest request, TransferState state,
                                      TransferMetrics metrics, TransferError error) {
        return new TransferSnapshot(request.transferId(), request.transferId(), null,
                state, clock.instant(), EvidenceSource.SYNTHETIC, metrics, error);
    }

    private static final class Run {
        private final TransferRequest request;
        private int progressIndex;
        private TransferSnapshot snapshot;
        private TransferSummary summary;

        private Run(TransferRequest request, TransferSnapshot snapshot) {
            this.request = request;
            this.snapshot = snapshot;
        }
    }

    /** Explicit fixture evidence; no metrics are calculated or filled in by the service. */
    public record Scenario(List<TransferMetrics> progress, TransferMetrics finalMetrics,
                           IntegrityStatus integrity, TransferError failure, String message) {
        public Scenario {
            progress = List.copyOf(progress);
            if (progress.isEmpty()) {
                throw new IllegalArgumentException("At least one RUNNING fixture is required");
            }
            Objects.requireNonNull(finalMetrics, "finalMetrics");
            Objects.requireNonNull(integrity, "integrity");
            Objects.requireNonNull(message, "message");
            if (failure == null && integrity != IntegrityStatus.VERIFIED) {
                throw new IllegalArgumentException("Synthetic success requires verified integrity");
            }
        }

        public static Scenario success(List<TransferMetrics> progress, TransferMetrics finalMetrics) {
            return new Scenario(progress, finalMetrics, IntegrityStatus.VERIFIED, null,
                    "Synthetic transfer completed and verified");
        }

        public static Scenario engineFailure(List<TransferMetrics> progress, TransferMetrics finalMetrics) {
            String message = "Synthetic engine failure: retry limit exceeded";
            return new Scenario(progress, finalMetrics, IntegrityStatus.UNCONFIRMED,
                    new TransferError(TRANSFER_FAILED, message), message);
        }

        public static Scenario integrityFailure(List<TransferMetrics> progress, TransferMetrics finalMetrics) {
            String message = "Synthetic integrity failure: SHA-256 mismatch";
            return new Scenario(progress, finalMetrics, IntegrityStatus.FAILED,
                    new TransferError(INTEGRITY_FAILED, message), message);
        }
    }
}
