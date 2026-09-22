package nettransfer.metrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable identity, attribution, and effective configuration for one endpoint's transfer view.
 *
 * <p>Application callers can supply trusted identifiers through {@link #builder(Endpoint)} and pass
 * the resulting context to the sender or receiver constructor. A missing run ID is generated. The
 * protocol UUID is never generated here; engines attach the UUID created or validated by the wire
 * protocol.
 */
public final class TransferContext {
    public enum Endpoint {
        SENDER,
        RECEIVER
    }

    private final String experimentId;
    private final String runId;
    private final String applicationTransferId;
    private final UUID protocolTransferId;
    private final Endpoint endpoint;
    private final String originalFilename;
    private final String fileAttribution;
    private final Long fileSizeBytes;
    private final TransferConfiguration configuration;
    private final String schemaVersion;
    private final String metricDefinitionVersion;
    private final TransferMetrics.EvidenceSource evidenceSource;
    private final Map<String, String> unavailableReasons;

    private TransferContext(Builder builder) {
        experimentId = optionalId("experimentId", builder.experimentId);
        runId = builder.runId == null ? generateRunId() : requiredId("runId", builder.runId);
        applicationTransferId = optionalId("applicationTransferId", builder.applicationTransferId);
        protocolTransferId = builder.protocolTransferId;
        if (builder.endpoint == null) {
            throw new IllegalArgumentException("endpoint is required");
        }
        endpoint = builder.endpoint;
        originalFilename = optionalText("originalFilename", builder.originalFilename);
        fileAttribution = optionalText("fileAttribution", builder.fileAttribution);
        if (builder.fileSizeBytes != null && builder.fileSizeBytes < 0) {
            throw new IllegalArgumentException("fileSizeBytes must be non-negative");
        }
        fileSizeBytes = builder.fileSizeBytes;
        configuration = builder.configuration;
        schemaVersion = optionalText("schemaVersion", builder.schemaVersion);
        metricDefinitionVersion = optionalText(
                "metricDefinitionVersion", builder.metricDefinitionVersion);
        evidenceSource = builder.evidenceSource == null
                ? TransferMetrics.EvidenceSource.REAL
                : builder.evidenceSource;
        unavailableReasons = immutableReasons(builder.unavailableReasons);
    }

    public static Builder builder(Endpoint endpoint) {
        return new Builder(endpoint);
    }

    public Builder toBuilder() {
        return new Builder(endpoint)
                .experimentId(experimentId)
                .runId(runId)
                .applicationTransferId(applicationTransferId)
                .protocolTransferId(protocolTransferId)
                .originalFilename(originalFilename)
                .fileAttribution(fileAttribution)
                .fileSizeBytes(fileSizeBytes)
                .configuration(configuration)
                .schemaVersion(schemaVersion)
                .metricDefinitionVersion(metricDefinitionVersion)
                .evidenceSource(evidenceSource)
                .unavailableReasons(unavailableReasons);
    }

    public TransferContext withProtocolTransferId(UUID observedId) {
        if (observedId == null) {
            throw new IllegalArgumentException("observed protocol transfer ID is required");
        }
        if (protocolTransferId != null && !protocolTransferId.equals(observedId)) {
            throw new IllegalStateException("conflicting protocol transfer ID");
        }
        return toBuilder()
                .protocolTransferId(observedId)
                .clearUnavailableReason("protocol_transfer_id")
                .build();
    }

    /** Applies only identity, configuration, attribution, and provenance to a B1 metrics builder. */
    public TransferMetrics.Builder applyTo(TransferMetrics.Builder metrics) {
        if (metrics == null) {
            throw new IllegalArgumentException("metrics builder is required");
        }
        metrics.experimentId(experimentId)
                .runId(runId)
                .applicationTransferId(applicationTransferId)
                .protocolTransferId(protocolTransferId == null ? null : protocolTransferId.toString())
                .fileSizeBytes(fileSizeBytes)
                .evidenceSource(evidenceSource)
                .endpointAttribution(endpoint.name())
                .fileAttribution(fileAttribution)
                .schemaVersion(schemaVersion)
                .metricDefinitionVersion(metricDefinitionVersion)
                .unavailableReasons(unavailableReasons);
        if (configuration != null) {
            metrics.chunkSizeBytes(configuration.getChunkSizeBytes())
                    .windowBytesRequested(configuration.getWindowBytesRequested())
                    .windowPackets(configuration.getWindowPackets())
                    .timeoutMs(configuration.getTimeoutMs())
                    .retryLimit(configuration.getRetryLimit())
                    .packetLossRate(configuration.getPacketLossRate())
                    .delayMs(configuration.getDelayMs())
                    .scenario(configuration.getScenario())
                    .impairmentSeed(configuration.getImpairmentSeed());
        }
        return metrics;
    }

    public TransferMetrics.Builder newMetricsBuilder() {
        return applyTo(TransferMetrics.builder());
    }

    private static String generateRunId() {
        return "run-" + UUID.randomUUID();
    }

    private static String requiredId(String field, String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String optionalId(String field, String value) {
        return value == null ? null : requiredId(field, value);
    }

    private static String optionalText(String field, String value) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
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
    public String getRunId() { return runId; }
    public String getApplicationTransferId() { return applicationTransferId; }
    public UUID getProtocolTransferId() { return protocolTransferId; }
    public Endpoint getEndpoint() { return endpoint; }
    public String getOriginalFilename() { return originalFilename; }
    public String getFileAttribution() { return fileAttribution; }
    public Long getFileSizeBytes() { return fileSizeBytes; }
    public TransferConfiguration getConfiguration() { return configuration; }
    public String getSchemaVersion() { return schemaVersion; }
    public String getMetricDefinitionVersion() { return metricDefinitionVersion; }
    public TransferMetrics.EvidenceSource getEvidenceSource() { return evidenceSource; }
    public Map<String, String> getUnavailableReasons() { return unavailableReasons; }

    public static final class Builder {
        private String experimentId;
        private String runId;
        private String applicationTransferId;
        private UUID protocolTransferId;
        private final Endpoint endpoint;
        private String originalFilename;
        private String fileAttribution;
        private Long fileSizeBytes;
        private TransferConfiguration configuration;
        private String schemaVersion;
        private String metricDefinitionVersion;
        private TransferMetrics.EvidenceSource evidenceSource;
        private Map<String, String> unavailableReasons = new LinkedHashMap<>();

        private Builder(Endpoint endpoint) {
            this.endpoint = endpoint;
        }

        public Builder experimentId(String value) { experimentId = value; return this; }
        public Builder runId(String value) { runId = value; return this; }
        public Builder applicationTransferId(String value) { applicationTransferId = value; return this; }
        public Builder protocolTransferId(UUID value) { protocolTransferId = value; return this; }
        public Builder originalFilename(String value) { originalFilename = value; return this; }
        public Builder fileAttribution(String value) { fileAttribution = value; return this; }
        public Builder fileSizeBytes(Long value) { fileSizeBytes = value; return this; }
        public Builder configuration(TransferConfiguration value) { configuration = value; return this; }
        public Builder schemaVersion(String value) { schemaVersion = value; return this; }
        public Builder metricDefinitionVersion(String value) { metricDefinitionVersion = value; return this; }
        public Builder evidenceSource(TransferMetrics.EvidenceSource value) { evidenceSource = value; return this; }

        public Builder unavailableReasons(Map<String, String> value) {
            unavailableReasons = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
            return this;
        }

        public Builder unavailableReason(String field, String reason) {
            unavailableReasons.put(field, reason);
            return this;
        }

        public Builder clearUnavailableReason(String field) {
            unavailableReasons.remove(field);
            return this;
        }

        public TransferContext build() { return new TransferContext(this); }
    }
}
