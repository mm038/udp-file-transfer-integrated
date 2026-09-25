package nettransfer.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import nettransfer.explanation.ExplanationDraft;
import nettransfer.explanation.ExplanationFlow;
import nettransfer.explanation.SyntheticExplanationFixtures;
import nettransfer.explanation.SyntheticSummaryProvider;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic authored evidence and loopback HTTP only; this does not evaluate live model prose. */
@Timeout(10)
class ExplanationCaptureIntegrationTest {
    private static final String KEY = "synthetic-cross-layer-capture-key-1328";
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualFlowDecisionLinksExactRequestAndPreservesEvenRejectedModelWording(boolean invalidReference)
            throws Exception {
        var fixture = SyntheticExplanationFixtures.baseline();
        JsonObject draft = draftJson(fixture.draft());
        String modelWording = invalidReference
                ? "SYNTHETIC fixture: this unsupported duration claim must remain reviewable."
                : "SYNTHETIC fixture: the cited duration is supplied by the authored evidence.";
        JsonObject observation = draft.getAsJsonArray("observations").get(0).getAsJsonObject();
        observation.addProperty("text", modelWording);
        if (invalidReference) {
            observation.getAsJsonArray("references").get(0).getAsJsonObject().addProperty("value", 999);
        }
        String responseBody = response(draft);
        List<byte[]> sentBodies = new CopyOnWriteArrayList<>();
        List<ApiCallObservation> observations = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "explanation-capture-integration-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(worker);
        server.createContext("/v1/responses", exchange -> {
            sentBodies.add(exchange.getRequestBody().readAllBytes());
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses");
        Path captureDirectory;
        try (EvaluationCapture capture = EvaluationCapture.open(evidenceRoot(invalidReference), KEY, ignored -> { })) {
            captureDirectory = capture.directory();
            var settings = new GptSettings("gpt-5-mini", Duration.ofSeconds(1), Duration.ofSeconds(2), 2);
            var client = new ResponsesExplanationClient(KEY, settings, endpoint, observations::add, capture);
            var flow = new ExplanationFlow(new SyntheticSummaryProvider(List.of(fixture.evidence())), client, capture);

            var result = flow.explain(fixture.selected(), "Explain this SYNTHETIC offline fixture.");

            var expected = invalidReference ? ExplanationFlow.Status.EXPLANATION_REJECTED : ExplanationFlow.Status.EXPLAINED;
            assertEquals(expected, result.status());
            assertSame(fixture.evidence(), result.evidence());
            if (invalidReference) assertNull(result.draft());
            else assertEquals(modelWording, result.draft().observations().get(0).text());
            assertEquals(1, sentBodies.size(), "Java reference rejection must not trigger another HTTP call");
            assertEquals(1, observations.size());
            assertNull(observations.get(0).failureCode(), "HTTP parsing succeeded before Java accepted or rejected the references");

            List<JsonObject> events = Files.readAllLines(captureDirectory.resolve("events.jsonl")).stream()
                    .map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
            JsonObject request = only(events, "request");
            JsonObject response = only(events, "response");
            JsonObject attempt = only(events, "attempt");
            JsonObject decision = only(events, "java_decision");
            assertEquals("explanation", decision.get("stage").getAsString());
            assertEquals(expected.name(), decision.get("status").getAsString());
            assertEquals(fixture.evidence().runId().toString(), decision.get("run_id").getAsString());
            assertEquals(fixture.evidence().transferId().toString(), decision.get("transfer_id").getAsString());
            for (JsonObject linked : List.of(response, attempt, decision)) {
                assertEquals(request.get("capture_id"), linked.get("capture_id"));
                assertEquals(request.get("request_id"), linked.get("request_id"));
            }
            assertTrue(attempt.get("sequence").getAsLong() < decision.get("sequence").getAsLong());
            assertEquals("EXACT_UNTRUSTED", response.get("body_status").getAsString());
            assertFalse(response.get("trusted").getAsBoolean());

            Path requestFile = captureDirectory.resolve(request.get("artifact").getAsString());
            Path responseFile = captureDirectory.resolve(response.get("artifact").getAsString());
            assertArrayEquals(sentBodies.get(0), Files.readAllBytes(requestFile));
            assertEquals(responseBody, Files.readString(responseFile));
            assertTrue(Files.readString(responseFile).contains(modelWording),
                    "Discarding a rejected ExplanationDraft must not discard the paid response's wording");
            JsonObject sent = JsonParser.parseString(Files.readString(requestFile)).getAsJsonObject();
            assertEquals(request.get("request_id"), sent.getAsJsonObject("metadata").get("request_id"));
            assertEquals("gpt-5-mini", sent.get("model").getAsString());
            JsonObject supplied = JsonParser.parseString(sent.getAsJsonArray("input").get(0).getAsJsonObject()
                    .get("content").getAsString()).getAsJsonObject();
            assertEquals("SYNTHETIC", supplied.getAsJsonObject("evidence").get("evidence_source").getAsString());
        } finally {
            server.stop(0);
            worker.shutdownNow();
        }
        JsonObject sessionEnd = JsonParser.parseString(Files.readString(captureDirectory.resolve("session-end.json")))
                .getAsJsonObject();
        assertEquals("COMPLETE", sessionEnd.get("recording_status").getAsString());
    }

    private Path evidenceRoot(boolean invalidReference) throws IOException {
        String configured = System.getProperty("nettransfer.testEvidenceRoot");
        if (configured == null || configured.isBlank()) return temporary;
        Path parent = Path.of(configured).resolve("explanation-integration-tests");
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, invalidReference ? "rejected-reference-" : "accepted-reference-");
    }

    private static JsonObject only(List<JsonObject> events, String type) {
        List<JsonObject> selected = events.stream().filter(event -> type.equals(event.get("event").getAsString())).toList();
        assertEquals(1, selected.size(), type);
        return selected.get(0);
    }

    private static JsonObject draftJson(ExplanationDraft draft) {
        JsonObject result = new JsonObject();
        result.addProperty("run_id", draft.runId().toString());
        result.addProperty("transfer_id", draft.transferId().toString());
        JsonArray observations = new JsonArray();
        for (var observation : draft.observations()) {
            JsonObject item = new JsonObject();
            item.addProperty("text", observation.text());
            JsonArray references = new JsonArray();
            for (var reference : observation.references()) {
                JsonObject ref = new JsonObject();
                ref.addProperty("field_id", reference.fieldId());
                ref.addProperty("value", reference.value());
                ref.addProperty("unit", reference.unit());
                references.add(ref);
            }
            item.add("references", references);
            observations.add(item);
        }
        result.add("observations", observations);
        result.add("hypotheses", strings(draft.hypotheses()));
        result.add("limitations", strings(draft.limitations()));
        return result;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static String response(JsonObject draft) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "output_text");
        part.addProperty("text", draft.toString());
        JsonArray content = new JsonArray();
        content.add(part);
        JsonObject message = new JsonObject();
        message.addProperty("type", "message");
        message.addProperty("role", "assistant");
        message.addProperty("status", "completed");
        message.add("content", content);
        JsonArray output = new JsonArray();
        output.add(message);
        JsonObject response = new JsonObject();
        response.addProperty("id", "resp_synthetic_capture_integration");
        response.addProperty("status", "completed");
        response.addProperty("model", "gpt-5-mini");
        response.add("output", output);
        return response.toString();
    }
}
