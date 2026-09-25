package nettransfer.cli;

import nettransfer.control.command.TransferConfiguration;
import nettransfer.control.engine.RealTransferService;
import nettransfer.explanation.ExplanationClient;
import nettransfer.explanation.ExplanationFlow;
import nettransfer.explanation.RealMetricsSummaryProvider;
import nettransfer.llm.GptClient;
import nettransfer.llm.GptException;
import nettransfer.llm.EvaluationCapture;
import nettransfer.llm.ResponsesGptClient;
import nettransfer.llm.ResponsesExplanationClient;
import nettransfer.metrics.PersistedEvidenceRepository;
import nettransfer.net.ImpairmentSettings;

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in entry point; the existing nettransfer.Main sender/receiver remains unchanged. */
public final class TransferCliMain {
    private TransferCliMain() { }

    public static void main(String[] args) {
        // Native consoles know their encoding. A PrintWriter over System.out would instead use
        // the JVM default, which can differ from the Windows terminal's active code page.
        Console console = System.console();
        PrintWriter output = console == null ? redirectedOutput(System.out) : console.writer();
        Reader input = console == null ? redirectedInput(System.in) : console.reader();
        if (args.length == 0 || (args.length == 1 && args[0].equals("--help"))) {
            usage(output);
            return;
        }
        TransferConfiguration configuration;
        ImpairmentSettings impairment;
        boolean recordEvaluation;
        try {
            configuration = configurationFromArgs(args);
            impairment = ImpairmentSettings.fromSystemProperties();
            recordEvaluation = evaluationRecordingEnabled(System.getProperty("nettransfer.evaluation.record"));
        } catch (IllegalArgumentException e) {
            output.println("Invalid startup configuration: " + e.getMessage());
            usage(output);
            return;
        }
        // The log root comes only from validated Java startup configuration, never GPT output.
        if (impairment.enabled()) {
            output.println("Simulator " + impairment.scenario() + ": receiver DATA loss="
                    + impairment.lossPercent() + "%, DATA/ACK delivery delay=" + impairment.delayMillis()
                    + " ms per direction, seed=" + impairment.seed() + "; START/FINISH unaffected.");
            output.flush();
        }
        EvaluationCapture capture;
        try {
            capture = recordEvaluation
                    ? EvaluationCapture.open(configuration.applicationRoot(), System.getenv("OPENAI_API_KEY"),
                    warning -> { output.println(warning); output.flush(); })
                    : EvaluationCapture.disabled();
        } catch (IOException | RuntimeException e) {
            output.println("Unable to initialize evaluation recording. Startup stopped before any model request.");
            output.flush();
            return;
        }
        if (capture.directory() != null) {
            output.println("Evaluation records: " + capture.directory());
            output.println("Recorded model responses are untrusted evidence and may include answers rejected by Java.");
            output.flush();
        }
        try (EvaluationCapture recording = capture;
             RealRuntime runtime = realRuntime(configuration,
                     explanationFromEnvironment(System.getenv(), recording), impairment, recording)) {
            RealTransferService service = runtime.service();
            Thread shutdown = new Thread(service::close, "transfer-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                new TransferCli(service, configuration,
                        input, output,
                        gptFromEnvironment(System.getenv(), recording),
                        runtime.explanations(), recording).run();
            } catch (IOException e) {
                output.println("Console input failed; closing transfer resources: " + e.getMessage());
            } finally {
                // Keep the shutdown hook registered until resources have actually closed.
                service.close();
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown has started; the hook and try-with-resources both close safely.
                }
            }
        } catch (IOException e) {
            output.println("Unable to initialize the trusted transfer log: " + e.getMessage());
        }
    }

    /** Without a native Console, both sides use UTF-8; pipe/IDE hosts must use the same encoding. */
    static Reader redirectedInput(InputStream input) {
        return new InputStreamReader(input, StandardCharsets.UTF_8);
    }

    static PrintWriter redirectedOutput(OutputStream output) {
        return new PrintWriter(output, true, StandardCharsets.UTF_8);
    }

    static TransferConfiguration configurationFromArgs(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException("Supply a project root and at least one approved file ID");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Map<String, Path> files = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            int equals = args[i].indexOf('=');
            if (equals <= 0 || equals == args[i].length() - 1) {
                throw new IllegalArgumentException("File entries must be file-id=relative-path");
            }
            String id = args[i].substring(0, equals);
            Path path = Path.of(args[i].substring(equals + 1));
            if (path.isAbsolute()) {
                throw new IllegalArgumentException("Approved file entries must use relative paths");
            }
            if (files.putIfAbsent(id, path) != null) {
                throw new IllegalArgumentException("Duplicate approved file ID: " + id);
            }
        }
        return TransferConfiguration.localhost(root, files);
    }

    static Path loggingRoot(TransferConfiguration configuration) {
        return configuration.applicationRoot().resolve("logs").normalize();
    }

    /** One Java-owned root is shared by sender logging and validated evidence lookup. */
    static RealRuntime realRuntime(TransferConfiguration configuration,
                                   ExplanationClient explanationClient) throws IOException {
        return realRuntime(configuration, explanationClient, ImpairmentSettings.disabled());
    }

    static boolean evaluationRecordingEnabled(String value) {
        if (value == null || value.equals("false")) return false;
        if (value.equals("true")) return true;
        throw new IllegalArgumentException("nettransfer.evaluation.record must be true or false");
    }

    static RealRuntime realRuntime(TransferConfiguration configuration,
                                   ExplanationClient explanationClient,
                                   ImpairmentSettings impairment) throws IOException {
        return realRuntime(configuration, explanationClient, impairment, EvaluationCapture.disabled());
    }

    static RealRuntime realRuntime(TransferConfiguration configuration,
                                   ExplanationClient explanationClient,
                                   ImpairmentSettings impairment, EvaluationCapture capture) throws IOException {
        Path root = loggingRoot(configuration).toAbsolutePath().normalize();
        var repository = new PersistedEvidenceRepository(root);
        var explanations = new ExplanationFlow(
                new RealMetricsSummaryProvider(repository), explanationClient, capture);
        return new RealRuntime(root, new RealTransferService(root, impairment), explanations);
    }

    record RealRuntime(Path loggingRoot, RealTransferService service,
                       ExplanationFlow explanations) implements AutoCloseable {
        RealRuntime {
            loggingRoot = loggingRoot.toAbsolutePath().normalize();
        }

        @Override
        public void close() {
            service.close();
        }
    }

    /** Invalid optional GPT configuration must not disable local status/help or transfer commands. */
    static GptClient gptFromEnvironment(Map<String, String> environment) {
        return gptFromEnvironment(environment, EvaluationCapture.disabled());
    }

    static GptClient gptFromEnvironment(Map<String, String> environment, EvaluationCapture capture) {
        try {
            return ResponsesGptClient.fromEnvironment(environment, capture);
        } catch (GptException e) {
            return request -> { throw e; };
        }
    }

    /** Construction makes no HTTP call; unavailable evidence stops the flow before this client. */
    static ExplanationClient explanationFromEnvironment(Map<String, String> environment) {
        return explanationFromEnvironment(environment, EvaluationCapture.disabled());
    }

    static ExplanationClient explanationFromEnvironment(Map<String, String> environment,
                                                       EvaluationCapture capture) {
        try {
            return ResponsesExplanationClient.fromEnvironment(environment, capture);
        } catch (GptException e) {
            return request -> { throw e; };
        }
    }

    private static void usage(PrintWriter output) {
        output.println("Usage: java -cp target/udp-file-transfer.jar nettransfer.cli.TransferCliMain <projectRoot> <file-id=relative-path>...");
        output.println("Example: java -cp target/udp-file-transfer.jar nettransfer.cli.TransferCliMain . report=storage/outgoing/report.txt");
        output.println("Files must pass Java validation under <projectRoot>/storage/outgoing. Quote arguments containing spaces.");
        output.println("Receiver ID receiver-a uses 127.0.0.1:9000; start the existing receiver separately.");
        output.println("Real sender evidence is written and retrieved under <projectRoot>/logs.");
        output.println("Status uses live sender observations; ACK-based rate is not reconciled throughput.");
        output.println("Natural language and AVAILABLE evidence explanations use OPENAI_API_KEY and optional OPENAI_MODEL (default gpt-5-mini).");
        output.println("Optional API deadlines: OPENAI_CONNECT_TIMEOUT_MS and OPENAI_REQUEST_TIMEOUT_MS. Direct commands need no key.");
        output.println("Opt-in evidence recording: -Dnettransfer.evaluation.record=true (unique target/evaluation/llm-... folder).");
        output.println("Optional simulator JVM settings (use the same values on receiver and console):");
        output.println("  -Dnettransfer.impairment.enabled=true -Dnettransfer.impairment.lossPercent=2");
        output.println("  -Dnettransfer.impairment.delayMs=0 -Dnettransfer.impairment.seed=42 -Dnettransfer.impairment.scenario=loss-2");
        output.println("Simulator: receiver DATA loss; fixed DATA and ACK delivery delay per direction. START/FINISH pass normally.");
        output.println("Native consoles use their own encoding; redirected or IDE input/output uses UTF-8.");
        output.flush();
    }
}
