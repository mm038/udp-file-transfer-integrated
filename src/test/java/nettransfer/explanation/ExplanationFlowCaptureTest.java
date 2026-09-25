package nettransfer.explanation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import nettransfer.llm.EvaluationCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static nettransfer.explanation.ExplanationFlow.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class ExplanationFlowCaptureTest {
    @TempDir Path root;

    @BeforeEach
    void retainEvidenceWhenRequested(TestInfo test) throws Exception {
        String requested = System.getProperty("nettransfer.testEvidenceRoot");
        if (requested != null) {
            Path base = Files.createDirectories(Path.of(requested).resolve("flow-tests"));
            root = Files.createTempDirectory(base, test.getTestMethod().orElseThrow().getName() + "-");
        }
    }

    @Test
    void evidenceGateRecordsSelectedIdsAndNoCallWithoutInvokingClient() throws Exception {
        var fixture = SyntheticExplanationFixtures.baseline();
        try (var capture = EvaluationCapture.open(root, "synthetic-capture-key", warning -> fail(warning))) {
            var flow = new ExplanationFlow(runId -> Optional.empty(), request -> {
                throw new AssertionError("Evidence gate must prevent the client call");
            }, capture);

            assertEquals(EVIDENCE_UNAVAILABLE, flow.explain(fixture.selected(), "Explain.").status());

            var decision = decisions(capture.directory()).get(0);
            assertEquals("explanation", decision.get("stage").getAsString());
            assertEquals("EVIDENCE_UNAVAILABLE", decision.get("status").getAsString());
            assertEquals(fixture.evidence().runId().toString(), decision.get("run_id").getAsString());
            assertEquals(fixture.evidence().transferId().toString(), decision.get("transfer_id").getAsString());
            assertDoesNotThrow(() -> UUID.fromString(decision.get("request_id").getAsString()));
            assertTrue(decision.get("capture_id").isJsonNull());
        }
    }

    @Test
    void acceptedRejectedAndFailedAnalysisRecordTheSameRequestIdSentToClient() throws Exception {
        var fixture = SyntheticExplanationFixtures.baseline();
        try (var capture = EvaluationCapture.open(root, "synthetic-capture-key", warning -> fail(warning))) {
            var requests = new ArrayList<UUID>();
            var captureIds = new ArrayList<UUID>();
            var calls = new AtomicInteger();
            var flow = new ExplanationFlow(new SyntheticSummaryProvider(List.of(fixture.evidence())), request -> {
                requests.add(request.requestId());
                captureIds.add(capture.request(request.requestId(), "{\"fixture\":\"simulated call only\"}"));
                return switch (calls.getAndIncrement()) {
                    case 0 -> fixture.draft();
                    case 1 -> new ExplanationDraft(UUID.randomUUID(), fixture.evidence().transferId(),
                            fixture.draft().observations(), fixture.draft().hypotheses(), fixture.draft().limitations());
                    default -> throw new IllegalStateException("Private failure detail must stay out of decision");
                };
            }, capture);

            assertEquals(EXPLAINED, flow.explain(fixture.selected(), "Explain.").status());
            var rejected = flow.explain(fixture.selected(), "Explain again.");
            assertEquals(EXPLANATION_REJECTED, rejected.status());
            assertNull(rejected.draft(), "Rejected prose remains absent from normal CLI display");
            assertEquals(EXPLANATION_UNAVAILABLE, flow.explain(fixture.selected(), "Explain again.").status());

            var decisions = decisions(capture.directory());
            assertEquals(List.of("EXPLAINED", "EXPLANATION_REJECTED", "EXPLANATION_UNAVAILABLE"), decisions.stream()
                    .map(row -> row.get("status").getAsString()).toList());
            for (int index = 0; index < decisions.size(); index++) {
                assertEquals(requests.get(index).toString(), decisions.get(index).get("request_id").getAsString());
                assertEquals(captureIds.get(index).toString(), decisions.get(index).get("capture_id").getAsString());
            }
            assertEquals(3, requests.stream().distinct().count());
            assertFalse(Files.readString(capture.directory().resolve("events.jsonl")).contains("Private failure detail"));
        }
    }

    @Test
    void recordingFailureDoesNotRetryAnalysisOrChangeAcceptedResult() throws Exception {
        var fixture = SyntheticExplanationFixtures.baseline();
        var warnings = new ArrayList<String>();
        var calls = new AtomicInteger();
        try (var capture = EvaluationCapture.open(root, "synthetic-capture-key", warnings::add)) {
            Path events = capture.directory().resolve("events.jsonl");
            Files.delete(events);
            Files.createDirectory(events); // Deliberate test-only I/O failure; capture must warn once.
            var flow = new ExplanationFlow(new SyntheticSummaryProvider(List.of(fixture.evidence())), request -> {
                calls.incrementAndGet();
                return fixture.draft();
            }, capture);

            assertEquals(EXPLAINED, flow.explain(fixture.selected(), "Explain.").status());
            assertEquals(EXPLAINED, flow.explain(fixture.selected(), "Explain again.").status());
            assertEquals(2, calls.get(), "Exactly one client invocation per user request; recorder adds no retries");
            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).contains("evidence is incomplete"));
        }
    }

    private static List<JsonObject> decisions(Path directory) throws Exception {
        return Files.readAllLines(directory.resolve("events.jsonl")).stream()
                .map(line -> JsonParser.parseString(line).getAsJsonObject())
                .filter(row -> row.get("event").getAsString().equals("java_decision")).toList();
    }
}
