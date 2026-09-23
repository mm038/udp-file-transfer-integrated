package nettransfer.control;

import nettransfer.metrics.LiveMetricsSnapshot;
import nettransfer.metrics.MetricsCollector;
import nettransfer.metrics.TransferContext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Compatibility view over Person 2's authoritative live metrics snapshot.
 *
 * <p>This type owns no independent measurements. New code should use
 * {@link #liveSnapshot()} and {@link #authoritativeMetrics()} directly. The
 * convenience accessors remain for the existing control/CLI contract while it
 * migrates to the complete metrics vocabulary.
 */
public final class TransferMetrics {
    private final LiveMetricsSnapshot liveSnapshot;

    public TransferMetrics(LiveMetricsSnapshot liveSnapshot) {
        this.liveSnapshot = Objects.requireNonNull(liveSnapshot, "live metrics snapshot is required");
    }

    public static TransferMetrics fromLive(LiveMetricsSnapshot liveSnapshot) {
        return new TransferMetrics(liveSnapshot);
    }

    /** Explicit synthetic fixture with sender-local progress; never used for a REAL status. */
    public static TransferMetrics synthetic(Long fileSizeBytes, Long acknowledgedPayloadBytes,
                                            Long elapsedMillis) {
        if ((fileSizeBytes != null && fileSizeBytes < 0)
                || (acknowledgedPayloadBytes != null && acknowledgedPayloadBytes < 0)
                || (elapsedMillis != null && elapsedMillis < 0)
                || (fileSizeBytes != null && acknowledgedPayloadBytes != null
                && acknowledgedPayloadBytes > fileSizeBytes)) {
            throw new IllegalArgumentException("Synthetic progress values must be consistent and nonnegative");
        }
        Instant capturedAt = Instant.EPOCH;
        Map<String, String> reasons = unavailableReasons(
                "Synthetic fixture does not supply this observation");
        if (fileSizeBytes != null) {
            reasons.remove("file_size_bytes");
        }
        if (elapsedMillis != null) {
            reasons.remove("transfer_time_sec");
        }
        TransferContext context = TransferContext.builder(TransferContext.Endpoint.SENDER)
                .evidenceSource(nettransfer.metrics.TransferMetrics.EvidenceSource.SYNTHETIC)
                .fileSizeBytes(fileSizeBytes)
                .unavailableReasons(reasons)
                .build();
        var metrics = context.newMetricsBuilder()
                .captureTimestamp(capturedAt)
                .transferTimeSec(elapsedMillis == null ? null : elapsedMillis / 1000.0)
                .build();
        Double elapsedSeconds = elapsedMillis == null ? null : elapsedMillis / 1000.0;
        return new TransferMetrics(new LiveMetricsSnapshot(
                context, metrics, new MetricsCollector.EndpointEmissionObservations(null, false),
                LiveMetricsSnapshot.LifecycleState.TRANSFERRING, capturedAt,
                elapsedSeconds, acknowledgedPayloadBytes, null,
                new MetricsCollector.SenderObservations(0, false, 0, 0, false, 0), null));
    }

    public static TransferMetrics unavailable(String reason) {
        return unavailable(reason, nettransfer.metrics.TransferMetrics.EvidenceSource.SYNTHETIC);
    }

    public static TransferMetrics unavailable(
            String reason, nettransfer.metrics.TransferMetrics.EvidenceSource source) {
        return unavailable(reason, source, Instant.EPOCH,
                LiveMetricsSnapshot.LifecycleState.NOT_STARTED, null, null);
    }

    /** Builds an explicitly unavailable status without substituting zero measurements. */
    public static TransferMetrics unavailable(
            String reason, nettransfer.metrics.TransferMetrics.EvidenceSource source,
            Instant capturedAt, LiveMetricsSnapshot.LifecycleState lifecycleState,
            Boolean transferSuccess, String failureReason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Unavailable metrics need a reason");
        }
        Objects.requireNonNull(capturedAt, "capture time is required");
        Objects.requireNonNull(lifecycleState, "lifecycle state is required");
        TransferContext context = TransferContext.builder(TransferContext.Endpoint.SENDER)
                .evidenceSource(source)
                .unavailableReasons(unavailableReasons(reason))
                .build();
        Map<String, String> reasons = new LinkedHashMap<>(context.getUnavailableReasons());
        if (transferSuccess != null) {
            reasons.remove("transfer_success");
        }
        var metrics = context.newMetricsBuilder()
                .captureTimestamp(capturedAt)
                .finalizationTimestamp(lifecycleState == LiveMetricsSnapshot.LifecycleState.SUCCEEDED
                        || lifecycleState == LiveMetricsSnapshot.LifecycleState.FAILED
                        ? capturedAt : null)
                .transferSuccess(transferSuccess)
                .failureReason(failureReason)
                .unavailableReasons(reasons)
                .build();
        return new TransferMetrics(new LiveMetricsSnapshot(
                context, metrics, new MetricsCollector.EndpointEmissionObservations(null, false),
                lifecycleState, capturedAt,
                null, null, null,
                new MetricsCollector.SenderObservations(0, false, 0, 0, false, 0), null));
    }

    /**
     * Re-times an explicit fixture without changing any of its observations.
     * This is used only by the deterministic synthetic service; real snapshots
     * always retain the capture instant supplied by the engine.
     */
    public TransferMetrics asSyntheticSnapshot(Instant capturedAt,
                                               LiveMetricsSnapshot.LifecycleState lifecycleState,
                                               Boolean transferSuccess,
                                               String failureReason) {
        if (authoritativeMetrics().getEvidenceSource()
                != nettransfer.metrics.TransferMetrics.EvidenceSource.SYNTHETIC) {
            throw new IllegalStateException("Only synthetic fixtures may be re-timed");
        }
        Objects.requireNonNull(capturedAt, "capture time is required");
        Objects.requireNonNull(lifecycleState, "lifecycle state is required");
        var reasons = new LinkedHashMap<>(authoritativeMetrics().getUnavailableReasons());
        if (transferSuccess != null) {
            reasons.remove("transfer_success");
        }
        var metrics = authoritativeMetrics().toBuilder()
                .captureTimestamp(capturedAt)
                .finalizationTimestamp(lifecycleState == LiveMetricsSnapshot.LifecycleState.SUCCEEDED
                        || lifecycleState == LiveMetricsSnapshot.LifecycleState.FAILED
                        ? capturedAt : null)
                .transferSuccess(transferSuccess)
                .failureReason(failureReason)
                .unavailableReasons(reasons)
                .build();
        return new TransferMetrics(new LiveMetricsSnapshot(
                liveSnapshot.context(), metrics, liveSnapshot.endpointEmission(), lifecycleState,
                capturedAt, liveSnapshot.liveElapsedTimeSec(),
                liveSnapshot.senderAcknowledgedPayloadBytes(),
                liveSnapshot.senderAcknowledgedRateMbps(), liveSnapshot.senderObservations(),
                liveSnapshot.receiverObservations()));
    }

    public LiveMetricsSnapshot liveSnapshot() {
        return liveSnapshot;
    }

    public nettransfer.metrics.TransferMetrics authoritativeMetrics() {
        return liveSnapshot.metrics();
    }

    public Long fileSizeBytes() {
        return liveSnapshot.metrics().getFileSizeBytes();
    }

    public Long uniquePayloadBytesAcked() {
        return liveSnapshot.senderAcknowledgedPayloadBytes();
    }

    public Long elapsedMillis() {
        Double seconds = liveSnapshot.liveElapsedTimeSec();
        return seconds == null ? null : Math.round(seconds * 1000.0);
    }

    /** The authoritative metrics schema has no engine-result chunk-count field. */
    public Integer totalChunks() {
        return null;
    }

    public String unavailableReason() {
        return liveSnapshot.metrics().getUnavailableReasons().values().stream()
                .distinct().findFirst().orElse(null);
    }

    private static Map<String, String> unavailableReasons(String reason) {
        Map<String, String> reasons = new LinkedHashMap<>();
        for (String field : new String[] {
                "file_size_bytes", "payload_bytes_delivered", "transfer_time_sec",
                "throughput_mbps", "transfer_success", "integrity_verified",
                "packets_sent", "packets_received", "packets_dropped", "retransmissions",
                "acks_received", "packets_acked", "packets_timed_out", "packets_duplicated",
                "retransmission_ratio", "udp_payload_bytes_emitted", "protocol_overhead_bytes",
                "protocol_overhead_ratio", "rtt_sample_count", "rtt_mean_ms", "rtt_p95_ms"
        }) {
            reasons.put(field, reason);
        }
        return reasons;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TransferMetrics that && liveSnapshot.equals(that.liveSnapshot);
    }

    @Override
    public int hashCode() {
        return liveSnapshot.hashCode();
    }
}
