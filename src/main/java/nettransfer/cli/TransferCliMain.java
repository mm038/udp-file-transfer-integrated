package nettransfer.cli;

import nettransfer.control.command.TransferConfiguration;
import nettransfer.control.engine.RealTransferService;
import nettransfer.explanation.ExplanationClient;
import nettransfer.explanation.ExplanationFlow;
import nettransfer.explanation.SummaryProvider;
import nettransfer.llm.GptClient;
import nettransfer.llm.GptException;
import nettransfer.llm.ResponsesGptClient;
import nettransfer.llm.ResponsesExplanationClient;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in entry point; the existing nettransfer.Main sender/receiver remains unchanged. */
public final class TransferCliMain {
    private TransferCliMain() { }

    public static void main(String[] args) {
        PrintWriter output = new PrintWriter(System.out, true);
        if (args.length == 0 || (args.length == 1 && args[0].equals("--help"))) {
            usage(output);
            return;
        }
        TransferConfiguration configuration;
        try {
            configuration = configurationFromArgs(args);
        } catch (IllegalArgumentException e) {
            output.println("Invalid startup configuration: " + e.getMessage());
            usage(output);
            return;
        }
        try (RealTransferService service = new RealTransferService()) {
            Thread shutdown = new Thread(service::close, "transfer-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                new TransferCli(service, configuration,
                        new InputStreamReader(System.in, StandardCharsets.UTF_8), output,
                        gptFromEnvironment(System.getenv()),
                        new ExplanationFlow(SummaryProvider.unavailable(),
                                explanationFromEnvironment(System.getenv()))).run();
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
        }
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

    /** Invalid optional GPT configuration must not disable local status/help or transfer commands. */
    static GptClient gptFromEnvironment(Map<String, String> environment) {
        try {
            return ResponsesGptClient.fromEnvironment(environment);
        } catch (GptException e) {
            return request -> { throw e; };
        }
    }

    /** Construction makes no HTTP call; unavailable evidence stops the flow before this client. */
    static ExplanationClient explanationFromEnvironment(Map<String, String> environment) {
        try {
            return ResponsesExplanationClient.fromEnvironment(environment);
        } catch (GptException e) {
            return request -> { throw e; };
        }
    }

    private static void usage(PrintWriter output) {
        output.println("Usage: java -cp target/udp-file-transfer.jar nettransfer.cli.TransferCliMain <projectRoot> <file-id=relative-path>...");
        output.println("Example: java -cp target/udp-file-transfer.jar nettransfer.cli.TransferCliMain . report=data/input/report.txt");
        output.println("Files must pass Java validation under <projectRoot>/data/input. Quote arguments containing spaces.");
        output.println("Receiver ID receiver-a uses 127.0.0.1:9000; start the existing receiver separately.");
        output.println("Natural language uses OPENAI_API_KEY and optional OPENAI_MODEL (default gpt-5-mini).");
        output.println("Optional API deadlines: OPENAI_CONNECT_TIMEOUT_MS and OPENAI_REQUEST_TIMEOUT_MS. Direct commands need no key.");
    }
}
