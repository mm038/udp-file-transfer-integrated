package nettransfer.explanation;

import nettransfer.control.EvidenceSource;
import nettransfer.metrics.EndpointMetricsRecord;
import nettransfer.metrics.EvidenceLookupResult;
import nettransfer.metrics.FinalMetricsSummary;
import nettransfer.metrics.MetricsExporter;
import nettransfer.metrics.MetricsSchema;
import nettransfer.metrics.PersistedEvidenceRepository;
import nettransfer.metrics.TransferMetrics;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Converts only repository-validated REAL evidence into bounded explanation fields. */
public final class RealMetricsSummaryProvider implements SummaryProvider {
    private final PersistedEvidenceRepository repository;

    public RealMetricsSummaryProvider(PersistedEvidenceRepository repository) {
        this.repository = Objects.requireNonNull(repository, "evidence repository is required");
    }

    /** Compatibility lookup for the Stage 2 identity convention where run and application IDs match. */
    @Override
    public Optional<RecordedSummary> load(UUID runId) {
        Objects.requireNonNull(runId, "run ID is required");
        EvidenceLookupResult evidence = repository.lookup(runId.toString(), runId.toString());
        if (!evidence.isAvailable()) {
            return Optional.empty();
        }
        UUID protocolId = protocolId(evidence.evidence());
        return Optional.of(convert(new Selection(
                runId, runId, runId.toString(), protocolId), evidence.evidence()));
    }

    @Override
    public LookupResult lookup(Selection selection) {
        Objects.requireNonNull(selection, "selection");
        EvidenceLookupResult result = repository.lookup(
                selection.senderRunId(), selection.applicationTransferId().toString());
        if (!result.isAvailable()) {
            return mapState(result);
        }
        try {
            UUID recordedProtocolId = protocolId(result.evidence());
            if (!selection.protocolTransferId().equals(recordedProtocolId)) {
                return LookupResult.rejected("PROTOCOL_IDENTITY_MISMATCH",
                        "Validated evidence does not match the selected protocol transfer UUID");
            }
            return LookupResult.available(convert(selection, result.evidence()));
        } catch (RuntimeException exception) {
            return LookupResult.rejected("REAL_SUMMARY_CONVERSION_FAILED",
                    "Validated evidence could not be represented by the explanation contract");
        }
    }

    private RecordedSummary convert(Selection selection, EvidenceLookupResult.Evidence evidence) {
        MetricsExporter.ValidatedEndpointEvidence sender = evidence.sender();
        if (sender == null) {
            throw new IllegalArgumentException("real explanation evidence requires a sender record");
        }
        EndpointMetricsRecord senderRecord = sender.record();
        if (!selection.senderRunId().equals(senderRecord.runId())
                || !selection.applicationTransferId().toString()
                .equals(senderRecord.applicationTransferId())) {
            throw new IllegalArgumentException("sender identity does not match the trusted selection");
        }

        TransferMetrics metrics;
        RecordedSummary.EvidenceScope scope;
        String schemaVersion;
        String receiverRunId = null;
        Boolean receiverIntegrity = null;
        Instant capturedAt;
        List<String> references = new ArrayList<>();
        addReferences(references, sender);

        if (evidence.isReconciled()) {
            if (evidence.receiver() == null) {
                throw new IllegalArgumentException("reconciled evidence requires a receiver endpoint");
            }
            FinalMetricsSummary summary = evidence.reconciled().summary();
            metrics = summary.metrics();
            scope = RecordedSummary.EvidenceScope.RECONCILED;
            schemaVersion = summary.schemaVersion();
            receiverRunId = summary.receiverRunId();
            receiverIntegrity = evidence.receiver().record().metrics().getIntegrityVerified();
            capturedAt = parseInstant(summary.finalizedAtUtc());
            addReferences(references, evidence.receiver());
            references.add(relative(evidence.reconciled().summaryPath()));
            references.add(relative(evidence.reconciled().manifestPath()));
            require("reconciliation status", "VERIFIED", summary.reconciliationStatus());
            require("evidence completeness", "COMPLETE", summary.evidenceCompleteness());
            require("summary sender run", senderRecord.runId(), summary.senderRunId());
            require("summary application transfer", senderRecord.applicationTransferId(),
                    summary.applicationTransferId());
        } else if (evidence.isSenderOnly()) {
            metrics = senderRecord.metrics();
            scope = RecordedSummary.EvidenceScope.SENDER_FINAL;
            schemaVersion = senderRecord.schemaVersion();
            capturedAt = requiredInstant(metrics.getFinalizationTimestamp());
        } else {
            throw new IllegalArgumentException("unsupported available evidence scope");
        }

        requireRealMetrics(metrics);
        require("metric definition", MetricsSchema.METRIC_DEFINITION_VERSION,
                metrics.getMetricDefinitionVersion());
        require("application transfer", selection.applicationTransferId().toString(),
                metrics.getApplicationTransferId());
        require("protocol transfer", selection.protocolTransferId().toString(),
                metrics.getProtocolTransferId());

        String failureReason = senderRecord.failureReason();
        var metadata = new RecordedSummary.EvidenceMetadata(
                scope,
                RecordedSummary.EvidenceCompleteness.COMPLETE,
                RecordedSummary.FinalizationStatus.FINAL,
                senderRecord.applicationTransferId(),
                senderRecord.runId(),
                receiverRunId,
                selection.protocolTransferId(),
                schemaVersion,
                MetricsSchema.METRIC_DEFINITION_VERSION,
                senderRecord.terminalOutcome(),
                receiverIntegrity,
                failureCategory(failureReason),
                failureReason,
                references);

        return new RecordedSummary(
                selection.runId(),
                selection.applicationTransferId(),
                selection.protocolTransferId(),
                EvidenceSource.REAL,
                capturedAt,
                MetricsSchema.METRIC_DEFINITION_VERSION,
                scope == RecordedSummary.EvidenceScope.RECONCILED
                        ? "REAL validated reconciled transfer evidence"
                        : "REAL validated sender-final transfer evidence",
                fields(metrics, scope),
                metadata);
    }

    /** Explicit mapping: values are copied, never inferred or recalculated. */
    private static List<RecordedSummary.Field> fields(
            TransferMetrics metrics, RecordedSummary.EvidenceScope scope) {
        List<RecordedSummary.Field> fields = new ArrayList<>(26);
        fields.add(observed(metrics, "file_size_bytes", metrics.getFileSizeBytes(), "bytes",
                "File size recorded in the validated transfer context; not proof of delivery."));
        fields.add(observed(metrics, "payload_bytes_delivered", metrics.getPayloadBytesDelivered(), "bytes",
                "Unique receiver payload bytes accepted and written; unavailable from sender-only evidence."));
        fields.add(observed(metrics, "transfer_time_sec", metrics.getTransferTimeSec(), "s",
                "Sender monotonic duration from first START attempt to terminal decision; excludes analysis time."));
        fields.add(observed(metrics, "throughput_mbps", metrics.getThroughputMbps(), "Mbps",
                scope == RecordedSummary.EvidenceScope.RECONCILED
                        ? "Reconciled delivered-payload rate using receiver bytes and sender duration; not ACK-based sender rate."
                        : "Sender ACK-based payload progress rate; not receiver-delivered or reconciled throughput."));
        fields.add(observed(metrics, "packets_sent", metrics.getPacketsSent(), "packets",
                "Sender DATA attempts including retransmissions."));
        fields.add(observed(metrics, "packets_received", metrics.getPacketsReceived(), "packets",
                "Receiver valid DATA arrivals including duplicate arrivals; not unique delivery."));
        fields.add(observed(metrics, "packets_dropped", metrics.getPacketsDropped(), "packets",
                "Observed DATA drops from an identified impairment source; configured loss is not an observation."));
        fields.add(observed(metrics, "retransmissions", metrics.getRetransmissions(), "packets",
                "Sender DATA retransmission attempts."));
        fields.add(observed(metrics, "acks_received", metrics.getAcksReceived(), "packets",
                "Sender DATA-ACK arrivals including repeat or stale arrivals."));
        fields.add(observed(metrics, "packets_acked", metrics.getPacketsAcked(), "packets",
                "Distinct DATA sequences newly confirmed by valid cumulative ACK progress."));
        fields.add(observed(metrics, "packets_timed_out", metrics.getPacketsTimedOut(), "events",
                "Detected expired sender DATA attempts; timeouts do not establish congestion."));
        fields.add(observed(metrics, "packets_duplicated", metrics.getPacketsDuplicated(), "packets",
                "Receiver DATA arrivals already accepted; excludes ahead-of-gap discards."));
        fields.add(observed(metrics, "retransmission_ratio", metrics.getRetransmissionRatio(), "fraction",
                "Finalized retransmissions divided by DATA attempts; unavailable when its denominator is zero."));
        fields.add(observed(metrics, "udp_payload_bytes_emitted", metrics.getUdpPayloadBytesEmitted(), "bytes",
                "Actual UDP payload emissions in the stated evidence scope; excludes suppressed sends and headers."));
        fields.add(observed(metrics, "protocol_overhead_bytes", metrics.getProtocolOverheadBytes(), "bytes",
                "Reconciled emitted UDP payload bytes minus unique receiver-delivered payload bytes."));
        fields.add(observed(metrics, "protocol_overhead_ratio", metrics.getProtocolOverheadRatio(), "fraction",
                "Reconciled protocol overhead divided by emitted UDP payload bytes."));
        fields.add(observed(metrics, "rtt_sample_count", metrics.getRttSampleCount(), "samples",
                "Count of eligible sender DATA-to-ACK RTT samples."));
        fields.add(observed(metrics, "rtt_mean_ms", metrics.getRttMeanMs(), "ms",
                "Arithmetic mean of eligible sender RTT samples; not configured delay."));
        fields.add(observed(metrics, "rtt_p95_ms", metrics.getRttP95Ms(), "ms",
                "Nearest-rank p95 of eligible sender RTT samples."));
        fields.add(configured(metrics, "chunk_size_bytes", metrics.getChunkSizeBytes(), "bytes",
                "Effective DATA payload chunk size."));
        fields.add(configured(metrics, "window_bytes_requested", metrics.getWindowBytesRequested(), "bytes",
                "Resolved requested sender window byte budget."));
        fields.add(configured(metrics, "window_packets", metrics.getWindowPackets(), "packets",
                "Effective sender DATA window capacity."));
        fields.add(configured(metrics, "timeout_ms", metrics.getTimeoutMs(), "ms",
                "Configured DATA retransmission timeout; not an API timeout."));
        fields.add(configured(metrics, "retry_limit", metrics.getRetryLimit(), "rounds",
                "Configured consecutive DATA retransmission rounds allowed without progress."));
        fields.add(configured(metrics, "packet_loss_rate", metrics.getPacketLossRate(), "percent",
                "Configured simulated DATA loss percentage; not observed packet loss."));
        fields.add(configured(metrics, "delay_ms", metrics.getDelayMs(), "ms",
                "Configured outgoing DATA impairment delay; not measured RTT."));
        return List.copyOf(fields);
    }

    private static RecordedSummary.Field observed(
            TransferMetrics metrics, String id, Number value, String unit, String definition) {
        return field(metrics, id, value, unit, RecordedSummary.Kind.OBSERVED, definition);
    }

    private static RecordedSummary.Field configured(
            TransferMetrics metrics, String id, Number value, String unit, String definition) {
        return field(metrics, id, value, unit, RecordedSummary.Kind.CONFIGURED, definition);
    }

    private static RecordedSummary.Field field(
            TransferMetrics metrics, String id, Number value, String unit,
            RecordedSummary.Kind kind, String definition) {
        if (value == null) {
            String reason = metrics.getUnavailableReasons().get(id);
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("missing unavailable reason for " + id);
            }
            return new RecordedSummary.Field(id, null, unit, kind, definition, reason);
        }
        BigDecimal decimal = value instanceof Long || value instanceof Integer
                ? BigDecimal.valueOf(value.longValue())
                : BigDecimal.valueOf(value.doubleValue());
        try {
            return new RecordedSummary.Field(id, decimal, unit, kind, definition, null);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(id + "=" + decimal + " cannot be represented", exception);
        }
    }

    private static LookupResult mapState(EvidenceLookupResult result) {
        return switch (result.status()) {
            case PENDING -> LookupResult.pending(result.reasonCode(), result.reason());
            case INCOMPLETE -> LookupResult.incomplete(result.reasonCode(), result.reason());
            case UNAVAILABLE -> LookupResult.unavailable(result.reasonCode(), result.reason());
            case REJECTED -> LookupResult.rejected(result.reasonCode(), result.reason());
            case AVAILABLE -> throw new IllegalArgumentException("available evidence must be converted");
        };
    }

    private static UUID protocolId(EvidenceLookupResult.Evidence evidence) {
        String value = evidence.sender() != null
                ? evidence.sender().record().protocolTransferId()
                : evidence.receiver().record().protocolTransferId();
        return UUID.fromString(value);
    }

    private void addReferences(
            List<String> references, MetricsExporter.ValidatedEndpointEvidence endpoint) {
        references.add(relative(endpoint.endpointPath()));
        references.add(relative(endpoint.eventPath()));
        references.add(relative(endpoint.statePath()));
    }

    private String relative(Path path) {
        Path root = repository.loggingRoot().toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("validated source is outside the trusted logging root");
        }
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    private static void requireRealMetrics(TransferMetrics metrics) {
        if (metrics == null || metrics.getEvidenceSource() != TransferMetrics.EvidenceSource.REAL) {
            throw new IllegalArgumentException("only REAL authoritative metrics are supported");
        }
    }

    private static void require(String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalArgumentException(field + " does not match validated evidence");
        }
    }

    private static Instant parseInstant(String value) {
        return Instant.parse(value);
    }

    private static Instant requiredInstant(Instant value) {
        return Objects.requireNonNull(value, "finalization timestamp is required");
    }

    private static String failureCategory(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        int separator = reason.indexOf(':');
        String candidate = (separator < 0 ? reason : reason.substring(0, separator)).trim();
        return candidate.matches("[A-Z][A-Z0-9_]{1,119}") ? candidate : null;
    }
}
