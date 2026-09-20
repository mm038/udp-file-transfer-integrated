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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
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
    private CommandDispatcher.Selection current = CommandDispatcher.Selection.none();
    private CommandDispatcher.Selection last = CommandDispatcher.Selection.none();

    public TransferCli(TransferService service, TransferConfiguration configuration,
                       Reader input, PrintWriter output) {
        this.service = Objects.requireNonNull(service, "service");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.input = new BufferedReader(Objects.requireNonNull(input, "input"));
        this.output = Objects.requireNonNull(output, "output");
        dispatcher = new CommandDispatcher(new CommandParser(), new CommandValidator(configuration), service);
    }

    /** EOF returns once; the launcher closes the service, including an active socket. */
    public void run() throws IOException {
        output.println("UDP transfer console. Type help for commands; GPT is not connected yet.");
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
            case "start_transfer", "explain" -> dispatch(command, arguments, defaultSelection());
            case "status" -> status(arguments);
            default -> output.println("UNSUPPORTED_REQUEST: use help for direct commands. Natural-language interpretation is not connected yet.");
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
        // Each explicit console line is a new Java request; a future model retry must reuse its request ID.
        DispatchResult result = dispatcher.dispatch(UUID.randomUUID(), proposal, selection);
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
            output.println("No GPT explanation is generated. This in-memory outcome is not a persisted experiment log; real measurement integration is pending.");
        } else if (result instanceof DispatchResult.Clarification clarification) {
            output.println("Clarification: " + clarification.question());
        } else if (result instanceof DispatchResult.Unsupported unsupported) {
            output.println("UNSUPPORTED_REQUEST: " + unsupported.reason());
        } else if (result instanceof DispatchResult.Rejected rejected) {
            output.println(rejected.error().code() + ": " + rejected.error().message());
        }
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
        output.println("file_size_bytes=" + value(metrics.fileSizeBytes())
                + "; unique_payload_bytes_acked=" + value(metrics.uniquePayloadBytesAcked())
                + "; elapsed_ms=" + value(metrics.elapsedMillis())
                + "; total_chunks=" + value(metrics.totalChunks()));
        output.println("Progress percentage and throughput: unavailable (no integrated measurements).");
        if (metrics.unavailableReason() != null) {
            output.println("Unavailable metrics: " + metrics.unavailableReason());
        }
        if (snapshot.error() != null) {
            output.println(snapshot.error().code() + ": " + snapshot.error().message());
        }
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
                JSON fields are required; null settings use Java defaults. Use IDs from catalog.
                Bare status, 'status this transfer', and null IDs select the current active run, or last terminal run.
                'status current' requires an active run; 'status last' / 'status last transfer' selects the last terminal run.
                An explicit UUID can select older history; unknown or ambiguous references are rejected.
                Explain selects frozen outcome evidence only; GPT and real experiment metrics/logging are pending.
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
