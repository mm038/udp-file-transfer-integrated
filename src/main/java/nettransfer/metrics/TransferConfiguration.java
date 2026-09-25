package nettransfer.metrics;

/**
 * Immutable effective configuration for one transfer endpoint.
 *
 * <p>Nullable values are unknown or not applicable at that endpoint. Sender DATA settings and
 * receiver waiting settings remain separate so similarly named timeouts cannot be confused.
 */
public final class TransferConfiguration {
    private final Long chunkSizeBytes;
    private final Long windowBytesRequested;
    private final Long windowPackets;
    private final Long timeoutMs;
    private final Long retryLimit;
    private final Double packetLossRate;
    private final Double delayMs;
    private final String scenario;
    private final Long impairmentSeed;
    private final String impairmentMechanism;
    private final Long startHandshakeTimeoutMs;
    private final Long startRetryLimit;
    private final Long finishHandshakeTimeoutMs;
    private final Long finishRetryLimit;
    private final Long receiverInitialTimeoutMs;
    private final Long receiverInactivityTimeoutMs;
    private final Long receiverCompletionGraceMs;

    private TransferConfiguration(Builder builder) {
        chunkSizeBytes = positive("chunkSizeBytes", builder.chunkSizeBytes);
        windowBytesRequested = positive("windowBytesRequested", builder.windowBytesRequested);
        windowPackets = positive("windowPackets", builder.windowPackets);
        timeoutMs = positive("timeoutMs", builder.timeoutMs);
        retryLimit = nonNegative("retryLimit", builder.retryLimit);
        packetLossRate = percentage("packetLossRate", builder.packetLossRate);
        delayMs = nonNegativeFinite("delayMs", builder.delayMs);
        scenario = optionalText("scenario", builder.scenario);
        impairmentSeed = builder.impairmentSeed;
        impairmentMechanism = optionalText("impairmentMechanism", builder.impairmentMechanism);
        startHandshakeTimeoutMs = positive("startHandshakeTimeoutMs", builder.startHandshakeTimeoutMs);
        startRetryLimit = nonNegative("startRetryLimit", builder.startRetryLimit);
        finishHandshakeTimeoutMs = positive("finishHandshakeTimeoutMs", builder.finishHandshakeTimeoutMs);
        finishRetryLimit = nonNegative("finishRetryLimit", builder.finishRetryLimit);
        receiverInitialTimeoutMs = positive("receiverInitialTimeoutMs", builder.receiverInitialTimeoutMs);
        receiverInactivityTimeoutMs = positive(
                "receiverInactivityTimeoutMs", builder.receiverInactivityTimeoutMs);
        receiverCompletionGraceMs = positive(
                "receiverCompletionGraceMs", builder.receiverCompletionGraceMs);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .chunkSizeBytes(chunkSizeBytes)
                .windowBytesRequested(windowBytesRequested)
                .windowPackets(windowPackets)
                .timeoutMs(timeoutMs)
                .retryLimit(retryLimit)
                .packetLossRate(packetLossRate)
                .delayMs(delayMs)
                .scenario(scenario)
                .impairmentSeed(impairmentSeed)
                .impairmentMechanism(impairmentMechanism)
                .startHandshakeTimeoutMs(startHandshakeTimeoutMs)
                .startRetryLimit(startRetryLimit)
                .finishHandshakeTimeoutMs(finishHandshakeTimeoutMs)
                .finishRetryLimit(finishRetryLimit)
                .receiverInitialTimeoutMs(receiverInitialTimeoutMs)
                .receiverInactivityTimeoutMs(receiverInactivityTimeoutMs)
                .receiverCompletionGraceMs(receiverCompletionGraceMs);
    }

    private static Long positive(String field, Long value) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
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

    private static Double percentage(String field, Double value) {
        if (value != null && (!Double.isFinite(value) || value < 0.0 || value > 100.0)) {
            throw new IllegalArgumentException(field + " must be a finite percentage from 0 to 100");
        }
        return value;
    }

    private static String optionalText(String field, String value) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public Long getChunkSizeBytes() { return chunkSizeBytes; }
    public Long getWindowBytesRequested() { return windowBytesRequested; }
    public Long getWindowPackets() { return windowPackets; }
    public Long getTimeoutMs() { return timeoutMs; }
    public Long getRetryLimit() { return retryLimit; }
    public Double getPacketLossRate() { return packetLossRate; }
    public Double getDelayMs() { return delayMs; }
    public String getScenario() { return scenario; }
    public Long getImpairmentSeed() { return impairmentSeed; }
    public String getImpairmentMechanism() { return impairmentMechanism; }
    public Long getStartHandshakeTimeoutMs() { return startHandshakeTimeoutMs; }
    public Long getStartRetryLimit() { return startRetryLimit; }
    public Long getFinishHandshakeTimeoutMs() { return finishHandshakeTimeoutMs; }
    public Long getFinishRetryLimit() { return finishRetryLimit; }
    public Long getReceiverInitialTimeoutMs() { return receiverInitialTimeoutMs; }
    public Long getReceiverInactivityTimeoutMs() { return receiverInactivityTimeoutMs; }
    public Long getReceiverCompletionGraceMs() { return receiverCompletionGraceMs; }

    public static final class Builder {
        private Long chunkSizeBytes;
        private Long windowBytesRequested;
        private Long windowPackets;
        private Long timeoutMs;
        private Long retryLimit;
        private Double packetLossRate;
        private Double delayMs;
        private String scenario;
        private Long impairmentSeed;
        private String impairmentMechanism;
        private Long startHandshakeTimeoutMs;
        private Long startRetryLimit;
        private Long finishHandshakeTimeoutMs;
        private Long finishRetryLimit;
        private Long receiverInitialTimeoutMs;
        private Long receiverInactivityTimeoutMs;
        private Long receiverCompletionGraceMs;

        private Builder() {}

        public Builder chunkSizeBytes(Long value) { chunkSizeBytes = value; return this; }
        public Builder windowBytesRequested(Long value) { windowBytesRequested = value; return this; }
        public Builder windowPackets(Long value) { windowPackets = value; return this; }
        public Builder timeoutMs(Long value) { timeoutMs = value; return this; }
        public Builder retryLimit(Long value) { retryLimit = value; return this; }
        public Builder packetLossRate(Double value) { packetLossRate = value; return this; }
        public Builder delayMs(Double value) { delayMs = value; return this; }
        public Builder scenario(String value) { scenario = value; return this; }
        public Builder impairmentSeed(Long value) { impairmentSeed = value; return this; }
        public Builder impairmentMechanism(String value) { impairmentMechanism = value; return this; }
        public Builder startHandshakeTimeoutMs(Long value) { startHandshakeTimeoutMs = value; return this; }
        public Builder startRetryLimit(Long value) { startRetryLimit = value; return this; }
        public Builder finishHandshakeTimeoutMs(Long value) { finishHandshakeTimeoutMs = value; return this; }
        public Builder finishRetryLimit(Long value) { finishRetryLimit = value; return this; }
        public Builder receiverInitialTimeoutMs(Long value) { receiverInitialTimeoutMs = value; return this; }
        public Builder receiverInactivityTimeoutMs(Long value) {
            receiverInactivityTimeoutMs = value;
            return this;
        }
        public Builder receiverCompletionGraceMs(Long value) {
            receiverCompletionGraceMs = value;
            return this;
        }

        public TransferConfiguration build() { return new TransferConfiguration(this); }
    }
}
