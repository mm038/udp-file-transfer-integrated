package nettransfer.cli;

import com.google.gson.JsonObject;
import nettransfer.control.TransferMetrics;
import nettransfer.control.TransferService;
import nettransfer.control.TransferSnapshot;
import nettransfer.control.TransferState;
import nettransfer.control.command.CommandDispatcher;
import nettransfer.control.command.CommandParser;
import nettransfer.control.command.CommandProposal;
import nettransfer.control.command.CommandValidator;
import nettransfer.control.command.DispatchResult;
import nettransfer.control.command.TransferConfiguration;
import nettransfer.llm.GptClient;
import nettransfer.llm.GptException;
import nettransfer.llm.InterpretationRequest;
import nettransfer.llm.EvaluationCapture;
import nettransfer.explanation.ExplanationFlow;
import nettransfer.explanation.ExplanationRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Single console thread; the supplied service owns transfer concurrency and resources. */
public final class TransferCli {
    private final TransferService service;
    private final TransferConfiguration configuration;
    private final CommandDispatcher dispatcher;
    private final BufferedReader input;
    private final PrintWriter output;
    private final GptClient gpt;
    private final ExplanationFlow explanations;
    private final EvaluationCapture capture;
    private final List<InterpretationRequest.Turn> clarificationHistory = new ArrayList<>();
    private UUID pendingRequestId;
    private CommandDispatcher.Selection current = CommandDispatcher.Selection.none();
    private CommandDispatcher.Selection last = CommandDispatcher.Selection.none();

    public TransferCli(TransferService service, TransferConfiguration configuration,
                       Reader input, PrintWriter output) {
        this(service, configuration, input, output, request -> new CommandProposal.Unsupported(
                "Natural-language interpretation requires a configured GPT client. Use help for direct commands."));
    }

    /** Both the offline stub and HTTP adapter feed the same deterministic dispatcher. */
    public TransferCli(TransferService service, TransferConfiguration configuration,
                       Reader input, PrintWriter output, GptClient gpt) {
        this(service, configuration, input, output, gpt, ExplanationFlow.unavailable());
    }

    /** Explicit injection is required for synthetic offline explanation fixtures. */
    public TransferCli(TransferService service, TransferConfiguration configuration,
                       Reader input, PrintWriter output, GptClient gpt, ExplanationFlow explanations) {
        this(service, configuration, input, output, gpt, explanations, EvaluationCapture.disabled());
    }

    public TransferCli(TransferService service, TransferConfiguration configuration,
                       Reader input, PrintWriter output, GptClient gpt, ExplanationFlow explanations,
                       EvaluationCapture capture) {
        this.service = Objects.requireNonNull(service, "service");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.input = new BufferedReader(Objects.requireNonNull(input, "input"));
        this.output = Objects.requireNonNull(output, "output");
        this.gpt = Objects.requireNonNull(gpt, "gpt");
        this.explanations = Objects.requireNonNull(explanations, "explanations");
        this.capture = Objects.requireNonNull(capture, "capture");
        dispatcher = new CommandDispatcher(new CommandParser(), new CommandValidator(configuration), service);
    }

    /** EOF returns once; the launcher closes the service, including an active socket. */
    public void run() throws IOException {
        output.println("UDP transfer console. Type help for commands; natural-language requests use the configured interpreter.");
        while (true) {
            output.print("transfer> ");
            output.flush();
            String line = input.readLine();
            if (line == null) {
                refreshCurrent();
                output.println(current.transferId() == null ? "Input closed; leaving console."
                        : "Input closed while transfer is active; shutdown will interrupt it. Completion is unconfirmed.");
                output.flush();
                return;
            }
            if (!handleLine(line)) {
                return;
            }
        }
    }

    /** Handles one local user request. Call only from the console thread. */
    public boolean handleLine(String line) {
        String stripped = Objects.requireNonNull(line, "line").strip();
        if (stripped.isEmpty()) {
            return true;
        }
        refreshCurrent();
        String[] parts = stripped.split("\\s+", 2);
        String command = parts[0];
        String arguments = parts.length == 2 ? parts[1].strip() : "";
        // A direct command ends the pending conversation, avoiding stale start intent later.
        if (List.of("help", "catalog", "exit", "start_transfer", "status").contains(command)
                || (command.equals("explain") && arguments.startsWith("{"))) {
            clearClarification();
        }
        switch (command) {
            case "help" -> help();
            case "catalog" -> catalog();
            case "exit" -> {
                if (!arguments.isEmpty()) {
                    output.println("INVALID_COMMAND: exit takes no arguments.");
                } else if (current.transferId() != null) {
                    output.println("Transfer is still active; exit refused. Use status, then exit after it finishes.");
                } else {
                    output.println("Goodbye.");
                    output.flush();
                    return false;
                }
            }
            case "start_transfer" -> dispatch(command, arguments, defaultSelection());
            case "explain" -> {
                if (arguments.startsWith("{")) {
                    dispatch(command, arguments, defaultSelection());
                } else {
                    interpret(stripped);
                }
            }
            case "status" -> status(arguments);
            case "ask" -> interpret(arguments);
            default -> interpret(stripped);
        }
        output.flush();
        return true;
    }

    private void status(String arguments) {
        if (arguments.startsWith("{")) {
            dispatch("status", arguments, defaultSelection());
        } else if (arguments.isEmpty() || arguments.equals("this transfer")) {
            dispatch("status", "{\"transfer_id\":null}", defaultSelection());
        } else if (arguments.equals("current")) {
            if (current.transferId() == null) {
                output.println("Clarification: no transfer is currently active; use status last for the last terminal transfer.");
            } else {
                dispatch("status", "{\"transfer_id\":null}", current);
            }
        } else if (arguments.equals("last") || arguments.equals("last transfer")) {
            if (last.transferId() == null) {
                output.println("Clarification: there is no completed or failed transfer in this session yet.");
            } else {
                dispatch("status", "{\"transfer_id\":null}", last);
            }
        } else if (arguments.matches(".*\\s.*")) {
            output.println("Clarification: select one transfer using status current, status last, or status <UUID>.");
        } else {
            JsonObject json = new JsonObject();
            json.addProperty("transfer_id", arguments);
            dispatch("status", json.toString(), defaultSelection());
        }
    }

    private void dispatch(String command, String arguments, CommandDispatcher.Selection selection) {
        var proposal = new CommandProposal.Calls(List.of(new CommandProposal.ToolCall(command, arguments)));
        UUID requestId = UUID.randomUUID();
        DispatchResult decision = dispatcher.dispatch(requestId, proposal, selection);
        captureDecision(requestId, "direct_command", decision);
        render(decision);
    }

    private void interpret(String text) {
        UUID requestId = pendingRequestId == null ? UUID.randomUUID() : pendingRequestId;
        capture.beginInvocation(requestId);
        try {
            var request = new InterpretationRequest(requestId, text,
                    configuration.approvedFiles().keySet().stream().sorted().toList(),
                    configuration.approvedReceivers().keySet().stream().sorted().toList(),
                    current.transferId(), current.runId(), last.transferId(), last.runId(), clarificationHistory);
            CommandProposal proposal = gpt.interpret(request);
            if (proposal == null) {
                throw new GptException(GptException.Code.INVALID_RESPONSE);
            }
            DispatchResult result = dispatcher.dispatch(requestId, proposal, defaultSelection());
            if (result instanceof DispatchResult.Clarification clarification) {
                String question = clarification.question();
                if (question.isBlank() || question.length() > InterpretationRequest.MAX_TEXT_LENGTH) {
                    throw new GptException(GptException.Code.INVALID_RESPONSE);
                }
                captureDecision(requestId, "interpretation", result);
                // This label prevents free model text from becoming a factual transfer acknowledgement.
                output.println((proposal instanceof CommandProposal.Clarification
                        ? "Model clarification (no command dispatched): " : "Clarification: ") + question);
                if (clarificationHistory.size() + 2 <= InterpretationRequest.MAX_HISTORY_TURNS) {
                    clarificationHistory.add(new InterpretationRequest.Turn("user", text));
                    clarificationHistory.add(new InterpretationRequest.Turn("assistant", question));
                    pendingRequestId = requestId;
                } else {
                    clearClarification();
                    output.println("Clarification limit reached. Please restate the full request with file and receiver IDs.");
                }
            } else {
                captureDecision(requestId, "interpretation", result);
                clearClarification();
                render(result);
            }
        } catch (GptException e) {
            capture.decision(requestId, "interpretation", e.code().name(), null, null, e.getMessage());
            clearClarification();
            output.println("GPT " + e.code() + ": " + e.getMessage()
                    + " No command dispatched. Direct commands remain available.");
        } catch (IllegalArgumentException e) {
            capture.decision(requestId, "interpretation", "INVALID_COMMAND", null, null,
                    "Input could not be represented by the bounded command contract. No command dispatched.");
            clearClarification();
            output.println("INVALID_COMMAND: GPT input must be 1-4000 characters, with at most 100 IDs per catalogue"
                    + " and 128 characters per ID. No command dispatched.");
        }
    }

    private void clearClarification() {
        pendingRequestId = null;
        clarificationHistory.clear();
    }

    /** Record only explicit Java decisions and selected identities, never the service or its resources. */
    private void captureDecision(UUID requestId, String stage, DispatchResult decision) {
        UUID runId = null;
        UUID transferId = null;
        String detail;
        if (decision instanceof DispatchResult.Started started) {
            runId = started.acknowledgement().runId();
            transferId = started.acknowledgement().transferId();
            detail = "Java accepted exactly one transfer start.";
        } else if (decision instanceof DispatchResult.Status status) {
            runId = status.snapshot().runId();
            transferId = status.snapshot().transferId();
            detail = "Java returned state " + status.snapshot().state() + ".";
        } else if (decision instanceof DispatchResult.SummarySelected selected) {
            runId = selected.summary().finalSnapshot().runId();
            transferId = selected.summary().finalSnapshot().transferId();
            detail = "Java selected a frozen terminal outcome; explanation evidence is checked separately.";
        } else if (decision instanceof DispatchResult.Clarification) {
            detail = "Clarification required. No transfer operation dispatched.";
        } else if (decision instanceof DispatchResult.Unsupported) {
            detail = "Unsupported operation. No transfer operation dispatched.";
        } else if (decision instanceof DispatchResult.Rejected rejected) {
            detail = rejected.error().code() + ": " + rejected.error().message();
        } else {
            detail = "Java dispatch completed.";
        }
        capture.decision(requestId, stage, decision.getClass().getSimpleName(), runId, transferId, detail);
    }

    private void render(DispatchResult result) {
        if (result instanceof DispatchResult.Started started) {
            var accepted = started.acknowledgement();
            // The previous run can finish between refreshCurrent() and this start.
            // Acceptance under the service's single-active rule proves it is now terminal.
            if (current.transferId() != null) {
                last = current;
            }
            current = new CommandDispatcher.Selection(accepted.transferId(), accepted.runId());
            output.println("Start accepted [" + accepted.evidenceSource() + "]");
            output.println("transfer_id=" + accepted.transferId() + "; run_id=" + accepted.runId()
                    + "; protocol_transfer_id=" + value(accepted.protocolTransferId()));
            output.println(started.settingsDescription());
        } else if (result instanceof DispatchResult.Status status) {
            snapshot(status.snapshot());
        } else if (result instanceof DispatchResult.SummarySelected selected) {
            var summary = selected.summary();
            output.println("Selected frozen outcome; question: " + selected.question());
            snapshot(summary.finalSnapshot());
            output.println("integrity=" + summary.integrity() + "; outcome=" + summary.message());
            output.println("The selected outcome is in-memory; explanation evidence is loaded and validated separately.");
            explanation(explanations.explain(summary, selected.question()));
        } else if (result instanceof DispatchResult.Clarification clarification) {
            output.println("Clarification: " + clarification.question());
        } else if (result instanceof DispatchResult.Unsupported unsupported) {
            output.println("UNSUPPORTED_REQUEST: " + unsupported.reason());
        } else if (result instanceof DispatchResult.Rejected rejected) {
            output.println(rejected.error().code() + ": " + rejected.error().message());
        }
    }

    private void explanation(ExplanationFlow.Result result) {
        output.println(result.status() + ": " + result.message());
        var evidence = result.evidence();
        if (evidence != null) {
            output.println("Evidence [" + evidence.source() + "] " + evidence.label()
                    + "; definition_version=" + evidence.definitionVersion()
                    + "; prompt_version=" + ExplanationRequest.PROMPT_VERSION);
            output.println("run_id=" + evidence.runId() + "; transfer_id=" + evidence.transferId()
                    + "; protocol_transfer_id=" + value(evidence.protocolTransferId())
                    + "; captured_at=" + evidence.capturedAt());
            var metadata = evidence.metadata();
            output.println("evidence_scope=" + metadata.scope()
                    + "; provenance=" + evidence.source()
                    + "; completeness=" + metadata.completeness()
                    + "; finalization=" + metadata.finalizationStatus());
            output.println("sender_outcome=" + value(metadata.senderTerminalOutcome())
                    + "; receiver_integrity_verified=" + value(metadata.receiverIntegrityVerified())
                    + "; receiver_run_id=" + value(metadata.receiverRunId()));
            if (metadata.failureCategory() != null || metadata.failureReason() != null) {
                output.println("recorded_failure_category=" + value(metadata.failureCategory())
                        + "; recorded_failure_reason=" + value(metadata.failureReason()));
            }
            for (var field : evidence.fields()) {
                output.println("  " + field.id() + "="
                        + (field.value() == null ? "unavailable" : field.value().toPlainString())
                        + " " + field.unit() + " [" + field.kind() + "]; " + field.definition());
                if (field.value() == null) {
                    output.println("    Missing evidence: " + field.unavailableReason());
                }
            }
            if (!metadata.sourceReferences().isEmpty()) {
                output.println("Validated source references (relative to the trusted logging root):");
                metadata.sourceReferences().forEach(reference -> output.println("  " + reference));
            }
            if (evidence.source() == nettransfer.control.EvidenceSource.REAL) {
                output.println(metadata.scope() == nettransfer.explanation.RecordedSummary.EvidenceScope.RECONCILED
                        ? "Limits: reconciled evidence combines validated endpoint observations; the explanation does not independently verify the transfer or establish causation."
                        : "Limits: sender-final evidence is endpoint-local; ACK progress and ACK-based rate are not receiver delivery or reconciled throughput.");
            } else {
                output.println("Limits: synthetic draft evidence only. Configured impairment is not observed loss;"
                        + " retransmissions do not establish loss percentage; timeouts do not prove congestion."
                        + " A single run cannot establish which setting is faster.");
            }
        }
        var draft = result.draft();
        if (draft == null) {
            switch (result.status()) {
                case EXPLANATION_REJECTED -> output.println(
                        "The returned GPT answer was rejected by Java. Available Java evidence is shown above.");
                case EXPLANATION_UNAVAILABLE -> output.println(
                        "No accepted GPT answer is available; an explanation request may have occurred. Available Java evidence is shown above.");
                default -> output.println(
                        "No GPT explanation is generated: evidence checks prevented an explanation request. Available Java evidence is shown above.");
            }
            return;
        }
        output.println("Explanation draft (no command dispatched):");
        for (var observation : draft.observations()) {
            output.println("Observation: " + observation.text());
            for (var reference : observation.references()) {
                // Print the source value, not the model's copy, even after reference validation.
                var field = evidence.fields().stream().filter(f -> f.id().equals(reference.fieldId())).findFirst().orElseThrow();
                output.println("  [run=" + evidence.runId() + "; field=" + field.id() + "] "
                        + field.value().toPlainString() + " " + field.unit() + " [" + field.kind() + "]");
            }
        }
        draft.hypotheses().forEach(text -> output.println("Hypothesis (unproven): " + text));
        draft.limitations().forEach(text -> output.println("Limitation: " + text));
    }

    /** Observe terminal state before selecting a run, accepting a new start, or exiting. */
    private void refreshCurrent() {
        if (current.transferId() != null
                && service.status(current.transferId()).state() != TransferState.RUNNING) {
            last = current;
            current = CommandDispatcher.Selection.none();
        }
    }

    private CommandDispatcher.Selection defaultSelection() {
        return current.transferId() == null ? last : current;
    }

    private void snapshot(TransferSnapshot snapshot) {
        output.println("[" + snapshot.evidenceSource() + "] " + snapshot.state()
                + "; snapshot_at=" + snapshot.snapshotAt());
        output.println("transfer_id=" + snapshot.transferId() + "; run_id=" + snapshot.runId()
                + "; protocol_transfer_id=" + value(snapshot.protocolTransferId()));
        TransferMetrics metrics = snapshot.metrics();
        var live = metrics.liveSnapshot();
        var recorded = metrics.authoritativeMetrics();
        String finality = live.isProvisional() ? "PROVISIONAL" : "FINAL";
        output.println("engine_lifecycle=" + live.lifecycleState()
                + "; evidence_scope=SENDER_ENDPOINT_LOCAL; evidence_finality=" + finality
                + "; provenance=" + recorded.getEvidenceSource());
        metric("file_size_bytes", recorded.getFileSizeBytes(), reason(recorded, "file_size_bytes"));
        metric("sender_confirmed_acked_payload_bytes", live.senderAcknowledgedPayloadBytes(),
                liveReason(live.senderAcknowledgedPayloadBytes(), live.lifecycleState(),
                        "sender ACK progress has not been observed"));
        metric("sender_ack_progress_percent", progressPercent(
                        live.senderAcknowledgedPayloadBytes(), recorded.getFileSizeBytes()),
                progressReason(live.senderAcknowledgedPayloadBytes(), recorded.getFileSizeBytes()));
        metric("live_elapsed_time_sec", live.liveElapsedTimeSec(),
                liveReason(live.liveElapsedTimeSec(), live.lifecycleState(),
                        "sender timing has not started"));
        metric("sender_ack_based_rate_mbps", live.senderAcknowledgedRateMbps(),
                liveReason(live.senderAcknowledgedRateMbps(), live.lifecycleState(),
                        "ACK-based sender rate is not available"));
        metric("packets_sent", recorded.getPacketsSent(), reason(recorded, "packets_sent"));
        metric("retransmissions", recorded.getRetransmissions(), reason(recorded, "retransmissions"));
        metric("acks_received", recorded.getAcksReceived(), reason(recorded, "acks_received"));
        metric("distinct_packets_acked", recorded.getPacketsAcked(), reason(recorded, "packets_acked"));
        metric("packets_timed_out", recorded.getPacketsTimedOut(), reason(recorded, "packets_timed_out"));
        metric("rtt_sample_count", recorded.getRttSampleCount(), reason(recorded, "rtt_sample_count"));
        metric("rtt_mean_ms", recorded.getRttMeanMs(), reason(recorded, "rtt_mean_ms"));
        metric("rtt_p95_ms", recorded.getRttP95Ms(), reason(recorded, "rtt_p95_ms"));
        metric("endpoint_local_udp_payload_bytes_emitted",
                live.endpointEmission().localUdpPayloadBytesEmitted(),
                live.endpointEmission().localUdpPayloadBytesEmitted() == null
                        ? "endpoint-local UDP emission observation is unavailable" : null);
        output.println("endpoint_emission_accounting_complete="
                + live.endpointEmission().accountingComplete());
        if (snapshot.error() != null) {
            output.println(snapshot.error().code() + ": " + snapshot.error().message());
        }
    }

    private void metric(String name, Object value, String unavailableReason) {
        if (value == null) {
            output.println(name + "=unavailable; reason=" + value(unavailableReason));
        } else {
            output.println(name + "=" + metricValue(value));
        }
    }

    private static String metricValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Double decimal) {
            return BigDecimal.valueOf(decimal).stripTrailingZeros().toPlainString();
        }
        return value.toString();
    }

    private static String reason(nettransfer.metrics.TransferMetrics metrics, String field) {
        return metrics.getUnavailableReasons().getOrDefault(field,
                "the sender did not record this measurement");
    }

    private static String liveReason(Object value,
                                     nettransfer.metrics.LiveMetricsSnapshot.LifecycleState lifecycle,
                                     String reason) {
        return value == null ? reason + " in lifecycle " + lifecycle : null;
    }

    private static BigDecimal progressPercent(Long acknowledgedBytes, Long fileSizeBytes) {
        if (acknowledgedBytes == null || fileSizeBytes == null || fileSizeBytes == 0) {
            return null;
        }
        return BigDecimal.valueOf(acknowledgedBytes)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(fileSizeBytes), 2, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private static String progressReason(Long acknowledgedBytes, Long fileSizeBytes) {
        if (acknowledgedBytes == null) {
            return "sender-confirmed acknowledged bytes are unavailable";
        }
        if (fileSizeBytes == null) {
            return "validated file size is unavailable";
        }
        return fileSizeBytes == 0
                ? "a zero-byte file has no byte-progress denominator" : null;
    }

    private static String value(Object value) {
        return value == null ? "unavailable" : value.toString();
    }

    private void help() {
        output.println("""
                help | catalog | exit
                start_transfer {"file_id":"report","receiver_id":"receiver-a","window_bytes":null,"timeout_ms":null}
                status [current|last|UUID]
                status {"transfer_id":null}
                explain {"run_id":null,"question":"What was the outcome?"}
                ask <natural-language request> (also accepts ordinary sentences without ask)
                JSON fields are required; null settings use Java defaults. Use IDs from catalog.
                Bare status, 'status this transfer', and null IDs select the current active run, or last terminal run.
                'status current' requires an active run; 'status last' / 'status last transfer' selects the last terminal run.
                An explicit UUID can select older history; unknown or ambiguous references are rejected.
                Use ask to interpret a sentence beginning with a reserved direct command such as status.
                A direct command clears pending clarification context. Model text never acknowledges execution.
                Status shows sender-endpoint live observations; ACK-based rate is not reconciled throughput.
                Explain checks a selected frozen outcome, then loads matching validated evidence from the trusted log root.
                REAL evidence may be pending, incomplete, unavailable, or rejected and never falls back to fixtures.
                Synthetic analysis requires explicit evaluation/test injection; it never supplies real measurements.
                Exit is refused while a transfer is active. EOF/process shutdown interrupts active work.
                Start a receiver separately before each transfer; this console does not start one.
                """);
    }

    private void catalog() {
        output.println("Approved files (paths relative to " + configuration.applicationRoot() + "):");
        configuration.approvedFiles().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> output.println("  " + entry.getKey() + " = " + entry.getValue()));
        output.println("Approved receivers:");
        configuration.approvedReceivers().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> output.println("  " + entry.getKey() + " = "
                        + entry.getValue().getAddress().getHostAddress() + ":" + entry.getValue().getPort()));
    }
}
