package nettransfer.metrics;

import java.util.Objects;

/** One immutable schema-v1 JSONL event. Optional fields are serialized as JSON null. */
public record TransferEvent(
        String schemaVersion,
        String eventId,
        long eventSequence,
        String timestampUtc,
        long monotonicTimeNanos,
        String experimentId,
        String runId,
        String applicationTransferId,
        String protocolTransferId,
        String endpoint,
        EventType eventType,
        Direction direction,
        String messageType,
        Integer sequenceNumber,
        Integer ackNumber,
        Integer attemptNumber,
        Long payloadBytes,
        Long encodedUdpPayloadBytes,
        String validationResult,
        String sequenceOutcome,
        String eventOutcome,
        String failureReason,
        Long newlyAcknowledgedPackets,
        Boolean retransmission,
        Boolean integrityVerified,
        Long impairmentDecisionIndex,
        Integer impairmentDelayMs) {

    public static final String SCHEMA_VERSION = MetricsSchema.EVENT_SCHEMA_VERSION;

    public TransferEvent {
        Objects.requireNonNull(schemaVersion, "schema version is required");
        Objects.requireNonNull(eventId, "event ID is required");
        Objects.requireNonNull(timestampUtc, "UTC timestamp is required");
        Objects.requireNonNull(runId, "run ID is required");
        Objects.requireNonNull(endpoint, "endpoint is required");
        Objects.requireNonNull(eventType, "event type is required");
        Objects.requireNonNull(direction, "direction is required");
    }

    /** Source-compatible constructor for events without impairment observations. */
    public TransferEvent(String schemaVersion, String eventId, long eventSequence, String timestampUtc,
                         long monotonicTimeNanos, String experimentId, String runId,
                         String applicationTransferId, String protocolTransferId, String endpoint,
                         EventType eventType, Direction direction, String messageType,
                         Integer sequenceNumber, Integer ackNumber, Integer attemptNumber,
                         Long payloadBytes, Long encodedUdpPayloadBytes, String validationResult,
                         String sequenceOutcome, String eventOutcome, String failureReason,
                         Long newlyAcknowledgedPackets, Boolean retransmission, Boolean integrityVerified) {
        this(schemaVersion, eventId, eventSequence, timestampUtc, monotonicTimeNanos, experimentId,
                runId, applicationTransferId, protocolTransferId, endpoint, eventType, direction,
                messageType, sequenceNumber, ackNumber, attemptNumber, payloadBytes, encodedUdpPayloadBytes,
                validationResult, sequenceOutcome, eventOutcome, failureReason, newlyAcknowledgedPackets,
                retransmission, integrityVerified, null, null);
    }

    public enum Direction {
        OUTBOUND,
        INBOUND,
        LOCAL
    }

    /** Optional event-specific fields; identity and timestamps are supplied by EventLogger. */
    public record Details(
            String messageType,
            Integer sequenceNumber,
            Integer ackNumber,
            Integer attemptNumber,
            Long payloadBytes,
            Long encodedUdpPayloadBytes,
            String validationResult,
            String sequenceOutcome,
            String eventOutcome,
            String failureReason,
            Long newlyAcknowledgedPackets,
            Boolean retransmission,
            Boolean integrityVerified,
            Long impairmentDecisionIndex,
            Integer impairmentDelayMs) {

        public Details(String messageType, Integer sequenceNumber, Integer ackNumber,
                       Integer attemptNumber, Long payloadBytes, Long encodedUdpPayloadBytes,
                       String validationResult, String sequenceOutcome, String eventOutcome,
                       String failureReason, Long newlyAcknowledgedPackets, Boolean retransmission,
                       Boolean integrityVerified) {
            this(messageType, sequenceNumber, ackNumber, attemptNumber, payloadBytes,
                    encodedUdpPayloadBytes, validationResult, sequenceOutcome, eventOutcome,
                    failureReason, newlyAcknowledgedPackets, retransmission, integrityVerified, null, null);
        }

        public static Details empty() {
            return new Details(null, null, null, null, null, null, null, null,
                    null, null, null, null, null);
        }

        public static Builder builder() {
            return new Builder();
        }
    }

    public static final class Builder {
        private String messageType;
        private Integer sequenceNumber;
        private Integer ackNumber;
        private Integer attemptNumber;
        private Long payloadBytes;
        private Long encodedUdpPayloadBytes;
        private String validationResult;
        private String sequenceOutcome;
        private String eventOutcome;
        private String failureReason;
        private Long newlyAcknowledgedPackets;
        private Boolean retransmission;
        private Boolean integrityVerified;
        private Long impairmentDecisionIndex;
        private Integer impairmentDelayMs;

        public Builder messageType(String value) { messageType = value; return this; }
        public Builder sequenceNumber(Integer value) { sequenceNumber = value; return this; }
        public Builder ackNumber(Integer value) { ackNumber = value; return this; }
        public Builder attemptNumber(Integer value) { attemptNumber = value; return this; }
        public Builder payloadBytes(Long value) { payloadBytes = value; return this; }
        public Builder encodedUdpPayloadBytes(Long value) { encodedUdpPayloadBytes = value; return this; }
        public Builder validationResult(String value) { validationResult = value; return this; }
        public Builder sequenceOutcome(String value) { sequenceOutcome = value; return this; }
        public Builder eventOutcome(String value) { eventOutcome = value; return this; }
        public Builder failureReason(String value) { failureReason = value; return this; }
        public Builder newlyAcknowledgedPackets(Long value) { newlyAcknowledgedPackets = value; return this; }
        public Builder retransmission(Boolean value) { retransmission = value; return this; }
        public Builder integrityVerified(Boolean value) { integrityVerified = value; return this; }
        public Builder impairmentDecisionIndex(Long value) { impairmentDecisionIndex = value; return this; }
        public Builder impairmentDelayMs(Integer value) { impairmentDelayMs = value; return this; }

        public Details build() {
            return new Details(messageType, sequenceNumber, ackNumber, attemptNumber,
                    payloadBytes, encodedUdpPayloadBytes, validationResult, sequenceOutcome,
                    eventOutcome, failureReason, newlyAcknowledgedPackets, retransmission,
                    integrityVerified, impairmentDecisionIndex, impairmentDelayMs);
        }
    }
}
