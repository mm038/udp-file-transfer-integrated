package nettransfer.metrics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Thread-safe, in-memory observations for one endpoint transfer context.
 *
 * <p>The engine remains responsible for every protocol and reliability decision. This collector
 * only records decisions and attempts at their existing execution points.
 */
public final class MetricsCollector {
    private final LongSupplier monotonicNanos;
    private TransferContext context;

    private boolean countersObserved;
    private long packetsSent;
    private long retransmissions;
    private long acksReceived;
    private long packetsAcked;
    private long packetsTimedOut;

    private long startAttempts;
    private boolean startAcknowledged;
    private long recoveryRounds;
    private long finishAttempts;
    private boolean finishAcknowledged;
    private long errorsObserved;

    private Long startedAtNanos;
    private Long terminalAtNanos;
    private Boolean transferSuccess;
    private String failureReason;
    private boolean rttSamplingActive;
    private boolean impairmentObservationActive;
    private long impairmentDrops;
    private final Map<Integer, RttState> outstandingRtt = new HashMap<>();
    private final List<Double> eligibleRttMillis = new ArrayList<>();
    private MetricsCalculator.RttStatistics cachedRttStatistics;
    private boolean rttStatisticsDirty = true;
    private boolean acknowledgedBytesInitialized;
    private boolean acknowledgedBytesUnavailable;
    private long senderAcknowledgedPayloadBytes;

    private boolean emissionAccountingInitialized;
    private long endpointUdpPayloadBytesEmitted;
    private boolean emissionAccountingComplete;
    private LiveMetricsSnapshot.LifecycleState lifecycleState =
            LiveMetricsSnapshot.LifecycleState.NOT_STARTED;
    private Instant terminalWallClock;

    private boolean receiverMeasurementActive;
    private long packetsReceived;
    private long packetsDuplicated;
    private long payloadBytesDelivered;
    private Boolean integrityVerified;
    private long receiverStartArrivals;
    private long receiverStartRejections;
    private boolean receiverStartAccepted;
    private String establishedPeerAddress;
    private Integer establishedPeerPort;
    private long dataValidationFailures;
    private long dataAccepted;
    private long dataOutOfOrderDiscarded;
    private long dataAckAttempts;
    private long finishArrivals;
    private long finishAckAttempts;
    private long duplicateFinishArrivals;
    private long cachedFinishAckResendAttempts;
    private boolean completionGraceExpired;
    private Boolean receiverLocalSuccess;
    private String receiverTerminalReason;
    private long receiverErrorsObserved;

    public MetricsCollector(TransferContext context) {
        this(context, System::nanoTime);
    }

    MetricsCollector(TransferContext context, LongSupplier monotonicNanos) {
        this.context = requireContext(context);
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonic clock is required");
    }

    /** Updates file/protocol observations while preserving this collector's application identity. */
    public synchronized void updateContext(TransferContext updated) {
        updated = requireContext(updated);
        if (updated.getEndpoint() != context.getEndpoint()) {
            throw new IllegalArgumentException("updated context belongs to a different endpoint");
        }
        if (!Objects.equals(context.getRunId(), updated.getRunId())
                || !Objects.equals(context.getExperimentId(), updated.getExperimentId())
                || !Objects.equals(context.getApplicationTransferId(), updated.getApplicationTransferId())) {
            throw new IllegalArgumentException("updated context belongs to a different transfer identity");
        }
        if (context.getProtocolTransferId() != null
                && updated.getProtocolTransferId() != null
                && !context.getProtocolTransferId().equals(updated.getProtocolTransferId())) {
            throw new IllegalArgumentException("updated context has a conflicting protocol transfer ID");
        }
        context = updated;
    }

    /** Counting point immediately before an actual START send call. */
    public synchronized void observeStartAttempt() {
        requireEndpoint(TransferContext.Endpoint.SENDER);
        if (startedAtNanos == null) {
            startedAtNanos = monotonicNanos.getAsLong();
            countersObserved = true;
        }
        startAttempts++;
    }

    public synchronized void observeStartAcknowledged() {
        requireEndpoint(TransferContext.Endpoint.SENDER);
        startAcknowledged = true;
        lifecycleState = LiveMetricsSnapshot.LifecycleState.TRANSFERRING;
    }

    /** Counting point immediately before a DATA attempt reaches the channel/impairment boundary. */
    public synchronized void observeDataSendAttempt(boolean retransmission) {
        requireEndpoint(TransferContext.Endpoint.SENDER);
        ensureCountersObserved();
        packetsSent++;
        if (retransmission) {
            retransmissions++;
        }
    }

    /** Records the monotonic timestamp immediately after a successful original DATA send. */
    public synchronized void observeOriginalDataEmission(int sequenceNumber) {
        observeOriginalDataEmission(sequenceNumber, null);
    }

    /** Also retains the actual payload length for exact sender-confirmed byte progress. */
    public synchronized void observeOriginalDataEmission(int sequenceNumber, int payloadLength) {
        observeOriginalDataEmission(sequenceNumber, Integer.valueOf(payloadLength));
    }

    private void observeOriginalDataEmission(int sequenceNumber, Integer payloadLength) {
        requireEndpoint(TransferContext.Endpoint.SENDER);
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException("sequenceNumber must be non-negative");
        }
        if (payloadLength != null && payloadLength < 0) {
            throw new IllegalArgumentException("payloadLength must be non-negative");
        }
        rttSamplingActive = true;
        if (payloadLength == null) {
            acknowledgedBytesUnavailable = true;
        } else {
            acknowledgedBytesInitialized = true;
        }
        outstandingRtt.putIfAbsent(sequenceNumber,
                new RttState(monotonicNanos.getAsLong(), false, payloadLength));
    }

    /** Makes a sequence permanently RTT-ineligible after a successful retransmission. */
    public synchronized void observeRetransmittedDataEmission(int sequenceNumber) {
        requireEndpoint(TransferContext.Endpoint.SENDER);
        RttState state = outstandingRtt.get(sequenceNumber);
        if (state != null) {
            outstandingRtt.put(sequenceNumber,
                    new RttState(state.originalSentAtNanos(), true, state.payloadLength()));
        }
    }

    /**
     * Observes validated cumulative ACK progress. Exactly-one progress may produce one sample;
     * multi-sequence progress is conservatively excluded. All acknowledged state is discarded.
     */
    public synchronized void observeDataAckProgress(int oldBase, int newBase) {
        requireEndpoint(TransferContext.Endpoint.SENDER);
        if (oldBase < 0 || newBase < oldBase) {
            throw new IllegalArgumentException("ACK progress bounds are invalid");
        }
        long acknowledged = (long) newBase - oldBase;
        long acknowledgedAt = monotonicNanos.getAsLong();
        if (acknowledged == 1) {
            RttState state = outstandingRtt.get(oldBase);
            if (state != null && !state.retransmitted()) {
                double millis = MetricsCalculator.transferDurationSeconds(
                        state.originalSentAtNanos(), acknowledgedAt) * 1_000.0;
                eligibleRttMillis.add(millis);
                rttStatisticsDirty = true;
            }
        }
        for (int sequence = oldBase; sequence < newBase; sequence++) {
            RttState state = outstandingRtt.remove(sequence);
            if (state == null || state.payloadLength() == null) {
                acknowledgedBytesUnavailable = true;
            } else if (!acknowledgedBytesUnavailable) {
                try {
                    senderAcknowledgedPayloadBytes = Math.addExact(
                            senderAcknowledgedPayloadBytes, state.payloadLength());
                } catch (ArithmeticException exception) {
                    acknowledgedBytesUnavailable = true;
                }
            }
        }
    }

    public synchronized void observeEndpointAwaitingStart() {
        if (lifecycleState != LiveMetricsSnapshot.LifecycleState.NOT_STARTED) {
            throw new IllegalStateException("endpoint transfer has already started");
        }
        lifecycleState = LiveMetricsSnapshot.LifecycleState.AWAITING_START;
    }

    public synchronized void observeSenderAwaitingFinish() {
        requireEndpoint(TransferContext.Endpoint.SENDER);
        lifecycleState = LiveMetricsSnapshot.LifecycleState.AWAITING_FINISH;
    }

    public synchronized void observeReceiverCompletionRecovery() {
        requireEndpoint(TransferContext.Endpoint.RECEIVER);
        lifecycleState = LiveMetricsSnapshot.LifecycleState.COMPLETION_RECOVERY;
    }

    /** Starts endpoint-local successful socket-send byte accounting at zero. */
    public synchronized void beginEndpointEmissionAccounting() {
        if (emissionAccountingInitialized) {
            throw new IllegalStateException("endpoint emission accounting is already initialized");
        }
        emissionAccountingInitialized = true;
    }

    /** Counting point invoked by UdpChannel only after a successful socket send. */
    public synchronized void observeUdpPayloadEmitted(long encodedPayloadBytes) {
        if (!emissionAccountingInitialized || emissionAccountingComplete) {
            throw new IllegalStateException("endpoint emission accounting is not active");
        }
        if (encodedPayloadBytes < 0) {
            throw new IllegalArgumentException("encodedPayloadBytes must be non-negative");
        }
        try {
            endpointUdpPayloadBytesEmitted = Math.addExact(
                    endpointUdpPayloadBytesEmitted, encodedPayloadBytes);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("endpoint UDP payload byte counter overflow", exception);
        }
    }

    public synchronized void completeEndpointEmissionAccounting() {
        if (emissionAccountingInitialized) {
            emissionAccountingComplete = true;
        }
    }

    /** Counts a decoded DATA-ACK arrival attributable to the active peer and protocol UUID. */
    public synchronized void observeDataAckArrival() {
        requireEndpoint(TransferContext.Endpoint.SENDER);
        ensureCountersObserved();
        acksReceived++;
    }

    /** Records the existing SenderWindow base advance; duplicate/stale ACKs pass zero. */
    public synchronized void observePacketsAcknowledged(long newlyAcknowledged) {
        requireEndpoint(TransferContext.Endpoint.SENDER);
        if (newlyAcknowledged < 0) {
            throw new IllegalArgumentException("newlyAcknowledged must be non-negative");
        }
        ensureCountersObserved();
        packetsAcked += newlyAcknowledged;
    }

    /** Counts one oldest-outstanding DATA deadline trigger, including retry-limit failure. */
    public synchronized void observeDataTimeout() {
        requireEndpoint(TransferContext.Endpoint.SENDER);
        ensureCountersObserved();
        packetsTimedOut++;
    }

    public synchronized void observeRecoveryDecision() {
        requireEndpoint(TransferContext.Endpoint.SENDER);
        recoveryRounds++;
    }

    public synchronized void observeFinishAttempt() {
        requireEndpoint(TransferContext.Endpoint.SENDER);
        finishAttempts++;
    }

    public synchronized void observeFinishAcknowledged() {
        requireEndpoint(TransferContext.Endpoint.SENDER);
        finishAcknowledged = true;
    }

    public synchronized void observeError() {
        requireEndpoint(TransferContext.Endpoint.SENDER);
        errorsObserved++;
    }

    /** Records the first trustworthy terminal sender decision and freezes its end timestamp. */
    public synchronized void observeTerminalOutcome(boolean success, String actualFailureReason) {
        requireEndpoint(TransferContext.Endpoint.SENDER);
        if (terminalAtNanos != null) {
            return;
        }
        terminalAtNanos = monotonicNanos.getAsLong();
        transferSuccess = success;
        failureReason = success ? null : actualFailureReason;
        outstandingRtt.clear();
        lifecycleState = success
                ? LiveMetricsSnapshot.LifecycleState.SUCCEEDED
                : LiveMetricsSnapshot.LifecycleState.FAILED;
        terminalWallClock = Instant.now();
        if (emissionAccountingInitialized) {
            emissionAccountingComplete = true;
        }
    }

    /** Returns an immutable provisional or terminal B1 metrics snapshot. */
    public synchronized TransferMetrics snapshot() {
        return metricsSnapshot(Instant.now());
    }

    /** Builds metrics, emissions, lifecycle, and progress under one collector lock. */
    public synchronized LiveMetricsSnapshot liveSnapshot() {
        Instant capturedAt = Instant.now();
        TransferMetrics metrics = metricsSnapshot(capturedAt);
        Double elapsed = liveElapsedTimeSeconds();
        Long acknowledgedBytes = acknowledgedPayloadBytes();
        Double acknowledgedRate = context.getEndpoint() == TransferContext.Endpoint.SENDER
                ? MetricsCalculator.throughputMbps(acknowledgedBytes, elapsed)
                : null;
        return new LiveMetricsSnapshot(
                context, metrics, endpointEmissionObservation(), lifecycleState, capturedAt,
                elapsed, acknowledgedBytes, acknowledgedRate,
                context.getEndpoint() == TransferContext.Endpoint.SENDER ? senderObservation() : null,
                context.getEndpoint() == TransferContext.Endpoint.RECEIVER ? receiverObservation() : null);
    }

    private TransferMetrics metricsSnapshot(Instant capturedAt) {
        return context.getEndpoint() == TransferContext.Endpoint.SENDER
                ? senderSnapshot(capturedAt)
                : receiverSnapshot(capturedAt);
    }

    private TransferMetrics senderSnapshot(Instant capturedAt) {
        TransferMetrics.Builder metrics = context.newMetricsBuilder()
                .captureTimestamp(capturedAt)
                .finalizationTimestamp(terminalWallClock);

        if (countersObserved) {
            metrics.packetsSent(packetsSent)
                    .retransmissions(retransmissions)
                    .acksReceived(acksReceived)
                    .packetsAcked(packetsAcked)
                    .packetsTimedOut(packetsTimedOut)
                    .retransmissionRatio(
                            MetricsCalculator.retransmissionRatio(retransmissions, packetsSent));
            if (packetsSent == 0) {
                metrics.unavailableReason(
                        "retransmission_ratio", "no DATA send attempts were observed");
            }
        } else {
            metrics.unavailableReason("packets_sent", "sender observation has not started")
                    .unavailableReason("retransmissions", "sender observation has not started")
                    .unavailableReason("acks_received", "sender observation has not started")
                    .unavailableReason("packets_acked", "sender observation has not started")
                    .unavailableReason("packets_timed_out", "sender observation has not started")
                    .unavailableReason("retransmission_ratio", "sender observation has not started");
        }

        if (startedAtNanos != null && terminalAtNanos != null) {
            metrics.transferTimeSec(
                    MetricsCalculator.transferDurationSeconds(startedAtNanos, terminalAtNanos));
        } else {
            metrics.unavailableReason(
                    "transfer_time_sec",
                    startedAtNanos == null
                            ? "first START attempt was not observed"
                            : "terminal sender decision has not occurred");
        }

        metrics.transferSuccess(transferSuccess).failureReason(failureReason);
        if (transferSuccess == null) {
            metrics.unavailableReason("transfer_success", "terminal sender decision has not occurred");
        }

        if (rttSamplingActive) {
            MetricsCalculator.RttStatistics statistics = currentRttStatistics();
            metrics.rttSampleCount(statistics.sampleCount())
                    .rttMeanMs(statistics.meanMs())
                    .rttP95Ms(statistics.p95Ms());
            if (statistics.sampleCount() == 0) {
                metrics.unavailableReason("rtt_mean_ms", "RTT sampling produced no eligible samples")
                        .unavailableReason("rtt_p95_ms", "RTT sampling produced no eligible samples");
            }
        } else {
            metrics.unavailableReason("rtt_sample_count", "no successful original DATA emission was observed")
                    .unavailableReason("rtt_mean_ms", "RTT sampling was not initialized")
                    .unavailableReason("rtt_p95_ms", "RTT sampling was not initialized");
        }

        addDeferredReasons(metrics);
        if (impairmentObservationActive) {
            metrics.unavailableReason("packets_dropped",
                    "receive-delivery DATA drops are observed at the receiver; receiver evidence is required");
        }
        return metrics.build();
    }

    /** Marks a parsed START arrival before the receiver decides whether to accept it. */
    public synchronized void observeReceiverStartArrival() {
        requireEndpoint(TransferContext.Endpoint.RECEIVER);
        receiverStartArrivals++;
    }

    /** Starts scoped receive-delivery observation; configured probability is not a drop count. */
    public synchronized void beginImpairmentObservation() {
        if (context.getConfiguration() == null
                || !"RECEIVE_DELIVERY_V1".equals(context.getConfiguration().getImpairmentMechanism())) {
            throw new IllegalStateException("receive-delivery impairment is not configured");
        }
        if (impairmentObservationActive) {
            throw new IllegalStateException("impairment observation already started");
        }
        impairmentObservationActive = true;
    }

    /** One valid peer/UUID DATA datagram discarded before delivery to the receiver engine. */
    public synchronized void observeImpairmentDrop() {
        requireEndpoint(TransferContext.Endpoint.RECEIVER);
        if (!impairmentObservationActive) {
            throw new IllegalStateException("impairment observation has not started");
        }
        impairmentDrops++;
    }

    /** Activates receiver DATA measurements after START has been validated and accepted. */
    public synchronized void observeReceiverStartAccepted(String peerAddress, int peerPort) {
        requireEndpoint(TransferContext.Endpoint.RECEIVER);
        receiverMeasurementActive = true;
        receiverStartAccepted = true;
        establishedPeerAddress = Objects.requireNonNull(peerAddress, "peer address is required");
        establishedPeerPort = peerPort;
        lifecycleState = LiveMetricsSnapshot.LifecycleState.TRANSFERRING;
    }

    public synchronized void observeReceiverStartRejected() {
        requireEndpoint(TransferContext.Endpoint.RECEIVER);
        receiverStartRejections++;
    }

    /** Counts one fully validated DATA arrival attributable to the active peer and UUID. */
    public synchronized void observeReceiverDataArrival() {
        requireReceiverMeasurement();
        packetsReceived++;
    }

    /** Records attributable active-transfer DATA rejected for framing, length, sequence or CRC. */
    public synchronized void observeReceiverDataValidationFailure() {
        requireReceiverMeasurement();
        dataValidationFailures++;
    }

    public synchronized void observeReceiverDataAccepted() {
        requireReceiverMeasurement();
        dataAccepted++;
    }

    public synchronized void observeReceiverDataDuplicate() {
        requireReceiverMeasurement();
        packetsDuplicated++;
    }

    public synchronized void observeReceiverDataOutOfOrderDiscarded() {
        requireReceiverMeasurement();
        dataOutOfOrderDiscarded++;
    }

    /** Counting point after a unique payload write has completed successfully. */
    public synchronized void observeReceiverPayloadWritten(long bytes) {
        requireReceiverMeasurement();
        if (bytes < 0) {
            throw new IllegalArgumentException("written payload bytes must be non-negative");
        }
        payloadBytesDelivered += bytes;
    }

    public synchronized void observeReceiverDataAckAttempt() {
        requireReceiverMeasurement();
        dataAckAttempts++;
    }

    public synchronized void observeReceiverFinishArrival() {
        requireReceiverMeasurement();
        finishArrivals++;
        lifecycleState = LiveMetricsSnapshot.LifecycleState.VERIFYING;
    }

    public synchronized void observeReceiverIntegrityVerification(boolean verified) {
        requireReceiverMeasurement();
        if (integrityVerified != null && integrityVerified != verified) {
            throw new IllegalStateException("receiver integrity result is already established");
        }
        integrityVerified = verified;
    }

    public synchronized void observeReceiverFinishAckAttempt() {
        requireReceiverMeasurement();
        finishAckAttempts++;
    }

    public synchronized void observeReceiverDuplicateFinishArrival() {
        requireReceiverMeasurement();
        duplicateFinishArrivals++;
    }

    public synchronized void observeReceiverCachedFinishAckResendAttempt() {
        requireReceiverMeasurement();
        cachedFinishAckResendAttempts++;
    }

    public synchronized void observeReceiverCompletionGraceExpired() {
        requireReceiverMeasurement();
        completionGraceExpired = true;
    }

    /** Records the receiver-local result without redefining sender-confirmed transfer_success. */
    public synchronized void observeReceiverTerminalOutcome(boolean success, String actualReason) {
        requireEndpoint(TransferContext.Endpoint.RECEIVER);
        if (receiverLocalSuccess != null) {
            return;
        }
        receiverLocalSuccess = success;
        receiverTerminalReason = success ? null : actualReason;
        lifecycleState = success
                ? LiveMetricsSnapshot.LifecycleState.SUCCEEDED
                : LiveMetricsSnapshot.LifecycleState.FAILED;
        terminalWallClock = Instant.now();
        if (emissionAccountingInitialized) {
            emissionAccountingComplete = true;
        }
    }

    public synchronized void observeReceiverError() {
        requireEndpoint(TransferContext.Endpoint.RECEIVER);
        receiverErrorsObserved++;
    }

    private TransferMetrics receiverSnapshot(Instant capturedAt) {
        TransferMetrics.Builder metrics = context.newMetricsBuilder()
                .captureTimestamp(capturedAt)
                .finalizationTimestamp(terminalWallClock)
                .transferSuccess(null)
                .failureReason(null);
        metrics.unavailableReason("transfer_success", "sender terminal confirmation is not observable at receiver")
                .unavailableReason("transfer_time_sec", "sender transfer duration is not observable at receiver")
                .unavailableReason("throughput_mbps", "sender transfer duration is not observable at receiver")
                .unavailableReason("packets_sent", "sender DATA emission evidence is unavailable at receiver")
                .unavailableReason("retransmissions", "sender retransmission evidence is unavailable at receiver")
                .unavailableReason("acks_received", "sender ACK arrival evidence is unavailable at receiver")
                .unavailableReason("packets_acked", "sender ACK progress is unavailable at receiver")
                .unavailableReason("packets_timed_out", "sender DATA timeout evidence is unavailable at receiver")
                .unavailableReason("retransmission_ratio", "sender retransmission evidence is unavailable at receiver")
                .unavailableReason("packets_dropped", "impairment observation is deferred")
                .unavailableReason("udp_payload_bytes_emitted", "requires sender and receiver reconciliation")
                .unavailableReason("protocol_overhead_bytes", "cross-endpoint byte accounting is deferred")
                .unavailableReason("protocol_overhead_ratio", "cross-endpoint byte accounting is deferred")
                .unavailableReason("rtt_sample_count", "RTT is observed only by the sender")
                .unavailableReason("rtt_mean_ms", "RTT is observed only by the sender")
                .unavailableReason("rtt_p95_ms", "RTT is observed only by the sender");

        if (impairmentObservationActive) {
            metrics.packetsDropped(impairmentDrops).clearUnavailableReason("packets_dropped");
        } else if (context.getConfiguration() != null
                && "RECEIVE_DELIVERY_V1".equals(context.getConfiguration().getImpairmentMechanism())) {
            metrics.unavailableReason("packets_dropped", "receive-delivery observation never started");
        }
        if (receiverMeasurementActive) {
            metrics.packetsReceived(packetsReceived)
                    .packetsDuplicated(packetsDuplicated)
                    .payloadBytesDelivered(payloadBytesDelivered);
        } else {
            metrics.unavailableReason("packets_received", "no accepted START established receiver measurement")
                    .unavailableReason("packets_duplicated", "no accepted START established receiver measurement")
                    .unavailableReason("payload_bytes_delivered", "no accepted START established receiver measurement");
        }
        if (integrityVerified == null) {
            metrics.unavailableReason("integrity_verified", "no completed SHA-256 comparison was observed");
        } else {
            metrics.integrityVerified(integrityVerified).integrityEvidenceSource("RECEIVER");
        }
        return metrics.build();
    }

    /** Small immutable view of supporting sender events reserved for later event logging. */
    public synchronized SenderObservations senderObservations() {
        requireEndpoint(TransferContext.Endpoint.SENDER);
        return senderObservation();
    }

    /** Immutable supporting receiver lifecycle view; transfer_success remains sender-only. */
    public synchronized ReceiverObservations receiverObservations() {
        requireEndpoint(TransferContext.Endpoint.RECEIVER);
        return receiverObservation();
    }

    /** Endpoint-local evidence; it is not the combined sender-plus-receiver metric. */
    public synchronized EndpointEmissionObservations endpointEmissionObservations() {
        return endpointEmissionObservation();
    }

    private SenderObservations senderObservation() {
        return new SenderObservations(startAttempts, startAcknowledged, recoveryRounds,
                finishAttempts, finishAcknowledged, errorsObserved);
    }

    private ReceiverObservations receiverObservation() {
        return new ReceiverObservations(
                receiverStartArrivals, receiverStartAccepted, receiverStartRejections,
                establishedPeerAddress, establishedPeerPort, dataValidationFailures,
                dataAccepted, packetsDuplicated, dataOutOfOrderDiscarded, dataAckAttempts,
                finishArrivals, integrityVerified, finishAckAttempts, duplicateFinishArrivals,
                cachedFinishAckResendAttempts, completionGraceExpired, receiverLocalSuccess,
                receiverTerminalReason, receiverErrorsObserved);
    }

    private EndpointEmissionObservations endpointEmissionObservation() {
        return new EndpointEmissionObservations(
                emissionAccountingInitialized ? endpointUdpPayloadBytesEmitted : null,
                emissionAccountingComplete);
    }

    private MetricsCalculator.RttStatistics currentRttStatistics() {
        if (rttStatisticsDirty || cachedRttStatistics == null) {
            cachedRttStatistics = MetricsCalculator.rttStatistics(eligibleRttMillis);
            rttStatisticsDirty = false;
        }
        return cachedRttStatistics;
    }

    private Double liveElapsedTimeSeconds() {
        if (context.getEndpoint() != TransferContext.Endpoint.SENDER || startedAtNanos == null) {
            return null;
        }
        long end = terminalAtNanos == null ? monotonicNanos.getAsLong() : terminalAtNanos;
        return MetricsCalculator.transferDurationSeconds(startedAtNanos, end);
    }

    private Long acknowledgedPayloadBytes() {
        if (context.getEndpoint() != TransferContext.Endpoint.SENDER
                || !acknowledgedBytesInitialized || acknowledgedBytesUnavailable) {
            return null;
        }
        return senderAcknowledgedPayloadBytes;
    }

    private void ensureCountersObserved() {
        countersObserved = true;
    }

    private static TransferContext requireContext(TransferContext context) {
        if (context == null) {
            throw new IllegalArgumentException("a transfer context is required");
        }
        return context;
    }

    private void requireEndpoint(TransferContext.Endpoint expected) {
        if (context.getEndpoint() != expected) {
            throw new IllegalStateException(expected + " observation used by " + context.getEndpoint() + " collector");
        }
    }

    private void requireReceiverMeasurement() {
        requireEndpoint(TransferContext.Endpoint.RECEIVER);
        if (!receiverMeasurementActive) {
            throw new IllegalStateException("receiver measurement requires an accepted START");
        }
    }

    private static void addDeferredReasons(TransferMetrics.Builder metrics) {
        metrics.unavailableReason("payload_bytes_delivered", "receiver delivery evidence is deferred")
                .unavailableReason("throughput_mbps", "receiver delivery evidence is deferred")
                .unavailableReason("packets_received", "receiver observation is deferred")
                .unavailableReason("packets_dropped", "impairment observation is deferred")
                .unavailableReason("packets_duplicated", "receiver observation is deferred")
                .unavailableReason("integrity_verified", "receiver integrity evidence is deferred")
                .unavailableReason("udp_payload_bytes_emitted", "requires sender and receiver reconciliation")
                .unavailableReason("protocol_overhead_bytes", "cross-endpoint byte accounting is deferred")
                .unavailableReason("protocol_overhead_ratio", "cross-endpoint byte accounting is deferred");
    }

    public record SenderObservations(
            long startAttempts,
            boolean startAcknowledged,
            long recoveryRounds,
            long finishAttempts,
            boolean finishAcknowledged,
            long errorsObserved) {}

    public record ReceiverObservations(
            long startArrivals,
            boolean startAccepted,
            long startRejections,
            String establishedPeerAddress,
            Integer establishedPeerPort,
            long dataValidationFailures,
            long dataAccepted,
            long dataDuplicated,
            long dataOutOfOrderDiscarded,
            long dataAckAttempts,
            long finishArrivals,
            Boolean integrityVerified,
            long finishAckAttempts,
            long duplicateFinishArrivals,
            long cachedFinishAckResendAttempts,
            boolean completionGraceExpired,
            Boolean localSuccess,
            String terminalReason,
            long errorsObserved) {}

    public record EndpointEmissionObservations(
            Long localUdpPayloadBytesEmitted,
            boolean accountingComplete) {}

    private record RttState(long originalSentAtNanos, boolean retransmitted, Integer payloadLength) {}
}
