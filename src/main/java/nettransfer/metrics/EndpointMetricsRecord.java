package nettransfer.metrics;

/** Durable final measurements and supporting observations for one local endpoint run. */
public record EndpointMetricsRecord(
        String schemaVersion,
        String metricDefinitionVersion,
        TransferMetrics.EvidenceSource evidenceSource,
        String endpoint,
        String runId,
        String experimentId,
        String applicationTransferId,
        String protocolTransferId,
        String originalFilename,
        String fileAttribution,
        TransferConfiguration configuration,
        TransferMetrics metrics,
        MetricsCollector.EndpointEmissionObservations endpointEmission,
        MetricsCollector.SenderObservations senderObservations,
        MetricsCollector.ReceiverObservations receiverObservations,
        String lifecycleState,
        boolean terminal,
        boolean evidenceComplete,
        String terminalOutcome,
        String failureReason,
        String integrityEvidenceSource,
        String capturedAtUtc,
        String finalizedAtUtc,
        String eventLogReference,
        String runStateReference) {}
