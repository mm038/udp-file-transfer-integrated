package nettransfer.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import nettransfer.control.TransferMetrics;
import nettransfer.control.command.CommandProposal;
import nettransfer.control.command.TransferConfiguration;
import nettransfer.control.simulation.FakeTransferService;
import nettransfer.explanation.ExplanationFlow;
import nettransfer.explanation.SummaryProvider;
import nettransfer.llm.EvaluationCapture;
import nettransfer.llm.GptException;
import nettransfer.llm.StubGptClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransferCliCaptureTest {
    @TempDir Path root;

    @BeforeEach
    void retainEvidenceWhenRequested(TestInfo test) throws Exception {
        String requested = System.getProperty("nettransfer.testEvidenceRoot");
        if (requested != null) {
            Path base = Files.createDirectories(Path.of(requested).resolve("cli-tests"));
            root = Files.createTempDirectory(base, test.getTestMethod().orElseThrow().getName() + "-");
        }
    }

    @Test
    void recordingIsOffByDefaultAndRejectsAmbiguousFlagValues() {
        assertFalse(TransferCliMain.evaluationRecordingEnabled(null));
        assertFalse(TransferCliMain.evaluationRecordingEnabled("false"));
        assertTrue(TransferCliMain.evaluationRecordingEnabled("true"));
        for (String value : List.of("", "TRUE", "yes", "1", " true ")) {
            assertThrows(IllegalArgumentException.class, () -> TransferCliMain.evaluationRecordingEnabled(value));
        }
    }

    @Test
    void capturesInterpretationDecisionsAndSelectedIdsWithoutServiceContents() throws Exception {
        var configuration = configuration();
        var service = service();
        var stub = new StubGptClient(List.of(new CommandProposal.Clarification("Which receiver?"),
                new CommandProposal.Unsupported("Unsupported operation"),
                call("start_transfer", "{\"file_id\":\"report\",\"receiver_id\":\"receiver-a\",\"window_bytes\":null,\"timeout_ms\":1}"),
                call("start_transfer", "{\"file_id\":\"report\",\"receiver_id\":\"receiver-a\",\"window_bytes\":null,\"timeout_ms\":null}"),
                call("status", "{\"transfer_id\":null}")));
        try (var capture = EvaluationCapture.open(root, "synthetic-capture-key", failWarning())) {
            var explanations = new ExplanationFlow(SummaryProvider.unavailable(), request -> {
                throw new AssertionError("Missing evidence must prevent explanation calls");
            }, capture);
            var cli = new TransferCli(service, configuration, new StringReader(""), new PrintWriter(new StringWriter()),
                    stub, explanations, capture);
            cli.handleLine("Send report");
            cli.handleLine("Delete the file");
            cli.handleLine("Send with a timeout of 1 ms");
            cli.handleLine("Send report to receiver-a");
            cli.handleLine("How is the transfer progressing?");

            var decisions = decisions(capture.directory());
            assertEquals(List.of("Clarification", "Unsupported", "Rejected", "Started", "Status"),
                    decisions.stream().map(row -> row.get("status").getAsString()).toList());
            for (int index = 0; index < decisions.size(); index++) {
                assertEquals("interpretation", decisions.get(index).get("stage").getAsString());
                assertEquals(stub.requests().get(index).requestId().toString(),
                        decisions.get(index).get("request_id").getAsString());
            }
            var started = decisions.get(3);
            UUID transfer = UUID.fromString(started.get("transfer_id").getAsString());
            assertEquals(transfer.toString(), decisions.get(4).get("transfer_id").getAsString());
            service.advance(transfer);
            cli.handleLine("explain {\"run_id\":null,\"question\":\"What happened?\"}");
            decisions = decisions(capture.directory());
            assertEquals("SummarySelected", decisions.get(5).get("status").getAsString());
            assertEquals("direct_command", decisions.get(5).get("stage").getAsString());
            assertEquals("EVIDENCE_UNAVAILABLE", decisions.get(6).get("status").getAsString());
            assertEquals(transfer.toString(), decisions.get(6).get("transfer_id").getAsString());
            String recorded = Files.readString(capture.directory().resolve("events.jsonl"));
            assertFalse(recorded.contains("PRIVATE PAYLOAD NEVER SENT TO GPT"));
        }
    }

    @Test
    void recordsClientFailureAndPreClientInputFailureWithoutDispatch() throws Exception {
        var configuration = configuration();
        try (var capture = EvaluationCapture.open(root, "synthetic-capture-key", failWarning())) {
            var cli = new TransferCli(service(), configuration, new StringReader(""), new PrintWriter(new StringWriter()),
                    request -> { throw new GptException(GptException.Code.TIMEOUT); },
                    ExplanationFlow.unavailable(), capture);
            cli.handleLine("Send report to receiver-a");
            cli.handleLine("ask " + "x".repeat(4001));
            var decisions = decisions(capture.directory());
            assertEquals(List.of("TIMEOUT", "INVALID_COMMAND"), decisions.stream()
                    .map(row -> row.get("status").getAsString()).toList());
            assertTrue(decisions.stream().allMatch(row -> row.get("transfer_id").isJsonNull()));
        }
    }

    @Test
    void oversizedClarificationReplyCannotLinkItsDecisionToThePreviousModelCall() throws Exception {
        var configuration = configuration();
        try (var capture = EvaluationCapture.open(root, "synthetic-capture-key", failWarning())) {
            var cli = new TransferCli(service(), configuration, new StringReader(""), new PrintWriter(new StringWriter()),
                    request -> {
                        capture.request(request.requestId(), "{\"fixture\":\"simulated call only\"}");
                        return new CommandProposal.Clarification("Which receiver?");
                    }, ExplanationFlow.unavailable(), capture);
            cli.handleLine("Send report");
            cli.handleLine("ask " + "x".repeat(4001));
            var decisions = decisions(capture.directory());
            assertEquals(decisions.get(0).get("request_id"), decisions.get(1).get("request_id"));
            assertFalse(decisions.get(0).get("capture_id").isJsonNull());
            assertTrue(decisions.get(1).get("capture_id").isJsonNull());
            assertEquals("INVALID_COMMAND", decisions.get(1).get("status").getAsString());
        }
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    @ResourceLock(Resources.SYSTEM_OUT)
    void failedCaptureInitializationAbortsStartupWithSafeMessage() throws Exception {
        Files.writeString(root.resolve("target"), "A file deliberately blocks evaluation-directory creation");
        String output = mainWithRecording("true");
        assertTrue(output.contains("Unable to initialize evaluation recording"), output);
        assertTrue(output.contains("before any model request"), output);
        assertFalse(output.contains("transfer>"), output);
        assertFalse(Files.exists(root.resolve("logs")));
    }

    @Test
    @ResourceLock(Resources.SYSTEM_PROPERTIES)
    @ResourceLock(Resources.SYSTEM_OUT)
    void startupOptInPrintsFolderAndClosesSessionWhileDefaultCreatesNoCapture() throws Exception {
        assertTrue(mainWithRecording(null).contains("Goodbye."));
        assertFalse(Files.exists(root.resolve("target/evaluation")));
        String output = mainWithRecording("true");
        assertTrue(output.contains("Evaluation records:"), output);
        assertTrue(output.contains("untrusted evidence"), output);
        try (var folders = Files.list(root.resolve("target/evaluation"))) {
            Path directory = folders.findFirst().orElseThrow();
            assertTrue(Files.isRegularFile(directory.resolve("session.json")));
            assertTrue(Files.isRegularFile(directory.resolve("session-end.json")));
        }
    }

    private String mainWithRecording(String value) throws Exception {
        String previous = System.getProperty("nettransfer.evaluation.record");
        var previousInput = System.in;
        var previousOutput = System.out;
        var output = new ByteArrayOutputStream();
        try {
            if (value == null) System.clearProperty("nettransfer.evaluation.record");
            else System.setProperty("nettransfer.evaluation.record", value);
            System.setIn(new ByteArrayInputStream("exit\n".getBytes(StandardCharsets.UTF_8)));
            System.setOut(new java.io.PrintStream(output, true, StandardCharsets.UTF_8));
            TransferCliMain.main(new String[]{root.toString(), "report=storage/outgoing/report.txt"});
            return output.toString(StandardCharsets.UTF_8);
        } finally {
            System.setIn(previousInput);
            System.setOut(previousOutput);
            if (previous == null) System.clearProperty("nettransfer.evaluation.record");
            else System.setProperty("nettransfer.evaluation.record", previous);
        }
    }

    private TransferConfiguration configuration() throws Exception {
        Files.createDirectories(root.resolve("storage/outgoing"));
        Files.writeString(root.resolve("storage/outgoing/report.txt"), "PRIVATE PAYLOAD NEVER SENT TO GPT");
        return TransferConfiguration.localhost(root, Map.of("report", Path.of("storage/outgoing/report.txt")));
    }

    private static FakeTransferService service() {
        var unknown = TransferMetrics.unavailable("Synthetic capture fixture");
        return new FakeTransferService(FakeTransferService.Scenario.success(List.of(unknown), unknown));
    }

    private static CommandProposal call(String name, String args) {
        return new CommandProposal.Calls(List.of(new CommandProposal.ToolCall(name, args)));
    }

    private static java.util.function.Consumer<String> failWarning() {
        return warning -> fail("Unexpected recording warning: " + warning);
    }

    private static List<JsonObject> decisions(Path directory) throws Exception {
        return Files.readAllLines(directory.resolve("events.jsonl")).stream()
                .map(line -> JsonParser.parseString(line).getAsJsonObject())
                .filter(row -> row.get("event").getAsString().equals("java_decision")).toList();
    }
}
