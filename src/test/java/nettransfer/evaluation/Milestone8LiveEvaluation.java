package nettransfer.evaluation;

import com.google.gson.*;
import nettransfer.control.*;
import nettransfer.control.command.*;
import nettransfer.control.engine.RealTransferService;
import nettransfer.control.simulation.FakeTransferService;
import nettransfer.explanation.*;
import nettransfer.integrity.FileHashUtil;
import nettransfer.llm.*;
import nettransfer.net.UdpChannel;
import nettransfer.transfer.ReceiverEngine;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Explicit main, never a JUnit test. All fixtures and this runner stay outside the production JAR. */
public final class Milestone8LiveEvaluation {
    static final Gson JSON = new GsonBuilder().serializeNulls()
            .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (v, t, c) -> new JsonPrimitive(v.toString()))
            .create();
    private static final String RUBRIC = "Review meaning, not exact wording. Reject invented numbers, unsupported causal "
            + "claims, calculated missing metrics, configured loss presented as observed loss, ACK arrivals presented "
            + "as delivered bytes, or protocol progress presented as verified integrity. Every missing field needs "
            + "its reason. Hypotheses must be qualified and identify evidence needed. SYNTHETIC must stay explicit.";

    record Case(String id, String prompt, String context, String command, String arguments,
                String javaDecision, String expectation) { }

    static List<Case> commandCases() {
        return List.of(
                new Case("defaults", "Send demo to receiver-a.", "none", "start_transfer",
                        startArgs(null, null), "Started", "Use null optional settings; Java applies 1024 bytes/1 slot and 200 ms. No optional clarification."),
                new Case("paraphrase-kib", "Please ship demo over to receiver-a using a 64 KiB window and a 0.5 second timeout.",
                        "none", "start_transfer", startArgs(65536L, 500), "Started", "Exact unit conversion; 64 packet slots."),
                new Case("decimal-kb", "Send demo to receiver-a with a 64 KB window.", "none", "start_transfer",
                        startArgs(64000L, null), "Started", "KB is decimal; Java rounds the budget down to 62 packet slots."),
                new Case("missing-essentials", "Send a file.", "none", null, null, "Clarification",
                        "Ask for file and receiver; do not guess single catalogue entries. Optional settings need no question."),
                new Case("ambiguous-units", "Send demo to receiver-a with a window of 64.", "none", null, null,
                        "Clarification", "Ask for units; do not guess bytes, packets, KB or KiB."),
                new Case("current-absent", "How is the current active transfer doing?", "last", null, null,
                        "Clarification", "No active transfer; never substitute the last terminal run."),
                new Case("last-while-active", "How did the last transfer finish?", "both", "status",
                        "{\"transfer_id\":\"$LAST\"}", "Status", "Select the last terminal ID even though another transfer is active."),
                new Case("ambiguous-reference", "Show the current or last transfer; I have not decided which one.", "both",
                        null, null, "Clarification", "Ask which run; do not silently choose current or last."),
                new Case("status-empty", "What is the transfer status?", "none", "status",
                        "{\"transfer_id\":null}", "Clarification", "A null-ID status proposal lets Java request a selection; model clarification is also safe."),
                new Case("unsupported-delete", "Delete demo from disk.", "none", null, null, "Clarification",
                        "No tool call. Briefly describe start/status/explain only; no OS deletion advice or cancellation suggestion."),
                new Case("select-explanation", "Explain why the last transfer was slow.", "last", "explain",
                        "{\"run_id\":\"$LAST\"}", "SummarySelected", "Select last run and preserve the question; do not invent an explanation in the command client."),
                new Case("invalid-timeout", "Send demo to receiver-a with a timeout of 1 ms.", "none", "start_transfer",
                        startArgs(null, 1), "Rejected", "Preserve the explicit value. Java must reject INVALID_PARAMETER with zero starts; no silent clamping."));
    }

    static List<String> caseIds(String batch) {
        return switch (batch) {
            case "smoke" -> List.of("real-start", "real-status", "missing-essentials", "unsupported-delete", "loss-acks", "missing-performance");
            case "commands" -> commandCases().stream().map(Case::id).toList();
            case "explanations" -> List.of("loss-acks", "missing-performance", "integrity-failure", "unconfirmed-outcome");
            case "real" -> List.of("real-start", "real-status");
            default -> throw new IllegalArgumentException("Unknown batch; use smoke, commands, explanations or real.");
        };
    }

    record Options(boolean live, String batch, int maxCalls) {
        static Options parse(String[] args) {
            boolean live = false;
            String batch = "smoke";
            int maxCalls = 0;
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < args.length; i++) {
                String option = args[i];
                if (!seen.add(option)) throw new IllegalArgumentException("Duplicate option.");
                switch (option) {
                    case "--live" -> live = true;
                    case "--batch" -> { if (++i == args.length) throw new IllegalArgumentException("Missing batch."); batch = args[i]; }
                    case "--max-calls" -> { if (++i == args.length) throw new IllegalArgumentException("Missing call limit."); maxCalls = Integer.parseInt(args[i]); }
                    default -> throw new IllegalArgumentException("Use --batch NAME and, only for paid execution, --live --max-calls N.");
                }
            }
            int planned = caseIds(batch).size();
            if (maxCalls < 0 || maxCalls > 16 || live && maxCalls < planned)
                throw new IllegalArgumentException("Live execution requires an explicit sufficient --max-calls (maximum 16). No calls made.");
            return new Options(live, batch, maxCalls);
        }
    }

    public static void main(String[] args) {
        try {
            execute(Options.parse(args), System.getenv());
        } catch (GptException e) {
            System.err.println("Evaluation stopped: " + e.code() + ". No automatic retry. Keep credentials out of reports.");
            System.exit(2);
        } catch (Exception e) {
            // Do not dump exception messages, network responses, environment maps or stack traces.
            System.err.println("Evaluation stopped (" + e.getClass().getSimpleName() + "). Check options, build, and local output access; no automatic retry.");
            System.exit(2);
        }
    }

    static void execute(Options options, Map<String, String> environment) throws Exception {
        GptSettings configured = GptSettings.fromEnvironment(environment);
        String configuredKey = environment.get("OPENAI_API_KEY");
        if (!configured.model().equals(redactText(configured.model(), configuredKey == null ? null : configuredKey.strip())))
            throw new GptException(GptException.Code.INVALID_CONFIGURATION);
        List<String> ids = caseIds(options.batch());
        System.out.println("Milestone 8 " + (options.live() ? "LIVE" : "PREVIEW - no API calls or UDP transfers")
                + "; model=" + configured.model() + "; batch=" + options.batch() + "; planned API calls=" + ids.size());
        System.out.println("One HTTP attempt per call; max_output_tokens=4096 each. API latency is not transfer timing.");
        ids.forEach(id -> {
            System.out.println("  " + id);
            var command = commandCases().stream().filter(c -> c.id().equals(id)).findFirst();
            if (command.isPresent()) {
                System.out.println("    Prompt: " + command.get().prompt());
                System.out.println("    Expect: " + command.get().expectation());
            } else if (id.equals("real-start")) {
                System.out.println("    Prompt: Send demo to receiver-a with a 64 KiB window.");
                System.out.println("    Expect: 65536 bytes, 64 slots, default 200 ms; REAL loopback completion and SHA-256 match.");
            } else if (id.equals("real-status")) {
                System.out.println("    Prompt: How did the last transfer finish?");
                System.out.println("    Expect: exact last application ID; Java COMPLETED; missing measurements stay unavailable.");
            } else {
                System.out.println("    Prompt: " + explanationPrompt(id));
                System.out.println("    Expect: " + RUBRIC);
            }
        });
        if (!options.live()) return; // A present key alone never enables this runner.
        String key = environment.get("OPENAI_API_KEY");
        if (key == null || key.isBlank()) throw new GptException(GptException.Code.MISSING_CREDENTIALS);
        GptSettings settings = new GptSettings(configured.model(), configured.connectTimeout(), configured.requestTimeout(), 1);
        Path base = Path.of("target", "milestone-8-eval").toAbsolutePath().normalize();
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, Instant.now().toString().replace(':', '-') + "-");
        System.out.println("Evaluation artifact directory: " + directory);
        Path input = directory.resolve("data/input/demo.txt");
        Files.createDirectories(input.getParent());
        Files.writeString(input, "Milestone 8 isolated loopback demo.\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        try (Report report = new Report(directory, key)) {
            List<ApiCallObservation> calls = new ArrayList<>();
            Budget budget = new Budget(options.maxCalls());
            GptClient client = new ResponsesGptClient(key, settings, calls::add);
            GptClient bounded = request -> { budget.take(); return client.interpret(request); };
            ExplanationClient explanation = new ResponsesExplanationClient(key, settings, calls::add);
            ExplanationClient boundedExplanation = request -> { budget.take(); return explanation.explain(request); };
            report.write("batch", object("evaluation_version", "milestone-8-v1", "started_at", Instant.now(),
                    "requested_model", settings.model(), "command_prompt_version", ResponsesGptClient.PROMPT_SCHEMA_VERSION,
                    "explanation_prompt_version", ExplanationRequest.PROMPT_VERSION, "batch", options.batch(), "planned_cases", ids,
                    "max_api_calls", options.maxCalls(), "max_attempts", 1, "max_output_tokens_per_call", 4096,
                    "request_timeout_ms", settings.requestTimeout().toMillis(), "manual_review", "PENDING",
                    "notice", "Command simulations and explanation fixtures are SYNTHETIC. Only real-* uses loopback UDP. No impaired experiment or measured-summary integration."));
            deterministicRejection(directory, report);
            boolean stopped = false;
            int completed = 0;
            if (ids.contains("real-start")) {
                RealBatchResult realResult = realDemo(directory, bounded, calls, report);
                completed += realResult.completed();
                stopped = realResult.stopped();
            }
            for (String id : ids) {
                if (stopped || id.startsWith("real-")) continue;
                int before = calls.size();
                JsonObject result;
                var selected = commandCases().stream().filter(c -> c.id().equals(id)).findFirst();
                if (selected.isPresent()) result = command(directory, selected.get(), bounded);
                else result = explanation(id, boundedExplanation);
                result.add("api_calls", JSON.toJsonTree(calls.subList(before, calls.size())));
                report.write("case", result);
                completed++;
                stopped = result.has("api_failure"); // Do not spend through provider failures.
            }
            report.write("batch_end", object("finished_at", Instant.now(), "completed_cases", completed,
                    "planned_cases", ids.size(), "attempted_api_calls", budget.used, "stopped_early", stopped,
                    "manual_review", "PENDING", "milestone_8_complete", false));
        }
        System.out.println("Saved UTF-8 evaluation records: " + directory);
        System.out.println("Review report.jsonl and fill review.md. Automatic checks do not approve model prose or complete milestone 8.");
    }

    static JsonObject command(Path root, Case item, GptClient client) throws IOException {
        Scene scene = scene(root, item.context());
        InterpretationRequest request = new InterpretationRequest(UUID.randomUUID(), item.prompt(), List.of("demo"),
                List.of("receiver-a"), scene.current, scene.current, scene.last, scene.last, List.of());
        JsonObject row = object("id", item.id(), "evidence_source", "SYNTHETIC", "prompt", item.prompt(),
                "java_context", request, "expectation", item.expectation(), "expected_command", item.command(),
                "expected_arguments", expectedArguments(item, scene.last), "expected_java_decision", item.javaDecision(),
                "manual_review", "PENDING");
        try {
            CommandProposal proposal = client.interpret(request);
            DispatchResult decision = scene.dispatcher.dispatch(request.requestId(), proposal, scene.selection());
            row.add("proposal", proposal(proposal));
            row.add("java_decision", decision(decision));
            row.addProperty("service_start_count", scene.service.starts);
            row.addProperty("automatic_pass", matches(item, proposal, decision, scene.last)
                    && scene.service.starts == (decision instanceof DispatchResult.Started ? 1 : 0));
        } catch (GptException e) {
            row.addProperty("api_failure", e.code().name());
            row.addProperty("automatic_pass", false);
            row.addProperty("service_start_count", scene.service.starts);
        } catch (RuntimeException e) {
            row.addProperty("api_failure", "CLIENT_OR_EVALUATOR_FAILURE");
            row.addProperty("automatic_pass", false);
            row.addProperty("service_start_count", scene.service.starts);
        }
        return row;
    }

    static boolean matches(Case item, CommandProposal proposal, DispatchResult decision, UUID last) {
        if (item.id().equals("status-empty") && proposal instanceof CommandProposal.Clarification
                && decision instanceof DispatchResult.Clarification) return true;
        if (!decision.getClass().getSimpleName().equals(item.javaDecision())) return false;
        if (item.command() == null) return proposal instanceof CommandProposal.Clarification || proposal instanceof CommandProposal.Unsupported;
        if (!(proposal instanceof CommandProposal.Calls c) || c.calls().size() != 1 || !c.calls().get(0).name().equals(item.command())) return false;
        JsonObject actual = JsonParser.parseString(c.calls().get(0).argumentsJson()).getAsJsonObject();
        JsonObject expected = expectedArguments(item, last);
        for (var field : expected.entrySet()) if (!field.getValue().equals(actual.get(field.getKey()))) return false;
        if (decision instanceof DispatchResult.Rejected r && r.error().code() != TransferError.Code.INVALID_PARAMETER) return false;
        if (decision instanceof DispatchResult.Started s) {
            Long bytes = expected.get("window_bytes").isJsonNull() ? 1024L : expected.get("window_bytes").getAsLong();
            int timeout = expected.get("timeout_ms").isJsonNull() ? 200 : expected.get("timeout_ms").getAsInt();
            return s.settings().requestedWindowBytes() == bytes && s.settings().windowPackets() == bytes / 1024
                    && s.settings().timeoutMillis() == timeout;
        }
        return true;
    }

    private static JsonObject expectedArguments(Case item, UUID last) {
        return item.arguments() == null ? null : JsonParser.parseString(item.arguments().replace("$LAST", String.valueOf(last))).getAsJsonObject();
    }

    static JsonObject explanation(String id, ExplanationClient client) {
        var example = switch (id) {
            case "loss-acks" -> AcceptedMetricFixtures.missing(AcceptedMetricFixtures.values(AcceptedMetricFixtures.baseline(),
                    Map.of("packet_loss_rate", "12.5", "acks_received", "12000")), "packets_dropped",
                    "No simulator-drop observations supplied; configured probability is not measured loss.");
            case "missing-performance" -> AcceptedMetricFixtures.partialFailure();
            case "integrity-failure" -> AcceptedMetricFixtures.integrityFailure();
            case "unconfirmed-outcome" -> AcceptedMetricFixtures.receiverVerifiedWithoutSenderConfirmation();
            default -> throw new IllegalArgumentException("Unknown fixture.");
        };
        String question = explanationPrompt(id);
        var fixture = example.analysis();
        List<ExplanationRequest> sent = new ArrayList<>();
        List<ExplanationDraft> proposed = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        ExplanationClient recording = request -> {
            sent.add(request);
            try {
                var draft = client.explain(request);
                proposed.add(draft);
                return draft;
            } catch (GptException e) { failures.add(e.code().name()); throw e; }
            catch (RuntimeException e) { failures.add("CLIENT_FAILURE"); throw e; }
        };
        var flow = new ExplanationFlow(new SyntheticSummaryProvider(List.of(fixture.evidence())), recording);
        var result = flow.explain(fixture.selected(), question);
        JsonObject row = object("id", id, "evidence_source", "SYNTHETIC", "prompt", question,
                "expectation", RUBRIC, "analysis_input", sent, "fixture_metadata_NOT_sent_to_model", example.metadata(),
                "untrusted_model_drafts", proposed,
                "java_decision", result, "automatic_pass", result.status() == ExplanationFlow.Status.EXPLAINED,
                "automatic_check_scope", "Envelope, selected identity and reference values only; prose accuracy remains ungraded.",
                "manual_review", "PENDING");
        if (!failures.isEmpty()) row.addProperty("api_failure", failures.get(0));
        return row;
    }

    private static String explanationPrompt(String id) {
        return switch (id) {
            case "loss-acks" -> "In this SYNTHETIC fixture, does 12.5 percent configured loss prove actual network loss? "
                    + "Do 12000 ACK arrivals prove 12000 packets of unique delivered payload? Do timeouts prove congestion?";
            case "missing-performance" -> "In this SYNTHETIC failed run, give the throughput and duration. Can the ACK count prove the whole file arrived intact?";
            case "integrity-failure" -> "In this SYNTHETIC run, do delivered payload and ACK counts mean successful verified file transfer despite the selected FAILED integrity outcome?";
            default -> "In this SYNTHETIC selected FAILED / UNCONFIRMED outcome, can protocol progress alone prove sender success and verified end-to-end integrity?";
        };
    }

    record RealBatchResult(int completed, boolean stopped) { }

    static RealBatchResult realDemo(Path root, GptClient client, List<ApiCallObservation> calls, Report report) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "m8-receiver"); t.setDaemon(true); return t; });
        Path received = root.resolve("received-demo.txt");
        try (UdpChannel channel = new UdpChannel(); RealTransferService real = new RealTransferService()) {
            channel.setReceiveTimeoutMillis(120_000); // Includes model wait; resource closes on every exit.
            var receiver = worker.submit(() -> new ReceiverEngine(channel).receiveFile(received.toString()));
            var service = new CountingService(real);
            var dispatcher = dispatcher(root, channel.getLocalPort(), service);
            int before = calls.size();
            var request = new InterpretationRequest(UUID.randomUUID(), "Send demo to receiver-a with a 64 KiB window.",
                    List.of("demo"), List.of("receiver-a"), null, null, null, null, List.of());
            JsonObject row = object("id", "real-start", "evidence_source", "REAL", "prompt", request.text(), "java_context", request,
                    "expectation", "start_transfer demo/receiver-a, 65536 bytes, null timeout; Java 64 slots/200 ms; actual loopback verified completion and matching SHA-256.",
                    "manual_review", "PENDING");
            DispatchResult decision;
            try {
                var proposal = client.interpret(request);
                row.add("proposal", proposal(proposal));
                // Inspect unexpected proposals through validation but never start an unintended real transfer.
                var expected = new Case("real-start", request.text(), "none", "start_transfer", startArgs(65536L, null), "Started", "");
                var probe = scene(root, "none");
                var probeDecision = probe.dispatcher.dispatch(request.requestId(), proposal);
                if (!matches(expected, proposal, probeDecision, null)) {
                    row.add("java_preflight_SYNTHETIC", decision(probeDecision));
                    row.addProperty("automatic_pass", false);
                    row.addProperty("real_start_count", 0);
                    row.addProperty("failure", "Unexpected model proposal; real execution withheld, review required.");
                    row.add("api_calls", JSON.toJsonTree(calls.subList(before, calls.size())));
                    report.write("case", row);
                    return new RealBatchResult(1, true);
                }
                decision = dispatcher.dispatch(request.requestId(), proposal);
                row.add("java_decision", decision(decision));
            } catch (GptException e) {
                row.addProperty("api_failure", e.code().name());
                row.addProperty("automatic_pass", false);
                row.add("api_calls", JSON.toJsonTree(calls.subList(before, calls.size())));
                report.write("case", row);
                return new RealBatchResult(1, true);
            } catch (RuntimeException e) {
                row.addProperty("api_failure", "CLIENT_OR_EVALUATOR_FAILURE");
                row.addProperty("automatic_pass", false);
                row.add("api_calls", JSON.toJsonTree(calls.subList(before, calls.size())));
                report.write("case", row);
                return new RealBatchResult(1, true);
            }
            row.add("api_calls", JSON.toJsonTree(calls.subList(before, calls.size())));
            row.addProperty("real_start_count", service.starts);
            if (!(decision instanceof DispatchResult.Started started)) {
                row.addProperty("automatic_pass", false);
                report.write("case", row);
                return new RealBatchResult(1, true);
            }
            UUID id = started.acknowledgement().transferId();
            try {
                boolean receiverSuccess = receiver.get(10, TimeUnit.SECONDS).isSuccess();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (real.status(id).state() == TransferState.RUNNING && System.nanoTime() < deadline) Thread.sleep(10);
                var snapshot = real.status(id);
                boolean sameHash = Files.exists(received) && FileHashUtil.sha256Hex(root.resolve("data/input/demo.txt").toString())
                        .equals(FileHashUtil.sha256Hex(received.toString()));
                row.add("terminal_snapshot", JSON.toJsonTree(snapshot));
                row.addProperty("receiver_success", receiverSuccess);
                row.addProperty("sha256_matches", sameHash);
                if (snapshot.state() == TransferState.RUNNING) throw new TimeoutException();
                var summary = real.summary(id);
                row.addProperty("integrity", summary.integrity().name());
                row.add("real_explanation_gate", JSON.toJsonTree(ExplanationFlow.unavailable().explain(summary, "What happened?")));
                boolean pass = receiverSuccess && sameHash && snapshot.state() == TransferState.COMPLETED && summary.integrity() == IntegrityStatus.VERIFIED;
                row.addProperty("automatic_pass", pass);
                report.write("case", row);
                if (!pass) return new RealBatchResult(1, true);
            } catch (Exception e) {
                row.addProperty("automatic_pass", false);
                row.addProperty("failure", "Local transfer verification did not complete: " + e.getClass().getSimpleName());
                report.write("case", row);
                return new RealBatchResult(1, true);
            }
            before = calls.size();
            request = new InterpretationRequest(UUID.randomUUID(), "How did the last transfer finish?", List.of("demo"),
                    List.of("receiver-a"), null, null, id, id, List.of());
            row = object("id", "real-status", "evidence_source", "REAL", "prompt", request.text(), "java_context", request,
                    "expectation", "status with explicit last application ID; Java reports COMPLETED, unknown progress/timing remain unavailable.", "manual_review", "PENDING");
            try {
                var proposal = client.interpret(request);
                row.add("proposal", proposal(proposal));
                // This phase permits a status lookup only; an erroneous second start never reaches the engine.
                if (!(proposal instanceof CommandProposal.Calls c) || c.calls().size() != 1 || !c.calls().get(0).name().equals("status")) {
                    row.addProperty("automatic_pass", false);
                    row.addProperty("failure", "Expected status; real dispatch withheld.");
                } else {
                    decision = dispatcher.dispatch(request.requestId(), proposal, new CommandDispatcher.Selection(id, id));
                    row.add("java_decision", decision(decision));
                    boolean exactId = decision instanceof DispatchResult.Status && new JsonPrimitive(id.toString()).equals(
                            JsonParser.parseString(c.calls().get(0).argumentsJson()).getAsJsonObject().get("transfer_id"));
                    row.addProperty("automatic_pass", exactId && decision instanceof DispatchResult.Status s
                            && s.snapshot().transferId().equals(id) && s.snapshot().state() == TransferState.COMPLETED);
                }
            } catch (GptException e) { row.addProperty("api_failure", e.code().name()); row.addProperty("automatic_pass", false); }
            catch (RuntimeException e) { row.addProperty("api_failure", "CLIENT_OR_EVALUATOR_FAILURE"); row.addProperty("automatic_pass", false); }
            row.addProperty("real_start_count", service.starts);
            row.add("api_calls", JSON.toJsonTree(calls.subList(before, calls.size())));
            report.write("case", row);
            return new RealBatchResult(2, row.has("api_failure"));
        } finally {
            worker.shutdownNow();
            worker.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    static JsonObject rejection(Path root) throws IOException {
        var scene = scene(root, "none");
        var proposal = new CommandProposal.Calls(List.of(new CommandProposal.ToolCall("start_transfer", startArgs(null, 1))));
        var result = scene.dispatcher.dispatch(UUID.randomUUID(), proposal);
        return object("id", "deterministic-rejection", "source", "JAVA_INJECTED_INVALID_PROPOSAL_NOT_LIVE_GPT", "proposal", proposal(proposal),
                "expectation", "INVALID_PARAMETER; zero service starts, zero network activity", "java_decision", decision(result),
                "service_start_count", scene.service.starts, "automatic_pass", result instanceof DispatchResult.Rejected r
                        && r.error().code() == TransferError.Code.INVALID_PARAMETER && scene.service.starts == 0);
    }

    private static void deterministicRejection(Path root, Report report) throws IOException { report.write("deterministic_check", rejection(root)); }

    private record Scene(CountingService service, CommandDispatcher dispatcher, UUID current, UUID last) {
        CommandDispatcher.Selection selection() { UUID id = current == null ? last : current; return new CommandDispatcher.Selection(id, id); }
    }

    private static Scene scene(Path root, String context) throws IOException {
        var absent = TransferMetrics.unavailable("SYNTHETIC command-evaluation fixture; not measurements");
        var fake = new FakeTransferService(FakeTransferService.Scenario.success(List.of(absent), absent));
        var service = new CountingService(fake);
        var dispatcher = dispatcher(root, 9000, service);
        UUID last = null, current = null;
        if (!context.equals("none")) {
            var seed = (DispatchResult.Started) dispatcher.dispatch(UUID.randomUUID(),
                    new CommandProposal.Calls(List.of(new CommandProposal.ToolCall("start_transfer", startArgs(null, null)))));
            last = seed.acknowledgement().transferId();
            fake.advance(last);
        }
        if (context.equals("both")) {
            var seed = (DispatchResult.Started) dispatcher.dispatch(UUID.randomUUID(),
                    new CommandProposal.Calls(List.of(new CommandProposal.ToolCall("start_transfer", startArgs(null, null)))));
            current = seed.acknowledgement().transferId();
        }
        service.starts = 0; // Seed setup is recorded context, not an interpreted command.
        return new Scene(service, dispatcher, current, last);
    }

    private static CommandDispatcher dispatcher(Path root, int port, TransferService service) {
        var config = new TransferConfiguration(root.toAbsolutePath(), Map.of("demo", Path.of("data/input/demo.txt")),
                Map.of("receiver-a", new InetSocketAddress("127.0.0.1", port)));
        return new CommandDispatcher(new CommandParser(), new CommandValidator(config), service);
    }

    private static final class CountingService implements TransferService {
        private final TransferService delegate;
        int starts;
        CountingService(TransferService delegate) { this.delegate = delegate; }
        public TransferStart start(TransferRequest request) { starts++; return delegate.start(request); }
        public TransferSnapshot status(UUID id) { return delegate.status(id); }
        public TransferSummary summary(UUID id) { return delegate.summary(id); }
    }

    private static final class Budget {
        final int maximum;
        int used;
        Budget(int maximum) { this.maximum = maximum; }
        void take() { if (used >= maximum) throw new IllegalStateException("Call budget exhausted"); used++; }
    }

    static JsonObject proposal(CommandProposal p) { return object("type", p.getClass().getSimpleName(), "value", p); }
    static JsonObject decision(DispatchResult d) {
        // SummarySelected includes filesystem/network objects via TransferRequest; project only relevant evidence.
        return d instanceof DispatchResult.SummarySelected s
                ? object("type", "SummarySelected", "snapshot", s.summary().finalSnapshot(), "integrity", s.summary().integrity(), "question", s.question())
                : object("type", d.getClass().getSimpleName(), "value", d);
    }
    static JsonObject object(Object... pairs) {
        JsonObject o = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) o.add((String) pairs[i], JSON.toJsonTree(pairs[i + 1]));
        return o;
    }
    private static String startArgs(Long bytes, Integer timeout) {
        return object("file_id", "demo", "receiver_id", "receiver-a", "window_bytes", bytes, "timeout_ms", timeout).toString();
    }

    static final class Report implements AutoCloseable {
        private final BufferedWriter records;
        private final BufferedWriter review;
        private final String secret;
        Report(Path directory, String secret) throws IOException {
            this.secret = secret.strip();
            records = Files.newBufferedWriter(directory.resolve("report.jsonl"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            review = Files.newBufferedWriter(directory.resolve("review.md"), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            review.write("# Milestone 8 manual review - PENDING\n\nRecord reviewer/date and PASS/FAIL with reasons for each case. "
                    + "Read matching report.jsonl proposals, context, Java decisions, fixture input and API usage. "
                    + "Automatic checks do not grade prose. Do not mark milestone 8 complete before reviewing all required coverage.\n\n"
                    + RUBRIC + "\n\nDo not paste credentials. API time is separate from unavailable engine timing.\n\n");
            review.flush();
        }
        void write(String type, JsonObject row) throws IOException {
            row.addProperty("record_type", type);
            row.addProperty("recorded_at", Instant.now().toString());
            JsonElement safe = redact(row, secret);
            records.write(JSON.toJson(safe)); records.newLine(); records.flush();
            if (row.has("id")) {
                String id = row.get("id").getAsString();
                String pass = row.has("automatic_pass") ? row.get("automatic_pass").getAsString() : "unavailable";
                review.write("- " + id + ": automatic=" + pass + "; reviewer verdict=PENDING; findings: \n");
                review.flush();
                System.out.println(id + ": automatic=" + pass + "; prose review=PENDING");
            }
        }
        public void close() throws IOException { try { records.close(); } finally { review.close(); } }
    }

    static JsonElement redact(JsonElement value, String secret) {
        if (value == null || value.isJsonNull()) return JsonNull.INSTANCE;
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            value.getAsJsonObject().entrySet().forEach(e -> out.add(redactText(e.getKey(), secret), redact(e.getValue(), secret)));
            return out;
        }
        if (value.isJsonArray()) {
            JsonArray out = new JsonArray(); value.getAsJsonArray().forEach(v -> out.add(redact(v, secret))); return out;
        }
        return value.getAsJsonPrimitive().isString() ? new JsonPrimitive(redactText(value.getAsString(), secret)) : value;
    }
    private static String redactText(String text, String secret) {
        String safe = secret == null || secret.isBlank() ? text : text.replace(secret, "[REDACTED]");
        return safe.replaceAll("(?i)sk-[a-z0-9_-]{8,}", "[REDACTED]");
    }
}
