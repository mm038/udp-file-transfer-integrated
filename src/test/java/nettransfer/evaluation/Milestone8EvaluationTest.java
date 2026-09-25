package nettransfer.evaluation;

import com.google.gson.JsonParser;
import nettransfer.control.command.CommandProposal;
import nettransfer.explanation.AcceptedMetricFixtures;
import nettransfer.explanation.ExplanationDraft;
import nettransfer.llm.GptException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tests the evaluator with stubs only. No environment lookup and no paid API clients. */
class Milestone8EvaluationTest {
    @TempDir Path root;

    private void input() throws Exception {
        Files.createDirectories(root.resolve("storage/outgoing"));
        Files.writeString(root.resolve("storage/outgoing/demo.txt"), "fixture", StandardCharsets.UTF_8);
    }

    @Test void keyAloneCannotEnableLiveExecution() throws Exception {
        var options = Milestone8LiveEvaluation.Options.parse(new String[0]);
        assertFalse(options.live());
        // execute returns from preview before constructing a client or writing an artifact.
        Milestone8LiveEvaluation.execute(options, Map.of("OPENAI_API_KEY", "not-a-real-key"));
    }

    @Test void liveRequiresExplicitSufficientBudget() {
        assertThrows(IllegalArgumentException.class, () -> Milestone8LiveEvaluation.Options.parse(new String[]{"--live"}));
        assertThrows(IllegalArgumentException.class, () -> Milestone8LiveEvaluation.Options.parse(new String[]{"--live", "--max-calls", "5"}));
        assertThrows(IllegalArgumentException.class, () -> Milestone8LiveEvaluation.Options.parse(new String[]{"--live", "--max-calls", "17"}));
        assertEquals(6, Milestone8LiveEvaluation.Options.parse(new String[]{"--live", "--max-calls", "6"}).maxCalls());
        assertThrows(IllegalArgumentException.class, () -> Milestone8LiveEvaluation.Options.parse(new String[]{"--batch", "unknown"}));
        assertThrows(IllegalArgumentException.class, () -> Milestone8LiveEvaluation.Options.parse(new String[]{"--live", "--live"}));
    }

    @Test void accidentalCredentialInModelIsRejectedBeforePrintingEvenInPreview() {
        var options = Milestone8LiveEvaluation.Options.parse(new String[0]);
        var error = assertThrows(GptException.class, () -> Milestone8LiveEvaluation.execute(options,
                Map.of("OPENAI_MODEL", "sk-notRealSecret012345", "OPENAI_API_KEY", "sk-notRealSecret012345")));
        assertEquals(GptException.Code.INVALID_CONFIGURATION, error.code());
    }

    @Test void defaultStartUsesRealValidationButOnlySyntheticService() throws Exception {
        input();
        var item = Milestone8LiveEvaluation.commandCases().get(0);
        var row = Milestone8LiveEvaluation.command(root, item, request -> new CommandProposal.Calls(List.of(
                new CommandProposal.ToolCall(item.command(), item.arguments()))));
        assertTrue(row.get("automatic_pass").getAsBoolean());
        assertEquals(1, row.get("service_start_count").getAsInt());
        assertEquals("SYNTHETIC", row.get("evidence_source").getAsString());
        assertEquals("PENDING", row.get("manual_review").getAsString());
    }

    @Test void unexpectedClarificationFailsClearStartExpectation() throws Exception {
        input();
        var row = Milestone8LiveEvaluation.command(root, Milestone8LiveEvaluation.commandCases().get(0),
                request -> new CommandProposal.Clarification("What timeout?"));
        assertFalse(row.get("automatic_pass").getAsBoolean());
        assertEquals(0, row.get("service_start_count").getAsInt());
    }

    @Test void lastSelectionIsCheckedAgainstContextWhileCurrentExists() throws Exception {
        input();
        var item = Milestone8LiveEvaluation.commandCases().stream().filter(c -> c.id().equals("last-while-active")).findFirst().orElseThrow();
        var correct = Milestone8LiveEvaluation.command(root, item, request -> new CommandProposal.Calls(List.of(
                new CommandProposal.ToolCall("status", "{\"transfer_id\":\"" + request.lastTransferId() + "\"}"))));
        var wrong = Milestone8LiveEvaluation.command(root, item, request -> new CommandProposal.Calls(List.of(
                new CommandProposal.ToolCall("status", "{\"transfer_id\":\"" + request.currentTransferId() + "\"}"))));
        assertTrue(correct.get("automatic_pass").getAsBoolean());
        assertFalse(wrong.get("automatic_pass").getAsBoolean());
    }

    @Test void deterministicRejectionIsNotAttributedToGpt() throws Exception {
        input();
        var result = Milestone8LiveEvaluation.rejection(root);
        assertTrue(result.get("automatic_pass").getAsBoolean());
        assertEquals(0, result.get("service_start_count").getAsInt());
        assertEquals("JAVA_INJECTED_INVALID_PROPOSAL_NOT_LIVE_GPT", result.get("source").getAsString());
    }

    @Test void apiFailureIsRecordedWithoutDispatchOrExceptionText() throws Exception {
        input();
        var row = Milestone8LiveEvaluation.command(root, Milestone8LiveEvaluation.commandCases().get(0),
                request -> { throw new GptException(GptException.Code.RATE_LIMIT); });
        assertEquals("RATE_LIMIT", row.get("api_failure").getAsString());
        assertEquals(0, row.get("service_start_count").getAsInt());
    }

    @Test void syntheticExplanationRecordsActualInputButDoesNotAutoGradeProse() {
        var row = Milestone8LiveEvaluation.explanation("loss-acks", request -> new ExplanationDraft(
                request.evidence().runId(), request.evidence().transferId(), List.of(), List.of(), List.of("SYNTHETIC; review needed")));
        assertTrue(row.get("automatic_pass").getAsBoolean());
        assertEquals("PENDING", row.get("manual_review").getAsString());
        assertTrue(row.has("fixture_metadata_NOT_sent_to_model"));
        var evidence = row.getAsJsonArray("analysis_input").get(0).getAsJsonObject().getAsJsonObject("evidence");
        assertEquals(AcceptedMetricFixtures.baseline().analysis().evidence().fields().size(), evidence.getAsJsonArray("fields").size());
    }

    @Test void invalidNumericReferencesFailExplanationBoundary() {
        var row = Milestone8LiveEvaluation.explanation("missing-performance", request -> new ExplanationDraft(
                request.evidence().runId(), request.evidence().transferId(), List.of(new ExplanationDraft.Observation("Invented rate",
                List.of(new ExplanationDraft.Reference("throughput_mbps", new java.math.BigDecimal("999"), "Mbps")))),
                List.of(), List.of("SYNTHETIC")));
        assertFalse(row.get("automatic_pass").getAsBoolean());
        assertEquals("EXPLANATION_REJECTED", row.getAsJsonObject("java_decision").get("status").getAsString());
        assertEquals(1, row.getAsJsonArray("untrusted_model_drafts").size(), "Rejected prose must remain reviewable as untrusted output");
    }

    @Test void unexpectedExplanationClientFailureStopsSpendingWithoutLeakingDetails() {
        var row = Milestone8LiveEvaluation.explanation("missing-performance", request -> { throw new IllegalStateException("sensitive details"); });
        assertEquals("CLIENT_FAILURE", row.get("api_failure").getAsString());
        assertFalse(row.toString().contains("sensitive details"));
    }

    @Test @org.junit.jupiter.api.Timeout(25)
    void realDemonstrationUsesStubLanguageButActualLoopbackAndKeepsRejectedStatus() throws Exception {
        input();
        try (var report = new Milestone8LiveEvaluation.Report(root, "dummy-not-real")) {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var result = Milestone8LiveEvaluation.realDemo(root, request -> calls.getAndIncrement() == 0
                    ? new CommandProposal.Calls(List.of(new CommandProposal.ToolCall("start_transfer",
                    "{\"file_id\":\"demo\",\"receiver_id\":\"receiver-a\",\"window_bytes\":65536,\"timeout_ms\":null}")))
                    : new CommandProposal.Calls(List.of(new CommandProposal.ToolCall("status", "{}"))),
                    new java.util.ArrayList<>(), report);
            assertEquals(2, result.completed());
            assertFalse(result.stopped());
            assertEquals(2, calls.get());
        }
        var rows = Files.readAllLines(root.resolve("report.jsonl"), StandardCharsets.UTF_8);
        var start = JsonParser.parseString(rows.get(0)).getAsJsonObject();
        var status = JsonParser.parseString(rows.get(1)).getAsJsonObject();
        assertTrue(start.get("automatic_pass").getAsBoolean());
        assertTrue(start.get("sha256_matches").getAsBoolean());
        assertEquals("EVIDENCE_UNAVAILABLE", start.getAsJsonObject("real_explanation_gate").get("status").getAsString());
        assertFalse(status.get("automatic_pass").getAsBoolean());
        assertEquals("Rejected", status.getAsJsonObject("java_decision").get("type").getAsString());
        assertEquals(1, status.get("real_start_count").getAsInt());
    }

    @Test void outputIsUtf8RedactedAndCannotOverwriteArtifacts() throws Exception {
        String secret = "sample-secret-value";
        try (var report = new Milestone8LiveEvaluation.Report(root, secret)) {
            report.write("test", Milestone8LiveEvaluation.object("value", "punctuation: \u2019 \u2014 \u201c " + secret + " sk-anotherCredential012345"));
        }
        String saved = Files.readString(root.resolve("report.jsonl"), StandardCharsets.UTF_8);
        assertFalse(saved.contains(secret));
        assertFalse(saved.contains("sk-another"));
        assertEquals("punctuation: \u2019 \u2014 \u201c [REDACTED] [REDACTED]", JsonParser.parseString(saved).getAsJsonObject().get("value").getAsString());
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> new Milestone8LiveEvaluation.Report(root, secret));
        assertEquals(saved, Files.readString(root.resolve("report.jsonl"), StandardCharsets.UTF_8));
    }
}
