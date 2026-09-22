package nettransfer.metrics;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable, finalized metrics for one transfer observation.
 *
 * <p>Nullable values mean that evidence was unavailable or the metric did not apply. An observed
 * zero is represented as zero. Identifiers are supplied by callers and are never generated here.
 */
public final class TransferMetrics {
    public enum EvidenceSource {
        REAL,
        SYNTHETIC
    }

    private final String experimentId;
    private final Long fileSizeBytes;
    private final Long payloadBytesDelivered;
    private final Double transferTimeSec;
    private final Double throughputMbps;
    private final Boolean transferSuccess;
    private final Boolean integrityVerified;
    private final String failureReason;

    private final Long packetsSent;
    private final Long packetsReceived;
    private final Long packetsDropped;
    private final Long retransmissions;
    private final Long acksReceived;
    private final Long packetsAcked;
    private final Long packetsTimedOut;
    private final Long packetsDuplicated;
    private final Double retransmissionRatio;

    private final Long udpPayloadBytesEmitted;
    private final Long protocolOverheadBytes;
    private final Double protocolOverheadRatio;
    private final Long rttSampleCount;
    private final Double rttMeanMs;
    private final Double rttP95Ms;

    private final Long chunkSizeBytes;
    private final Long windowBytesRequested;
    private final Long windowPackets;
    private final Long timeoutMs;
    private final Long retryLimit;
    private final Double packetLossRate;
    private final Double delayMs;
    private final String scenario;
    private final Long impairmentSeed;

    private final String schemaVersion;
    private final String metricDefinitionVersion;
    private final String runId;
    private final String applicationTransferId;
    private final String protocolTransferId;
    private final EvidenceSource evidenceSource;
    private final String endpointAttribution;
    private final String fileAttribution;
    private final Instant captureTimestamp;
    private final Instant finalizationTimestamp;
    private final String integrityEvidenceSource;
    private final Map<String, String> unavailableReasons;

    private TransferMetrics(Builder builder) {
        experimentId = builder.experimentId;
        fileSizeBytes = nonNegative("fileSizeBytes", builder.fileSizeBytes);
        payloadBytesDelivered = nonNegative("payloadBytesDelivered", builder.payloadBytesDelivered);
        transferTimeSec = nonNegativeFinite("transferTimeSec", builder.transferTimeSec);
        throughputMbps = nonNegativeFinite("throughputMbps", builder.throughputMbps);
        transferSuccess = builder.transferSuccess;
        integrityVerified = builder.integrityVerified;
        failureReason = builder.failureReason;

        packetsSent = nonNegative("packetsSent", builder.packetsSent);
        packetsReceived = nonNegative("packetsReceived", builder.packetsReceived);
        packetsDropped = nonNegative("packetsDropped", builder.packetsDropped);
        retransmissions = nonNegative("retransmissions", builder.retransmissions);
        acksReceived = nonNegative("acksReceived", builder.acksReceived);
        packetsAcked = nonNegative("packetsAcked", builder.packetsAcked);
        packetsTimedOut = nonNegative("packetsTimedOut", builder.packetsTimedOut);
        packetsDuplicated = nonNegative("packetsDuplicated", builder.packetsDuplicated);
        retransmissionRatio = fraction("retransmissionRatio", builder.retransmissionRatio);

        udpPayloadBytesEmitted = nonNegative("udpPayloadBytesEmitted", builder.udpPayloadBytesEmitted);
        protocolOverheadBytes = nonNegative("protocolOverheadBytes", builder.protocolOverheadBytes);
        protocolOverheadRatio = fraction("protocolOverheadRatio", builder.protocolOverheadRatio);
        rttSampleCount = nonNegative("rttSampleCount", builder.rttSampleCount);
        rttMeanMs = nonNegativeFinite("rttMeanMs", builder.rttMeanMs);
        rttP95Ms = nonNegativeFinite("rttP95Ms", builder.rttP95Ms);

        chunkSizeBytes = nonNegative("chunkSizeBytes", builder.chunkSizeBytes);
        windowBytesRequested = nonNegative("windowBytesRequested", builder.windowBytesRequested);
        windowPackets = nonNegative("windowPackets", builder.windowPackets);
        timeoutMs = nonNegative("timeoutMs", builder.timeoutMs);
        retryLimit = nonNegative("retryLimit", builder.retryLimit);
        packetLossRate = percentage("packetLossRate", builder.packetLossRate);
        delayMs = nonNegativeFinite("delayMs", builder.delayMs);
        scenario = builder.scenario;
        impairmentSeed = builder.impairmentSeed;

        schemaVersion = builder.schemaVersion;
        metricDefinitionVersion = builder.metricDefinitionVersion;
        runId = builder.runId;
        applicationTransferId = builder.applicationTransferId;
        protocolTransferId = builder.protocolTransferId;
        evidenceSource = builder.evidenceSource;
        endpointAttribution = builder.endpointAttribution;
        fileAttribution = builder.fileAttribution;
        captureTimestamp = builder.captureTimestamp;
        finalizationTimestamp = builder.finalizationTimestamp;
        integrityEvidenceSource = builder.integrityEvidenceSource;
        unavailableReasons = immutableReasons(builder.unavailableReasons);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return builder()
                .experimentId(experimentId).fileSizeBytes(fileSizeBytes)
                .payloadBytesDelivered(payloadBytesDelivered).transferTimeSec(transferTimeSec)
                .throughputMbps(throughputMbps).transferSuccess(transferSuccess)
                .integrityVerified(integrityVerified).failureReason(failureReason)
                .packetsSent(packetsSent).packetsReceived(packetsReceived)
                .packetsDropped(packetsDropped).retransmissions(retransmissions)
                .acksReceived(acksReceived).packetsAcked(packetsAcked)
                .packetsTimedOut(packetsTimedOut).packetsDuplicated(packetsDuplicated)
                .retransmissionRatio(retransmissionRatio)
                .udpPayloadBytesEmitted(udpPayloadBytesEmitted)
                .protocolOverheadBytes(protocolOverheadBytes)
                .protocolOverheadRatio(protocolOverheadRatio)
                .rttSampleCount(rttSampleCount).rttMeanMs(rttMeanMs).rttP95Ms(rttP95Ms)
                .chunkSizeBytes(chunkSizeBytes).windowBytesRequested(windowBytesRequested)
                .windowPackets(windowPackets).timeoutMs(timeoutMs).retryLimit(retryLimit)
                .packetLossRate(packetLossRate).delayMs(delayMs).scenario(scenario)
                .impairmentSeed(impairmentSeed).schemaVersion(schemaVersion)
                .metricDefinitionVersion(metricDefinitionVersion).runId(runId)
                .applicationTransferId(applicationTransferId)
                .protocolTransferId(protocolTransferId).evidenceSource(evidenceSource)
                .endpointAttribution(endpointAttribution).fileAttribution(fileAttribution)
                .captureTimestamp(captureTimestamp).finalizationTimestamp(finalizationTimestamp)
                .integrityEvidenceSource(integrityEvidenceSource)
                .unavailableReasons(unavailableReasons);
    }

    private static Long nonNegative(String field, Long value) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value;
    }

    private static Double nonNegativeFinite(String field, Double value) {
        if (value != null && (!Double.isFinite(value) || value < 0.0)) {
            throw new IllegalArgumentException(field + " must be finite and non-negative");
        }
        return value;
    }

    private static Double fraction(String field, Double value) {
        if (value != null && (!Double.isFinite(value) || value < 0.0 || value > 1.0)) {
            throw new IllegalArgumentException(field + " must be a finite fraction from 0 to 1");
        }
        return value;
    }

    private static Double percentage(String field, Double value) {
        if (value != null && (!Double.isFinite(value) || value < 0.0 || value > 100.0)) {
            throw new IllegalArgumentException(field + " must be a finite percentage from 0 to 100");
        }
        return value;
    }

    private static Map<String, String> immutableReasons(Map<String, String> reasons) {
        LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        reasons.forEach((field, reason) -> {
            if (field == null || field.isBlank() || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("unavailable reason field and explanation must be non-blank");
            }
            copy.put(field, reason);
        });
        return Collections.unmodifiableMap(copy);
    }

    public String getExperimentId() { return experimentId; }
    public Long getFileSizeBytes() { return fileSizeBytes; }
    public Long getPayloadBytesDelivered() { return payloadBytesDelivered; }
    public Double getTransferTimeSec() { return transferTimeSec; }
    public Double getThroughputMbps() { return throughputMbps; }
    public Boolean getTransferSuccess() { return transferSuccess; }
    public Boolean getIntegrityVerified() { return integrityVerified; }
    public String getFailureReason() { return failureReason; }
    public Long getPacketsSent() { return packetsSent; }
    public Long getPacketsReceived() { return packetsReceived; }
    public Long getPacketsDropped() { return packetsDropped; }
    public Long getRetransmissions() { return retransmissions; }
    public Long getAcksReceived() { return acksReceived; }
    public Long getPacketsAcked() { return packetsAcked; }
    public Long getPacketsTimedOut() { return packetsTimedOut; }
    public Long getPacketsDuplicated() { return packetsDuplicated; }
    public Double getRetransmissionRatio() { return retransmissionRatio; }
    public Long getUdpPayloadBytesEmitted() { return udpPayloadBytesEmitted; }
    public Long getProtocolOverheadBytes() { return protocolOverheadBytes; }
    public Double getProtocolOverheadRatio() { return protocolOverheadRatio; }
    public Long getRttSampleCount() { return rttSampleCount; }
    public Double getRttMeanMs() { return rttMeanMs; }
    public Double getRttP95Ms() { return rttP95Ms; }
    public Long getChunkSizeBytes() { return chunkSizeBytes; }
    public Long getWindowBytesRequested() { return windowBytesRequested; }
    public Long getWindowPackets() { return windowPackets; }
    public Long getTimeoutMs() { return timeoutMs; }
    public Long getRetryLimit() { return retryLimit; }
    public Double getPacketLossRate() { return packetLossRate; }
    public Double getDelayMs() { return delayMs; }
    public String getScenario() { return scenario; }
    public Long getImpairmentSeed() { return impairmentSeed; }
    public String getSchemaVersion() { return schemaVersion; }
    public String getMetricDefinitionVersion() { return metricDefinitionVersion; }
    public String getRunId() { return runId; }
    public String getApplicationTransferId() { return applicationTransferId; }
    public String getProtocolTransferId() { return protocolTransferId; }
    public EvidenceSource getEvidenceSource() { return evidenceSource; }
    public String getEndpointAttribution() { return endpointAttribution; }
    public String getFileAttribution() { return fileAttribution; }
    public Instant getCaptureTimestamp() { return captureTimestamp; }
    public Instant getFinalizationTimestamp() { return finalizationTimestamp; }
    public String getIntegrityEvidenceSource() { return integrityEvidenceSource; }
    public Map<String, String> getUnavailableReasons() { return unavailableReasons; }

    public static final class Builder {
        private String experimentId;
        private Long fileSizeBytes;
        private Long payloadBytesDelivered;
        private Double transferTimeSec;
        private Double throughputMbps;
        private Boolean transferSuccess;
        private Boolean integrityVerified;
        private String failureReason;
        private Long packetsSent;
        private Long packetsReceived;
        private Long packetsDropped;
        private Long retransmissions;
        private Long acksReceived;
        private Long packetsAcked;
        private Long packetsTimedOut;
        private Long packetsDuplicated;
        private Double retransmissionRatio;
        private Long udpPayloadBytesEmitted;
        private Long protocolOverheadBytes;
        private Double protocolOverheadRatio;
        private Long rttSampleCount;
        private Double rttMeanMs;
        private Double rttP95Ms;
        private Long chunkSizeBytes;
        private Long windowBytesRequested;
        private Long windowPackets;
        private Long timeoutMs;
        private Long retryLimit;
        private Double packetLossRate;
        private Double delayMs;
        private String scenario;
        private Long impairmentSeed;
        private String schemaVersion;
        private String metricDefinitionVersion;
        private String runId;
        private String applicationTransferId;
        private String protocolTransferId;
        private EvidenceSource evidenceSource;
        private String endpointAttribution;
        private String fileAttribution;
        private Instant captureTimestamp;
        private Instant finalizationTimestamp;
        private String integrityEvidenceSource;
        private Map<String, String> unavailableReasons = new LinkedHashMap<>();

        private Builder() {}

        public Builder experimentId(String value) { experimentId = value; return this; }
        public Builder fileSizeBytes(Long value) { fileSizeBytes = value; return this; }
        public Builder payloadBytesDelivered(Long value) { payloadBytesDelivered = value; return this; }
        public Builder transferTimeSec(Double value) { transferTimeSec = value; return this; }
        public Builder throughputMbps(Double value) { throughputMbps = value; return this; }
        public Builder transferSuccess(Boolean value) { transferSuccess = value; return this; }
        public Builder integrityVerified(Boolean value) { integrityVerified = value; return this; }
        public Builder failureReason(String value) { failureReason = value; return this; }
        public Builder packetsSent(Long value) { packetsSent = value; return this; }
        public Builder packetsReceived(Long value) { packetsReceived = value; return this; }
        public Builder packetsDropped(Long value) { packetsDropped = value; return this; }
        public Builder retransmissions(Long value) { retransmissions = value; return this; }
        public Builder acksReceived(Long value) { acksReceived = value; return this; }
        public Builder packetsAcked(Long value) { packetsAcked = value; return this; }
        public Builder packetsTimedOut(Long value) { packetsTimedOut = value; return this; }
        public Builder packetsDuplicated(Long value) { packetsDuplicated = value; return this; }
        public Builder retransmissionRatio(Double value) { retransmissionRatio = value; return this; }
        public Builder udpPayloadBytesEmitted(Long value) { udpPayloadBytesEmitted = value; return this; }
        public Builder protocolOverheadBytes(Long value) { protocolOverheadBytes = value; return this; }
        public Builder protocolOverheadRatio(Double value) { protocolOverheadRatio = value; return this; }
        public Builder rttSampleCount(Long value) { rttSampleCount = value; return this; }
        public Builder rttMeanMs(Double value) { rttMeanMs = value; return this; }
        public Builder rttP95Ms(Double value) { rttP95Ms = value; return this; }
        public Builder chunkSizeBytes(Long value) { chunkSizeBytes = value; return this; }
        public Builder windowBytesRequested(Long value) { windowBytesRequested = value; return this; }
        public Builder windowPackets(Long value) { windowPackets = value; return this; }
        public Builder timeoutMs(Long value) { timeoutMs = value; return this; }
        public Builder retryLimit(Long value) { retryLimit = value; return this; }
        public Builder packetLossRate(Double value) { packetLossRate = value; return this; }
        public Builder delayMs(Double value) { delayMs = value; return this; }
        public Builder scenario(String value) { scenario = value; return this; }
        public Builder impairmentSeed(Long value) { impairmentSeed = value; return this; }
        public Builder schemaVersion(String value) { schemaVersion = value; return this; }
        public Builder metricDefinitionVersion(String value) { metricDefinitionVersion = value; return this; }
        public Builder runId(String value) { runId = value; return this; }
        public Builder applicationTransferId(String value) { applicationTransferId = value; return this; }
        public Builder protocolTransferId(String value) { protocolTransferId = value; return this; }
        public Builder evidenceSource(EvidenceSource value) { evidenceSource = value; return this; }
        public Builder endpointAttribution(String value) { endpointAttribution = value; return this; }
        public Builder fileAttribution(String value) { fileAttribution = value; return this; }
        public Builder captureTimestamp(Instant value) { captureTimestamp = value; return this; }
        public Builder finalizationTimestamp(Instant value) { finalizationTimestamp = value; return this; }
        public Builder integrityEvidenceSource(String value) { integrityEvidenceSource = value; return this; }

        public Builder unavailableReasons(Map<String, String> value) {
            unavailableReasons = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
            return this;
        }

        public Builder unavailableReason(String field, String reason) {
            unavailableReasons.put(field, reason);
            return this;
        }

        public TransferMetrics build() { return new TransferMetrics(this); }
    }
}
