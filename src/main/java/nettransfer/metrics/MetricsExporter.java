package nettransfer.metrics;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Random;
import java.util.UUID;

/** Persists endpoint metrics and reconciles two explicit, finalized endpoint run directories. */
public final class MetricsExporter {
    private static final Gson JSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerTypeAdapter(Instant.class, new InstantAdapter())
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private MetricsExporter() {}

    /** Writes one immutable endpoint record without replacing an existing record. */
    static Path writeEndpointRecord(Path runDirectory, Path eventLog,
                                    LiveMetricsSnapshot snapshot,
                                    boolean localSuccess, String terminalReason) throws IOException {
        Objects.requireNonNull(runDirectory, "run directory is required");
        Objects.requireNonNull(eventLog, "event log is required");
        Objects.requireNonNull(snapshot, "live metrics snapshot is required");
        if (!snapshot.isTerminal() || !snapshot.endpointEmission().accountingComplete()) {
            throw new IOException("endpoint metrics cannot finalize before the terminal accounting boundary");
        }
        TransferContext context = snapshot.context();
        Map<String, String> reasons = new LinkedHashMap<>(
                snapshot.metrics().getUnavailableReasons());
        reasons.remove("schema_version");
        reasons.remove("metric_definition_version");
        TransferMetrics metrics = snapshot.metrics().toBuilder()
                .schemaVersion(MetricsSchema.ENDPOINT_RECORD_SCHEMA_VERSION)
                .metricDefinitionVersion(MetricsSchema.METRIC_DEFINITION_VERSION)
                .evidenceSource(TransferMetrics.EvidenceSource.REAL)
                .unavailableReasons(reasons)
                .build();
        String endpointName = context.getEndpoint().name();
        EndpointMetricsRecord record = new EndpointMetricsRecord(
                MetricsSchema.ENDPOINT_RECORD_SCHEMA_VERSION,
                MetricsSchema.METRIC_DEFINITION_VERSION,
                TransferMetrics.EvidenceSource.REAL,
                endpointName,
                context.getRunId(),
                context.getExperimentId(),
                context.getApplicationTransferId(),
                context.getProtocolTransferId() == null
                        ? null : context.getProtocolTransferId().toString(),
                context.getOriginalFilename(),
                context.getFileAttribution(),
                context.getConfiguration(),
                metrics,
                snapshot.endpointEmission(),
                snapshot.senderObservations(),
                snapshot.receiverObservations(),
                snapshot.lifecycleState().name(),
                true,
                true,
                localSuccess ? "SUCCESS" : "FAILED",
                localSuccess ? null : terminalReason,
                metrics.getIntegrityEvidenceSource(),
                snapshot.capturedAt().toString(),
                Instant.now().toString(),
                eventLog.getFileName().toString(),
                "run-state.json");
        Path destination = runDirectory.resolve(
                context.getEndpoint() == TransferContext.Endpoint.SENDER
                        ? "endpoint-sender.json" : "endpoint-receiver.json");
        writeNewAtomically(destination, JSON.toJson(record) + "\n");
        return destination;
    }

    /**
     * Reconciles explicit sender and receiver directories and atomically publishes one final result.
     * Source logs and endpoint records are read only and remain untouched on any failure.
     */
    public static ExportResult reconcile(Path senderRunDirectory, Path receiverRunDirectory)
            throws IOException, EvidenceException {
        Path senderDirectory = normalizedDirectory(senderRunDirectory, "sender");
        Path receiverDirectory = normalizedDirectory(receiverRunDirectory, "receiver");
        ValidatedEndpointEvidence sender = readValidatedEndpoint(
                senderDirectory, TransferContext.Endpoint.SENDER);
        ValidatedEndpointEvidence receiver = readValidatedEndpoint(
                receiverDirectory, TransferContext.Endpoint.RECEIVER);
        validateAssociation(sender.record(), receiver.record());
        validateCrossRelationships(sender.record(), receiver.record());

        String protocolId = sender.record().protocolTransferId();
        Path senderParent = senderDirectory.getParent();
        Path receiverParent = receiverDirectory.getParent();
        if (senderParent == null || !senderParent.equals(receiverParent)) {
            throw new EvidenceException("RUN_SCOPE_MISMATCH",
                    "sender and receiver run directories must share one explicit experiment scope");
        }
        Path reconciledParent = senderParent.resolve("reconciled");
        Files.createDirectories(reconciledParent);
        Path finalDirectory = reconciledParent.resolve(protocolId);
        if (Files.exists(finalDirectory)) {
            throw new EvidenceException("SUMMARY_COLLISION",
                    "a reconciled result already exists for protocol UUID " + protocolId);
        }

        Instant finalizedAt = Instant.now();
        FinalMetricsSummary summary = buildSummary(sender, receiver, finalDirectory, finalizedAt);
        Path temporaryDirectory = reconciledParent.resolve(
                "." + protocolId + ".tmp-" + UUID.randomUUID());
        Files.createDirectory(temporaryDirectory);
        try {
            Path summaryPath = temporaryDirectory.resolve("summary.jsonl");
            Path manifestPath = temporaryDirectory.resolve("manifest.json");
            Files.writeString(summaryPath, JSON.toJson(summary) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Manifest manifest = buildManifest(
                    temporaryDirectory, sender, receiver, summary, finalizedAt);
            Files.writeString(manifestPath, JSON.toJson(manifest) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            publishDirectory(temporaryDirectory, finalDirectory);
        } catch (IOException | RuntimeException exception) {
            throw exception;
        }
        return new ExportResult(finalDirectory, finalDirectory.resolve("summary.jsonl"),
                finalDirectory.resolve("manifest.json"), summary);
    }

    public static EndpointMetricsRecord readEndpointRecord(Path endpointRecord) throws IOException {
        try {
            return JSON.fromJson(Files.readString(endpointRecord, StandardCharsets.UTF_8),
                    EndpointMetricsRecord.class);
        } catch (JsonParseException exception) {
            throw new IOException("malformed endpoint metrics record: " + endpointRecord, exception);
        }
    }

    /**
     * Reads a published reconciliation and revalidates its manifest, source endpoints,
     * identities, and calculated summary without changing any evidence.
     */
    public static ExportResult readValidatedReconciled(Path outputDirectory)
            throws IOException, EvidenceException {
        Path directory = normalizedDirectory(outputDirectory, "reconciled output");
        Path reconciledParent = directory.getParent();
        Path scope = reconciledParent == null ? null : reconciledParent.getParent();
        if (scope == null || !"reconciled".equals(reconciledParent.getFileName().toString())) {
            throw new EvidenceException("INVALID_RECONCILED_LOCATION",
                    "reconciled output is not under a scope reconciliation directory");
        }
        String protocolId = directory.getFileName().toString();
        parseUuid(protocolId, "reconciled directory protocol UUID");

        Path summaryPath = secureRegularFile(directory, directory, "summary.jsonl");
        Path manifestPath = secureRegularFile(directory, directory, "manifest.json");
        FinalMetricsSummary summary = readSingleJson(
                summaryPath, FinalMetricsSummary.class, "summary");
        Manifest manifest = readSingleJson(manifestPath, Manifest.class, "manifest");
        if (summary == null || manifest == null || summary.sourceEvidence() == null
                || summary.metrics() == null) {
            throw new EvidenceException("MALFORMED_RECONCILED_EVIDENCE",
                    "summary or manifest lacks required evidence");
        }
        requireEqual("summary protocol UUID", protocolId, summary.protocolTransferId());

        FinalMetricsSummary.SourceEvidence sources = summary.sourceEvidence();
        Path senderEndpoint = secureRegularFile(
                scope, directory, sources.senderEndpointRecord());
        Path receiverEndpoint = secureRegularFile(
                scope, directory, sources.receiverEndpointRecord());
        ValidatedEndpointEvidence sender = readValidatedEndpoint(
                senderEndpoint.getParent(), TransferContext.Endpoint.SENDER);
        ValidatedEndpointEvidence receiver = readValidatedEndpoint(
                receiverEndpoint.getParent(), TransferContext.Endpoint.RECEIVER);
        requireEqual("sender endpoint reference", sender.endpointPath().toRealPath(),
                senderEndpoint.toRealPath());
        requireEqual("receiver endpoint reference", receiver.endpointPath().toRealPath(),
                receiverEndpoint.toRealPath());
        requireEqual("sender event reference", sender.eventPath().toRealPath(),
                secureRegularFile(scope, directory, sources.senderEventLog()).toRealPath());
        requireEqual("receiver event reference", receiver.eventPath().toRealPath(),
                secureRegularFile(scope, directory, sources.receiverEventLog()).toRealPath());
        requireEqual("sender state reference", sender.statePath().toRealPath(),
                secureRegularFile(scope, directory, sources.senderRunState()).toRealPath());
        requireEqual("receiver state reference", receiver.statePath().toRealPath(),
                secureRegularFile(scope, directory, sources.receiverRunState()).toRealPath());
        validateAssociation(sender.record(), receiver.record());
        validateCrossRelationships(sender.record(), receiver.record());

        Instant finalizedAt;
        try {
            finalizedAt = Instant.parse(summary.finalizedAtUtc());
        } catch (RuntimeException exception) {
            throw new EvidenceException("INVALID_FINALIZATION_TIME",
                    "summary finalization time is invalid", exception);
        }
        FinalMetricsSummary expected = buildSummary(sender, receiver, directory, finalizedAt);
        if (!JSON.toJsonTree(expected).equals(JSON.toJsonTree(summary))) {
            throw new EvidenceException("SUMMARY_EVIDENCE_MISMATCH",
                    "summary does not match its validated endpoint evidence");
        }
        Manifest expectedManifest = buildManifest(directory, sender, receiver, expected, finalizedAt);
        if (!JSON.toJsonTree(expectedManifest).equals(JSON.toJsonTree(manifest))) {
            throw new EvidenceException("MANIFEST_EVIDENCE_MISMATCH",
                    "manifest does not match its validated summary and endpoint evidence");
        }
        return new ExportResult(directory, summaryPath, manifestPath, summary);
    }

    /** Reads and fully validates one finalized endpoint directory without modifying it. */
    public static ValidatedEndpointEvidence readValidatedEndpoint(
            Path directory, TransferContext.Endpoint expected) throws IOException, EvidenceException {
        String suffix = expected.name().toLowerCase(java.util.Locale.ROOT);
        Path endpointPath = directory.resolve("endpoint-" + suffix + ".json");
        if (!Files.isRegularFile(endpointPath)) {
            throw new EvidenceException("MISSING_ENDPOINT_RECORD", "missing " + endpointPath);
        }
        EndpointMetricsRecord record = readEndpointRecord(endpointPath);
        if (record == null) {
            throw new EvidenceException("MALFORMED_ENDPOINT_RECORD", "empty endpoint record");
        }
        requireEqual("endpoint record schema", MetricsSchema.ENDPOINT_RECORD_SCHEMA_VERSION,
                record.schemaVersion());
        requireEqual("metric definition", MetricsSchema.METRIC_DEFINITION_VERSION,
                record.metricDefinitionVersion());
        requireEqual("endpoint role", expected.name(), record.endpoint());
        if (record.metrics() == null || record.configuration() == null
                || record.endpointEmission() == null) {
            throw new EvidenceException("MALFORMED_ENDPOINT_RECORD",
                    expected + " endpoint lacks required metrics, configuration, or emissions");
        }
        requireEqual("metrics endpoint role", expected.name(),
                record.metrics().getEndpointAttribution());
        if (record.evidenceSource() != TransferMetrics.EvidenceSource.REAL
                || record.metrics().getEvidenceSource() != TransferMetrics.EvidenceSource.REAL) {
            throw new EvidenceException("PROVENANCE_MISMATCH", "endpoint evidence must be REAL");
        }
        if (!record.terminal() || !record.evidenceComplete()
                || !record.endpointEmission().accountingComplete()) {
            throw new EvidenceException("INCOMPLETE_ENDPOINT", expected + " endpoint is incomplete");
        }
        if (!("SUCCESS".equals(record.terminalOutcome())
                || "FAILED".equals(record.terminalOutcome()))) {
            throw new EvidenceException("TERMINAL_OUTCOME_INVALID",
                    expected + " endpoint has an unsupported terminal outcome");
        }
        requireEqual("terminal lifecycle", "SUCCESS".equals(record.terminalOutcome())
                ? "SUCCEEDED" : "FAILED", record.lifecycleState());
        requireEqual("endpoint run ID", record.runId(), record.metrics().getRunId());
        requireEqual("application transfer ID", record.applicationTransferId(),
                record.metrics().getApplicationTransferId());
        requireEqual("endpoint protocol UUID", record.protocolTransferId(),
                record.metrics().getProtocolTransferId());
        parseUuid(record.protocolTransferId(), expected + " protocol UUID");
        validateConfigurationProjection(record);
        validateUnavailableReasons(record.metrics());

        Path statePath = resolveChild(directory, record.runStateReference());
        Path eventPath = resolveChild(directory, record.eventLogReference());
        JsonObject state = parseObject(statePath, "run state");
        validateRunState(state, record, expected);
        EventEvidence eventEvidence = validateEvents(eventPath, state, record, expected);
        validateEventMetricConsistency(record, eventEvidence, expected);
        return new ValidatedEndpointEvidence(directory, endpointPath, eventPath, statePath, record);
    }

    private static void validateConfigurationProjection(EndpointMetricsRecord record)
            throws EvidenceException {
        TransferConfiguration configuration = record.configuration();
        TransferMetrics metrics = record.metrics();
        requireEqual("chunk size projection", configuration.getChunkSizeBytes(),
                metrics.getChunkSizeBytes());
        requireEqual("requested window projection", configuration.getWindowBytesRequested(),
                metrics.getWindowBytesRequested());
        requireEqual("packet window projection", configuration.getWindowPackets(),
                metrics.getWindowPackets());
        requireEqual("timeout projection", configuration.getTimeoutMs(), metrics.getTimeoutMs());
        requireEqual("retry-limit projection", configuration.getRetryLimit(), metrics.getRetryLimit());
        requireEqual("loss-rate projection", configuration.getPacketLossRate(),
                metrics.getPacketLossRate());
        requireEqual("delay projection", configuration.getDelayMs(), metrics.getDelayMs());
        requireEqual("scenario projection", configuration.getScenario(), metrics.getScenario());
        requireEqual("impairment-seed projection", configuration.getImpairmentSeed(),
                metrics.getImpairmentSeed());
        requireEqual("impairment mechanism projection", configuration.getImpairmentMechanism(),
                metrics.getImpairmentMechanism());
        validateImpairmentConfiguration(configuration);
    }

    private static void validateImpairmentConfiguration(TransferConfiguration configuration)
            throws EvidenceException {
        if (configuration.getImpairmentMechanism() == null) return; // Historical, unconfigured evidence.
        Double loss = configuration.getPacketLossRate();
        Double delay = configuration.getDelayMs();
        if (!"RECEIVE_DELIVERY_V1".equals(configuration.getImpairmentMechanism())
                || loss == null || !Double.isFinite(loss) || loss < 0 || loss > 100
                || delay == null || !Double.isFinite(delay) || delay < 0 || delay > 5_000
                || delay != Math.rint(delay) || configuration.getImpairmentSeed() == null
                || configuration.getScenario() == null || configuration.getScenario().isBlank()
                || configuration.getScenario().length() > 128
                || configuration.getScenario().chars().anyMatch(Character::isISOControl)) {
            throw new EvidenceException("INVALID_IMPAIRMENT_CONFIGURATION",
                    "receive-delivery evidence requires supported mechanism and complete valid settings");
        }
    }

    private static void validateAssociation(EndpointMetricsRecord sender,
                                            EndpointMetricsRecord receiver)
            throws EvidenceException {
        requireEqual("protocol UUID", sender.protocolTransferId(), receiver.protocolTransferId());
        parseUuid(sender.protocolTransferId(), "shared protocol UUID");
        conflictIfBothPresent("experiment ID", sender.experimentId(), receiver.experimentId());
        conflictIfBothPresent("application transfer ID",
                sender.applicationTransferId(), receiver.applicationTransferId());
        conflictIfBothPresent("original filename", sender.originalFilename(), receiver.originalFilename());
        conflictIfBothPresent("file size", sender.metrics().getFileSizeBytes(),
                receiver.metrics().getFileSizeBytes());
        conflictIfBothPresent("chunk size", sender.metrics().getChunkSizeBytes(),
                receiver.metrics().getChunkSizeBytes());
        TransferConfiguration senderConfig = sender.configuration();
        TransferConfiguration receiverConfig = receiver.configuration();
        if (!Objects.equals(senderConfig.getImpairmentMechanism(), receiverConfig.getImpairmentMechanism())
                || (senderConfig.getImpairmentMechanism() != null
                && (!Objects.equals(senderConfig.getPacketLossRate(), receiverConfig.getPacketLossRate())
                || !Objects.equals(senderConfig.getDelayMs(), receiverConfig.getDelayMs())
                || !Objects.equals(senderConfig.getScenario(), receiverConfig.getScenario())
                || !Objects.equals(senderConfig.getImpairmentSeed(), receiverConfig.getImpairmentSeed())))) {
            throw new EvidenceException("IMPAIRMENT_PROFILE_MISMATCH",
                    "sender and receiver must use the same enabled mechanism, loss, delay, scenario and seed");
        }
        if (receiver.receiverObservations() == null
                || !receiver.receiverObservations().startAccepted()
                || receiver.receiverObservations().establishedPeerAddress() == null
                || receiver.receiverObservations().establishedPeerPort() == null) {
            throw new EvidenceException("PEER_ATTRIBUTION_MISSING",
                    "receiver endpoint lacks established sender peer attribution");
        }
    }

    private static void validateCrossRelationships(EndpointMetricsRecord sender,
                                                   EndpointMetricsRecord receiver)
            throws EvidenceException {
        TransferMetrics s = sender.metrics();
        TransferMetrics r = receiver.metrics();
        if (s.getRetransmissions() != null && s.getPacketsSent() != null
                && s.getRetransmissions() > s.getPacketsSent()) {
            throw new EvidenceException("COUNTER_INCONSISTENCY",
                    "sender retransmissions exceed DATA attempts");
        }
        if (r.getPacketsDuplicated() != null && r.getPacketsReceived() != null
                && r.getPacketsDuplicated() > r.getPacketsReceived()) {
            throw new EvidenceException("COUNTER_INCONSISTENCY",
                    "receiver duplicates exceed valid DATA arrivals");
        }
        if (r.getPayloadBytesDelivered() != null && r.getFileSizeBytes() != null
                && r.getPayloadBytesDelivered() > r.getFileSizeBytes()) {
            throw new EvidenceException("COUNTER_INCONSISTENCY",
                    "receiver delivered bytes exceed observed file size");
        }
    }

    private static FinalMetricsSummary buildSummary(
            ValidatedEndpointEvidence senderEvidence, ValidatedEndpointEvidence receiverEvidence,
            Path outputDirectory, Instant finalizedAt)
            throws EvidenceException {
        EndpointMetricsRecord sender = senderEvidence.record();
        EndpointMetricsRecord receiver = receiverEvidence.record();
        TransferMetrics s = sender.metrics();
        TransferMetrics r = receiver.metrics();
        final long combinedEmission;
        try {
            combinedEmission = Math.addExact(
                    requiredNonNegative(sender.endpointEmission().localUdpPayloadBytesEmitted(),
                            "sender emission bytes"),
                    requiredNonNegative(receiver.endpointEmission().localUdpPayloadBytesEmitted(),
                            "receiver emission bytes"));
        } catch (ArithmeticException exception) {
            throw new EvidenceException("EMISSION_OVERFLOW", "combined emission bytes overflow", exception);
        }
        Long delivered = r.getPayloadBytesDelivered();
        MetricsCalculator.OverheadResult overhead;
        try {
            overhead = MetricsCalculator.protocolOverhead(combinedEmission, delivered, true);
        } catch (IllegalArgumentException exception) {
            throw new EvidenceException("OVERHEAD_INCONSISTENCY", exception.getMessage(), exception);
        }
        Double throughput = MetricsCalculator.throughputMbps(delivered, s.getTransferTimeSec());
        Double retransmissionRatio = MetricsCalculator.retransmissionRatio(
                s.getRetransmissions(), s.getPacketsSent());
        Long fileSize = firstNonNull(s.getFileSizeBytes(), r.getFileSizeBytes());
        boolean receiveImpairment = "RECEIVE_DELIVERY_V1".equals(s.getImpairmentMechanism());
        Long dropped = receiveImpairment ? r.getPacketsDropped() : s.getPacketsDropped();
        Map<String, String> unavailable = new LinkedHashMap<>();
        // Preserve reasons only for missing values, from the endpoint supplying each metric.
        copyReasonIfNull(unavailable, "file_size_bytes", fileSize, s);
        copyReasonIfNull(unavailable, "payload_bytes_delivered", delivered, r);
        copyReasonIfNull(unavailable, "transfer_time_sec", s.getTransferTimeSec(), s);
        copyReasonIfNull(unavailable, "transfer_success", s.getTransferSuccess(), s);
        copyReasonIfNull(unavailable, "integrity_verified", r.getIntegrityVerified(), r);
        copyReasonIfNull(unavailable, "packets_sent", s.getPacketsSent(), s);
        copyReasonIfNull(unavailable, "packets_received", r.getPacketsReceived(), r);
        copyReasonIfNull(unavailable, "retransmissions", s.getRetransmissions(), s);
        copyReasonIfNull(unavailable, "acks_received", s.getAcksReceived(), s);
        copyReasonIfNull(unavailable, "packets_acked", s.getPacketsAcked(), s);
        copyReasonIfNull(unavailable, "packets_timed_out", s.getPacketsTimedOut(), s);
        copyReasonIfNull(unavailable, "packets_duplicated", r.getPacketsDuplicated(), r);
        copyReasonIfNull(unavailable, "rtt_sample_count", s.getRttSampleCount(), s);
        copyReasonIfNull(unavailable, "rtt_mean_ms", s.getRttMeanMs(), s);
        copyReasonIfNull(unavailable, "rtt_p95_ms", s.getRttP95Ms(), s);
        if (dropped == null) {
            // Retain the established combined-scope reason for compatibility with saved summaries.
            unavailable.put("packets_dropped", receiveImpairment
                    ? r.getUnavailableReasons().get("packets_dropped")
                    : "no active simulator or observed drop evidence");
        }
        if (throughput == null) {
            unavailable.put("throughput_mbps",
                    delivered == null ? "receiver delivery evidence is unavailable"
                            : "sender transfer duration is zero or unavailable");
        }
        if (retransmissionRatio == null) {
            unavailable.put("retransmission_ratio",
                    s.getPacketsSent() == null || s.getRetransmissions() == null
                            ? "sender DATA attempt or retransmission counts are unavailable"
                            : "no DATA send attempts were observed");
        }
        if (overhead.protocolOverheadBytes() == null) {
            unavailable.put("protocol_overhead_bytes", "receiver delivery evidence is unavailable");
        }
        if (overhead.protocolOverheadRatio() == null) {
            unavailable.put("protocol_overhead_ratio", delivered == null
                    ? "receiver delivery evidence is unavailable"
                    : "combined emitted-byte denominator is zero");
        }
        copyReasonIfNull(unavailable, "chunk_size_bytes", s.getChunkSizeBytes(), s);
        copyReasonIfNull(unavailable, "window_bytes_requested", s.getWindowBytesRequested(), s);
        copyReasonIfNull(unavailable, "window_packets", s.getWindowPackets(), s);
        copyReasonIfNull(unavailable, "timeout_ms", s.getTimeoutMs(), s);
        copyReasonIfNull(unavailable, "retry_limit", s.getRetryLimit(), s);
        copyReasonIfNull(unavailable, "packet_loss_rate", s.getPacketLossRate(), s);
        copyReasonIfNull(unavailable, "delay_ms", s.getDelayMs(), s);
        copyReasonIfNull(unavailable, "scenario", s.getScenario(), s);
        copyReasonIfNull(unavailable, "impairment_seed", s.getImpairmentSeed(), s);

        // validateAssociation has already rejected conflicting non-null identities.
        String applicationId = firstNonNull(
                sender.applicationTransferId(), receiver.applicationTransferId());
        String experimentId = sender.experimentId() != null
                ? sender.experimentId() : receiver.experimentId();
        String failureReason = Boolean.TRUE.equals(s.getTransferSuccess())
                ? (Boolean.FALSE.equals(receiver.receiverObservations().localSuccess())
                        ? receiver.failureReason() : null)
                : s.getFailureReason();
        TransferMetrics combined = TransferMetrics.builder()
                .schemaVersion(MetricsSchema.SUMMARY_SCHEMA_VERSION)
                .metricDefinitionVersion(MetricsSchema.METRIC_DEFINITION_VERSION)
                .evidenceSource(TransferMetrics.EvidenceSource.REAL)
                .experimentId(experimentId)
                .fileSizeBytes(fileSize)
                .payloadBytesDelivered(delivered)
                .transferTimeSec(s.getTransferTimeSec())
                .throughputMbps(throughput)
                .transferSuccess(s.getTransferSuccess())
                .integrityVerified(r.getIntegrityVerified())
                .failureReason(failureReason)
                .packetsSent(s.getPacketsSent()).packetsReceived(r.getPacketsReceived())
                .packetsDropped(dropped).retransmissions(s.getRetransmissions())
                .acksReceived(s.getAcksReceived()).packetsAcked(s.getPacketsAcked())
                .packetsTimedOut(s.getPacketsTimedOut()).packetsDuplicated(r.getPacketsDuplicated())
                .retransmissionRatio(retransmissionRatio)
                .udpPayloadBytesEmitted(combinedEmission)
                .protocolOverheadBytes(overhead.protocolOverheadBytes())
                .protocolOverheadRatio(overhead.protocolOverheadRatio())
                .rttSampleCount(s.getRttSampleCount()).rttMeanMs(s.getRttMeanMs())
                .rttP95Ms(s.getRttP95Ms())
                .chunkSizeBytes(s.getChunkSizeBytes())
                .windowBytesRequested(s.getWindowBytesRequested())
                .windowPackets(s.getWindowPackets()).timeoutMs(s.getTimeoutMs())
                .retryLimit(s.getRetryLimit()).packetLossRate(s.getPacketLossRate())
                .delayMs(s.getDelayMs()).scenario(s.getScenario()).impairmentSeed(s.getImpairmentSeed())
                .impairmentMechanism(s.getImpairmentMechanism())
                .applicationTransferId(applicationId).protocolTransferId(sender.protocolTransferId())
                .endpointAttribution("COMBINED")
                .captureTimestamp(finalizedAt).finalizationTimestamp(finalizedAt)
                .integrityEvidenceSource(r.getIntegrityEvidenceSource())
                .unavailableReasons(unavailable).build();
        validateUnavailableReasons(combined);
        FinalMetricsSummary.SourceEvidence sources = new FinalMetricsSummary.SourceEvidence(
                relative(outputDirectory, senderEvidence.endpointPath()),
                relative(outputDirectory, receiverEvidence.endpointPath()),
                relative(outputDirectory, senderEvidence.eventPath()),
                relative(outputDirectory, receiverEvidence.eventPath()),
                relative(outputDirectory, senderEvidence.statePath()),
                relative(outputDirectory, receiverEvidence.statePath()));
        return new FinalMetricsSummary(
                MetricsSchema.SUMMARY_SCHEMA_VERSION,
                MetricsSchema.METRIC_DEFINITION_VERSION,
                TransferMetrics.EvidenceSource.REAL,
                "VERIFIED",
                "COMPLETE",
                sender.protocolTransferId(), sender.runId(), receiver.runId(),
                applicationId, experimentId, combined, sources, finalizedAt.toString());
    }

    private static Manifest buildManifest(Path outputDirectory, ValidatedEndpointEvidence sender,
                                          ValidatedEndpointEvidence receiver, FinalMetricsSummary summary,
                                          Instant finalizedAt) {
        return new Manifest(
                MetricsSchema.MANIFEST_SCHEMA_VERSION,
                MetricsSchema.METRIC_DEFINITION_VERSION,
                summary.protocolTransferId(), summary.experimentId(), summary.senderRunId(),
                summary.receiverRunId(), summary.applicationTransferId(),
                relative(outputDirectory, sender.endpointPath()),
                relative(outputDirectory, receiver.endpointPath()),
                relative(outputDirectory, sender.eventPath()),
                relative(outputDirectory, receiver.eventPath()),
                relative(outputDirectory, sender.statePath()),
                relative(outputDirectory, receiver.statePath()),
                "summary.jsonl", TransferMetrics.EvidenceSource.REAL,
                "VERIFIED", "COMPLETE", "FINAL", finalizedAt.toString());
    }

    private static void validateRunState(JsonObject state, EndpointMetricsRecord record,
                                         TransferContext.Endpoint endpoint)
            throws EvidenceException {
        requireJsonString(state, "schema_version", MetricsSchema.RUN_STATE_SCHEMA_VERSION);
        requireJsonString(state, "endpoint", endpoint.name());
        requireJsonString(state, "run_id", record.runId());
        requireJsonString(state, "protocol_transfer_id", record.protocolTransferId());
        String expectedState = "SUCCESS".equals(record.terminalOutcome())
                ? "FINALIZED_SUCCESS" : "FINALIZED_FAILED";
        requireJsonString(state, "recording_state", expectedState);
        if (!state.has("writer_flushed_and_closed")
                || !state.get("writer_flushed_and_closed").getAsBoolean()) {
            throw new EvidenceException("UNFINALIZED_LOG", endpoint + " writer was not finalized");
        }
        if (state.has("logging_failure") && !state.get("logging_failure").isJsonNull()) {
            throw new EvidenceException("LOGGING_FAILURE", endpoint + " run reports logging failure");
        }
    }

    private static EventEvidence validateEvents(Path eventPath, JsonObject state,
                                                EndpointMetricsRecord record,
                                                TransferContext.Endpoint endpoint)
            throws IOException, EvidenceException {
        List<String> lines = Files.readAllLines(eventPath, StandardCharsets.UTF_8);
        long emitted = 0;
        long dataAttempts = 0;
        long retransmissions = 0;
        long ackArrivals = 0;
        long acked = 0;
        long timeouts = 0;
        long dataReceived = 0;
        long duplicates = 0;
        long payloadWritten = 0;
        ImpairmentEvidence impairment = new ImpairmentEvidence(record, endpoint);
        Set<Long> originalDataSequences = new HashSet<>();
        Boolean integrityObserved = null;
        String terminalType = endpoint == TransferContext.Endpoint.SENDER
                ? ("SUCCESS".equals(record.terminalOutcome())
                        ? EventType.TRANSFER_SUCCEEDED.name() : EventType.TRANSFER_FAILED.name())
                : ("SUCCESS".equals(record.terminalOutcome())
                        ? EventType.RECEIVER_SUCCEEDED.name() : EventType.RECEIVER_FAILED.name());
        boolean terminalSeen = false;
        int terminalIndex = -1;
        for (int index = 0; index < lines.size(); index++) {
            JsonObject event;
            try {
                event = JsonParser.parseString(lines.get(index)).getAsJsonObject();
            } catch (RuntimeException exception) {
                throw new EvidenceException("MALFORMED_EVENT_LOG",
                        "malformed JSONL at line " + (index + 1), exception);
            }
            requireJsonString(event, "schema_version", MetricsSchema.EVENT_SCHEMA_VERSION);
            requireJsonString(event, "run_id", record.runId());
            requireJsonString(event, "endpoint", endpoint.name());
            requireJsonNullableString(event, "application_transfer_id",
                    record.applicationTransferId());
            if (!event.has("event_sequence") || event.get("event_sequence").getAsLong() != index) {
                throw new EvidenceException("EVENT_ORDER_INVALID",
                        "event sequence is not contiguous at line " + (index + 1));
            }
            if (event.has("protocol_transfer_id")
                    && !event.get("protocol_transfer_id").isJsonNull()
                    && !record.protocolTransferId().equals(
                            event.get("protocol_transfer_id").getAsString())) {
                throw new EvidenceException("EVENT_IDENTITY_CONFLICT",
                        "event protocol UUID conflicts at line " + (index + 1));
            }
            String type = event.get("event_type").getAsString();
            if (type.startsWith("IMPAIRMENT_")) {
                impairment.accept(type, event);
            }
            if (type.endsWith("_EMITTED")
                    && event.has("encoded_udp_payload_bytes")
                    && !event.get("encoded_udp_payload_bytes").isJsonNull()) {
                try {
                    emitted = Math.addExact(emitted,
                            event.get("encoded_udp_payload_bytes").getAsLong());
                } catch (ArithmeticException exception) {
                    throw new EvidenceException("EMISSION_OVERFLOW", "event emission sum overflow", exception);
                }
            }
            if (type.equals(EventType.DATA_SEND_ATTEMPT.name())) {
                dataAttempts++;
                if (event.has("retransmission")
                        && !event.get("retransmission").isJsonNull()
                        && !event.get("retransmission").getAsBoolean()
                        && event.has("sequence_number")
                        && !event.get("sequence_number").isJsonNull()) {
                    originalDataSequences.add(event.get("sequence_number").getAsLong());
                }
            }
            if (type.equals(EventType.DATA_RETRANSMISSION.name())) retransmissions++;
            if (type.equals(EventType.DATA_ACK_RECEIVED.name())) ackArrivals++;
            if (type.equals(EventType.DATA_ACK_ACCEPTED.name())
                    && !event.get("newly_acknowledged_packets").isJsonNull()) {
                acked += event.get("newly_acknowledged_packets").getAsLong();
            }
            if (type.equals(EventType.DATA_TIMEOUT.name())) timeouts++;
            if (type.equals(EventType.DATA_RECEIVED.name())) dataReceived++;
            if (type.equals(EventType.DATA_DUPLICATE.name())) duplicates++;
            if (type.equals(EventType.PAYLOAD_WRITTEN.name())) {
                try {
                    payloadWritten = Math.addExact(payloadWritten,
                            event.get("payload_bytes").getAsLong());
                } catch (ArithmeticException exception) {
                    throw new EvidenceException("PAYLOAD_OVERFLOW",
                            "written payload event sum overflow", exception);
                }
            }
            if (type.equals(EventType.INTEGRITY_VERIFIED.name())
                    || type.equals(EventType.INTEGRITY_FAILED.name())) {
                boolean observed = type.equals(EventType.INTEGRITY_VERIFIED.name());
                if (integrityObserved != null && integrityObserved != observed) {
                    throw new EvidenceException("INTEGRITY_EVIDENCE_CONFLICT",
                            "receiver event log contains conflicting integrity results");
                }
                integrityObserved = observed;
            }
            if (type.equals(terminalType)) {
                terminalSeen = true;
                terminalIndex = index;
            }
        }
        if (!terminalSeen) {
            throw new EvidenceException("MISSING_TERMINAL_EVENT",
                    endpoint + " event log lacks its declared terminal event");
        }
        if (terminalIndex != lines.size() - 1) {
            throw new EvidenceException("TERMINAL_EVENT_ORDER",
                    "terminal event is not the final endpoint event");
        }
        if (!state.has("event_count") || state.get("event_count").getAsLong() != lines.size()) {
            throw new EvidenceException("EVENT_COUNT_MISMATCH",
                    endpoint + " run-state event count does not match JSONL");
        }
        impairment.validateFinished();
        return new EventEvidence(emitted, dataAttempts, retransmissions, ackArrivals,
                acked, timeouts, dataReceived, duplicates, payloadWritten,
                originalDataSequences.size(), integrityObserved, impairment.started, impairment.drops);
    }

    private static void validateEventMetricConsistency(
            EndpointMetricsRecord record, EventEvidence events,
            TransferContext.Endpoint endpoint) throws EvidenceException {
        requireCount("emitted UDP payload bytes",
                record.endpointEmission().localUdpPayloadBytesEmitted(), events.emittedBytes());
        TransferMetrics metrics = record.metrics();
        if ("RECEIVE_DELIVERY_V1".equals(record.configuration().getImpairmentMechanism())) {
            if (endpoint == TransferContext.Endpoint.RECEIVER && events.impairmentStarted()) {
                requireCount("receive-delivery DATA drops", metrics.getPacketsDropped(), events.impairmentDrops());
            } else if (metrics.getPacketsDropped() != null) {
                throw new EvidenceException("IMPAIRMENT_DROP_SCOPE_MISMATCH",
                        "DATA drop counts require active receiver-side observation");
            }
        }
        if (endpoint == TransferContext.Endpoint.SENDER) {
            requireCount("DATA attempts", metrics.getPacketsSent(), events.dataAttempts());
            requireCount("DATA retransmissions", metrics.getRetransmissions(), events.retransmissions());
            requireCount("ACK arrivals", metrics.getAcksReceived(), events.ackArrivals());
            requireCount("newly acknowledged DATA", metrics.getPacketsAcked(), events.acked());
            requireCount("DATA timeouts", metrics.getPacketsTimedOut(), events.timeouts());
            if (metrics.getPacketsAcked() != null
                    && metrics.getPacketsAcked() > events.originalDataSequences()) {
                throw new EvidenceException("COUNTER_INCONSISTENCY",
                        "acknowledged DATA exceeds distinct original DATA sequences");
            }
        } else {
            requireCount("valid DATA arrivals", metrics.getPacketsReceived(), events.dataReceived());
            requireCount("duplicate DATA", metrics.getPacketsDuplicated(), events.duplicates());
            requireCount("written payload bytes", metrics.getPayloadBytesDelivered(), events.payloadWritten());
            if (!Objects.equals(metrics.getIntegrityVerified(), events.integrityObserved())
                    || record.receiverObservations() == null
                    || !Objects.equals(record.receiverObservations().integrityVerified(),
                            events.integrityObserved())) {
                throw new EvidenceException("INTEGRITY_EVIDENCE_MISMATCH",
                        "receiver integrity metric does not match its event evidence");
            }
        }
    }

    private static void requireCount(String field, Long metricValue, long eventValue)
            throws EvidenceException {
        if (metricValue == null || metricValue != eventValue) {
            throw new EvidenceException("EVENT_METRIC_MISMATCH",
                    field + " metric=" + metricValue + " events=" + eventValue);
        }
    }

    private static Path normalizedDirectory(Path path, String label) throws IOException {
        Objects.requireNonNull(path, label + " run directory is required");
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " run directory does not exist: " + normalized);
        }
        return normalized;
    }

    private static Path resolveChild(Path directory, String reference) throws EvidenceException {
        return secureRegularFile(directory, directory, reference);
    }

    private static Path secureRegularFile(Path allowedRoot, Path base, String reference)
            throws EvidenceException {
        if (reference == null || reference.isBlank()) {
            throw new EvidenceException("MISSING_REFERENCE", "endpoint evidence reference is unavailable");
        }
        Path root = allowedRoot.toAbsolutePath().normalize();
        Path resolved = base.resolve(reference).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new EvidenceException("UNSAFE_OR_MISSING_REFERENCE", "evidence reference escapes its scope");
        }
        Path current = root;
        for (Path component : root.relativize(resolved)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new EvidenceException("UNSAFE_OR_MISSING_REFERENCE",
                        "symbolic evidence references are not allowed");
            }
        }
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new EvidenceException("UNSAFE_OR_MISSING_REFERENCE", "invalid evidence reference: " + reference);
        }
        try {
            if (!resolved.toRealPath().startsWith(root.toRealPath())) {
                throw new EvidenceException("UNSAFE_OR_MISSING_REFERENCE",
                        "evidence reference escapes its trusted scope");
            }
            return resolved;
        } catch (IOException exception) {
            throw new EvidenceException("UNSAFE_OR_MISSING_REFERENCE",
                    "evidence reference is inaccessible", exception);
        }
    }

    private static <T> T readSingleJson(Path path, Class<T> type, String label)
            throws IOException, EvidenceException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.size() != 1 || lines.get(0).isBlank()) {
            throw new EvidenceException("MALFORMED_" + label.toUpperCase(),
                    label + " must contain exactly one JSON record");
        }
        try {
            return JSON.fromJson(lines.get(0), type);
        } catch (RuntimeException exception) {
            throw new EvidenceException("MALFORMED_" + label.toUpperCase(),
                    "malformed " + label, exception);
        }
    }

    private static JsonObject parseObject(Path path, String label) throws IOException, EvidenceException {
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new EvidenceException("MALFORMED_" + label.toUpperCase().replace(' ', '_'),
                    "malformed " + label + ": " + path, exception);
        }
    }

    private static void writeNewAtomically(Path destination, String content) throws IOException {
        if (Files.exists(destination)) {
            throw new IOException("refusing to overwrite existing artifact: " + destination);
        }
        Path temporary = destination.resolveSibling("." + destination.getFileName()
                + ".tmp-" + UUID.randomUUID());
        Files.writeString(temporary, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void publishDirectory(Path temporary, Path destination) throws IOException {
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, destination);
        }
    }

    private static void requireEqual(String field, Object expected, Object actual)
            throws EvidenceException {
        if (!Objects.equals(expected, actual)) {
            throw new EvidenceException("IDENTITY_CONFLICT",
                    field + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void conflictIfBothPresent(String field, Object first, Object second)
            throws EvidenceException {
        if (first != null && second != null && !first.equals(second)) {
            throw new EvidenceException("EVIDENCE_CONFLICT",
                    field + " conflicts: " + first + " versus " + second);
        }
    }

    private static void requireJsonString(JsonObject object, String field, String expected)
            throws EvidenceException {
        if (!object.has(field) || object.get(field).isJsonNull()
                || !expected.equals(object.get(field).getAsString())) {
            throw new EvidenceException("IDENTITY_CONFLICT",
                    field + " does not match expected value " + expected);
        }
    }

    private static void requireJsonNullableString(JsonObject object, String field, String expected)
            throws EvidenceException {
        if (!object.has(field)
                || (expected == null && !object.get(field).isJsonNull())
                || (expected != null && (object.get(field).isJsonNull()
                || !expected.equals(object.get(field).getAsString())))) {
            throw new EvidenceException("IDENTITY_CONFLICT",
                    field + " does not match endpoint evidence");
        }
    }

    private static void validateUnavailableReasons(TransferMetrics metrics)
            throws EvidenceException {
        requireReason(metrics, "file_size_bytes", metrics.getFileSizeBytes());
        requireReason(metrics, "payload_bytes_delivered", metrics.getPayloadBytesDelivered());
        requireReason(metrics, "transfer_time_sec", metrics.getTransferTimeSec());
        requireReason(metrics, "throughput_mbps", metrics.getThroughputMbps());
        requireReason(metrics, "transfer_success", metrics.getTransferSuccess());
        requireReason(metrics, "integrity_verified", metrics.getIntegrityVerified());
        requireReason(metrics, "packets_sent", metrics.getPacketsSent());
        requireReason(metrics, "packets_received", metrics.getPacketsReceived());
        requireReason(metrics, "packets_dropped", metrics.getPacketsDropped());
        requireReason(metrics, "retransmissions", metrics.getRetransmissions());
        requireReason(metrics, "acks_received", metrics.getAcksReceived());
        requireReason(metrics, "packets_acked", metrics.getPacketsAcked());
        requireReason(metrics, "packets_timed_out", metrics.getPacketsTimedOut());
        requireReason(metrics, "packets_duplicated", metrics.getPacketsDuplicated());
        requireReason(metrics, "retransmission_ratio", metrics.getRetransmissionRatio());
        requireReason(metrics, "udp_payload_bytes_emitted", metrics.getUdpPayloadBytesEmitted());
        requireReason(metrics, "protocol_overhead_bytes", metrics.getProtocolOverheadBytes());
        requireReason(metrics, "protocol_overhead_ratio", metrics.getProtocolOverheadRatio());
        requireReason(metrics, "rtt_sample_count", metrics.getRttSampleCount());
        requireReason(metrics, "rtt_mean_ms", metrics.getRttMeanMs());
        requireReason(metrics, "rtt_p95_ms", metrics.getRttP95Ms());
        requireReason(metrics, "chunk_size_bytes", metrics.getChunkSizeBytes());
        requireReason(metrics, "window_bytes_requested", metrics.getWindowBytesRequested());
        requireReason(metrics, "window_packets", metrics.getWindowPackets());
        requireReason(metrics, "timeout_ms", metrics.getTimeoutMs());
        requireReason(metrics, "retry_limit", metrics.getRetryLimit());
        requireReason(metrics, "packet_loss_rate", metrics.getPacketLossRate());
        requireReason(metrics, "delay_ms", metrics.getDelayMs());
        requireReason(metrics, "scenario", metrics.getScenario());
        requireReason(metrics, "impairment_seed", metrics.getImpairmentSeed());
    }

    private static void requireReason(TransferMetrics metrics, String field, Object value)
            throws EvidenceException {
        String reason = metrics.getUnavailableReasons().get(field);
        if (value == null && (reason == null || reason.isBlank())) {
            throw new EvidenceException("MISSING_UNAVAILABLE_REASON",
                    field + " is unavailable without an explanation");
        }
    }

    private static UUID parseUuid(String value, String field) throws EvidenceException {
        if (value == null) {
            throw new EvidenceException("MISSING_PROTOCOL_UUID", field + " is unavailable");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new EvidenceException("INVALID_PROTOCOL_UUID", field + " is invalid", exception);
        }
    }

    private static long requiredNonNegative(Long value, String field) throws EvidenceException {
        if (value == null || value < 0) {
            throw new EvidenceException("MISSING_OR_INVALID_MEASUREMENT", field + " is unavailable or negative");
        }
        return value;
    }

    private static void copyReasonIfNull(Map<String, String> target, String field,
                                         Object value, TransferMetrics source) throws EvidenceException {
        if (value == null) {
            requireReason(source, field, null);
            target.put(field, source.getUnavailableReasons().get(field));
        }
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private static String relative(Path from, Path to) {
        return from.toAbsolutePath().normalize().relativize(to.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    public record ExportResult(Path outputDirectory, Path summaryPath, Path manifestPath,
                               FinalMetricsSummary summary) {}

    public record ValidatedEndpointEvidence(
            Path directory, Path endpointPath, Path eventPath,
            Path statePath, EndpointMetricsRecord record) {}

    public static final class EvidenceException extends Exception {
        private final String code;

        public EvidenceException(String code, String message) {
            super(message);
            this.code = code;
        }

        public EvidenceException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String getCode() { return code; }
    }

    /** Checks every scoped decision has one terminal outcome before evidence is finalized. */
    private static final class ImpairmentEvidence {
        private final EndpointMetricsRecord record;
        private final TransferContext.Endpoint endpoint;
        private final boolean configured;
        private final Random random;
        private final Map<Long, PendingDecision> pending = new LinkedHashMap<>();
        private boolean started;
        private boolean finished;
        private boolean failed;
        private long nextDecision = 1;
        private long drops;

        private ImpairmentEvidence(EndpointMetricsRecord record, TransferContext.Endpoint endpoint) {
            this.record = record;
            this.endpoint = endpoint;
            configured = "RECEIVE_DELIVERY_V1".equals(record.configuration().getImpairmentMechanism());
            random = configured ? new Random(record.configuration().getImpairmentSeed()) : null;
        }

        private void accept(String type, JsonObject event) throws EvidenceException {
            if (!configured || finished) reject("impairment events occur outside an enabled, unfinished scope");
            requireJsonString(event, "protocol_transfer_id", record.protocolTransferId());
            if (type.equals(EventType.IMPAIRMENT_STARTED.name())) {
                if (started) reject("impairment scope started more than once");
                requireJsonString(event, "direction", TransferEvent.Direction.LOCAL.name());
                requireJsonString(event, "event_outcome", "RECEIVE_DELIVERY_V1");
                started = true;
                return;
            }
            if (!started) reject("impairment decision occurs before its scope starts");
            if (type.equals(EventType.IMPAIRMENT_FINISHED.name())) {
                requireJsonString(event, "direction", TransferEvent.Direction.LOCAL.name());
                requireJsonString(event, "event_outcome", "DECISIONS_FINALIZED");
                if (!pending.isEmpty()) reject("delayed impairment decisions are not finalized");
                finished = true;
                return;
            }
            requireJsonString(event, "direction", TransferEvent.Direction.INBOUND.name());
            String message = endpoint == TransferContext.Endpoint.RECEIVER ? "DATA" : "ACK";
            requireJsonString(event, "message_type", message);
            long sequence = requiredInteger(event, endpoint == TransferContext.Endpoint.RECEIVER
                    ? "sequence_number" : "ack_number");
            if (sequence < -1 || sequence > Integer.MAX_VALUE
                    || (endpoint == TransferContext.Endpoint.RECEIVER && sequence < 0)) {
                reject("impairment sequence is outside the wire field bounds");
            }
            long decision = requiredInteger(event, "impairment_decision_index");
            long delay = requiredInteger(event, "impairment_delay_ms");
            if (delay != record.configuration().getDelayMs().longValue()) {
                reject("impairment event delay conflicts with configured per-direction delay");
            }
            PendingDecision prior = pending.get(decision);
            boolean delayed = type.equals(EventType.IMPAIRMENT_DELAYED.name());
            boolean delivered = type.equals(EventType.IMPAIRMENT_DELIVERED.name());
            boolean dropped = type.equals(EventType.IMPAIRMENT_DROPPED.name());
            boolean cancelled = type.equals(EventType.IMPAIRMENT_CANCELLED.name());
            boolean failure = type.equals(EventType.IMPAIRMENT_FAILED.name());
            if (!(delayed || delivered || dropped || cancelled || failure)) reject("unknown impairment event");
            String action = delayed ? "DELAY" : delivered ? "DELIVER" : dropped ? "DROP"
                    : cancelled ? "CANCEL" : "QUEUE_OVERFLOW";
            if (!event.has("event_outcome") || event.get("event_outcome").isJsonNull()
                    || !action.equals(event.get("event_outcome").getAsString())) {
                reject("impairment event type and recorded action conflict");
            }
            if (failure && delay == 0) reject("zero-delay profile has no queue to overflow");
            if (prior != null) {
                if (prior.sequence() != sequence || !(delivered || cancelled)) {
                    reject("delayed decision has conflicting sequence or terminal outcome");
                }
                pending.remove(decision);
            } else {
                if (decision != nextDecision++) reject("impairment decision indices are missing or repeated");
                if (endpoint == TransferContext.Endpoint.RECEIVER) {
                    boolean expectedDrop = random.nextDouble() < record.configuration().getPacketLossRate() / 100.0;
                    if (dropped != expectedDrop) reject("DATA drop decision disagrees with configured seed and probability");
                }
                if (cancelled || (delivered && delay > 0)) reject("delayed decision is missing its queue event");
                if (delayed) {
                    if (delay == 0) reject("zero-delay profile cannot queue a delay");
                    pending.put(decision, new PendingDecision(sequence));
                }
            }
            if (dropped) {
                if (endpoint != TransferContext.Endpoint.RECEIVER
                        || record.configuration().getPacketLossRate() == 0) {
                    reject("DATA drops conflict with endpoint or configured zero loss");
                }
                drops++;
            }
            if (failure) failed = true;
        }

        private void validateFinished() throws EvidenceException {
            if (!configured) return;
            boolean receiverNeverAccepted = endpoint == TransferContext.Endpoint.RECEIVER
                    && record.receiverObservations() != null && !record.receiverObservations().startAccepted()
                    && "FAILED".equals(record.terminalOutcome());
            if (!started && receiverNeverAccepted) return;
            if (!started || !finished || !pending.isEmpty()) reject("configured impairment observation is incomplete");
            if (failed && "SUCCESS".equals(record.terminalOutcome())) {
                reject("successful endpoint cannot hide an impairment queue failure");
            }
        }

        private static long requiredInteger(JsonObject event, String field) throws EvidenceException {
            try {
                JsonElement value = event.get(field);
                if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException();
                return value.getAsBigDecimal().longValueExact();
            } catch (RuntimeException exception) {
                throw new EvidenceException("INVALID_IMPAIRMENT_EVENT", "missing or noninteger " + field);
            }
        }

        private static void reject(String reason) throws EvidenceException {
            throw new EvidenceException("INVALID_IMPAIRMENT_EVENT", reason);
        }

        private record PendingDecision(long sequence) {}
    }

    private record EventEvidence(long emittedBytes, long dataAttempts, long retransmissions,
                                 long ackArrivals, long acked, long timeouts,
                                 long dataReceived, long duplicates, long payloadWritten,
                                 long originalDataSequences, Boolean integrityObserved,
                                 boolean impairmentStarted, long impairmentDrops) {}

    private record Manifest(
            String schemaVersion,
            String metricDefinitionVersion,
            String protocolTransferId,
            String experimentId,
            String senderRunId,
            String receiverRunId,
            String applicationTransferId,
            String senderEndpointRecord,
            String receiverEndpointRecord,
            String senderEventLog,
            String receiverEventLog,
            String senderRunState,
            String receiverRunState,
            String summary,
            TransferMetrics.EvidenceSource evidenceSource,
            String identityReconciliationStatus,
            String evidenceCompleteness,
            String exportState,
            String finalizedAtUtc) {}

    private static final class InstantAdapter
            implements JsonSerializer<Instant>, JsonDeserializer<Instant> {
        @Override
        public JsonElement serialize(Instant source, Type type,
                                     JsonSerializationContext context) {
            return source == null ? null : context.serialize(source.toString());
        }

        @Override
        public Instant deserialize(JsonElement json, Type type,
                                   JsonDeserializationContext context) throws JsonParseException {
            return json == null || json.isJsonNull() ? null : Instant.parse(json.getAsString());
        }
    }
}
