package nettransfer.metrics;

/** One schema-v1 reconciled transfer summary written as one JSONL record. */
public record FinalMetricsSummary(
        String schemaVersion,
        String metricDefinitionVersion,
        TransferMetrics.EvidenceSource evidenceSource,
        String reconciliationStatus,
        String evidenceCompleteness,
        String protocolTransferId,
        String senderRunId,
        String receiverRunId,
        String applicationTransferId,
        String experimentId,
        TransferMetrics metrics,
        SourceEvidence sourceEvidence,
        String finalizedAtUtc) {

    public record SourceEvidence(
            String senderEndpointRecord,
            String receiverEndpointRecord,
            String senderEventLog,
            String receiverEventLog,
            String senderRunState,
            String receiverRunState) {}
}
