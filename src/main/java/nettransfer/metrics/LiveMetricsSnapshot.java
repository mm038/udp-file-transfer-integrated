package nettransfer.metrics;

import java.time.Instant;
import java.util.Objects;

/** Immutable atomic view of one endpoint's current transfer observations. */
public record LiveMetricsSnapshot(
        TransferContext context,
        TransferMetrics metrics,
        MetricsCollector.EndpointEmissionObservations endpointEmission,
        LifecycleState lifecycleState,
        Instant capturedAt,
        Double liveElapsedTimeSec,
        Long senderAcknowledgedPayloadBytes,
        Double senderAcknowledgedRateMbps,
        MetricsCollector.SenderObservations senderObservations,
        MetricsCollector.ReceiverObservations receiverObservations) {

    public LiveMetricsSnapshot {
        Objects.requireNonNull(context, "context is required");
        Objects.requireNonNull(metrics, "metrics are required");
        Objects.requireNonNull(endpointEmission, "endpoint emission observation is required");
        Objects.requireNonNull(lifecycleState, "lifecycle state is required");
        Objects.requireNonNull(capturedAt, "capture time is required");
    }

    public boolean isTerminal() {
        return lifecycleState == LifecycleState.SUCCEEDED
                || lifecycleState == LifecycleState.FAILED;
    }

    public boolean isProvisional() {
        return !isTerminal();
    }

    public enum LifecycleState {
        NOT_STARTED,
        AWAITING_START,
        TRANSFERRING,
        AWAITING_FINISH,
        VERIFYING,
        COMPLETION_RECOVERY,
        SUCCEEDED,
        FAILED
    }
}
