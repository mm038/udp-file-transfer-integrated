package nettransfer.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationCaptureTest {
    @TempDir Path temporary;
    private static final String SECRET = "capture-test-private-key";

    private Path project() throws Exception {
        String evidenceRoot = System.getProperty("nettransfer.testEvidenceRoot");
        if (evidenceRoot == null) return temporary;
        Path parent = Path.of(evidenceRoot).resolve("recorder-tests");
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, "case-");
    }

    @Test
    void disabledCaptureCreatesNoArtifactsAndAcceptsNoOpCallbacks() throws Exception {
        try (var capture = EvaluationCapture.disabled()) {
            assertNull(capture.directory());
            capture.beginInvocation(UUID.randomUUID());
            assertNull(capture.request(UUID.randomUUID(), "{}"));
            capture.response(null, 1, 200, new byte[0]);
            capture.attempt(null, null);
            capture.decision(UUID.randomUUID(), "explanation", "EXPLAINED", null, null, "detail");
        }
        try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
    }

    @Test
    void preservesExactRequestAndUntrustedResponseAndLinksJavaDecision() throws Exception {
        List<String> warnings = new ArrayList<>();
        UUID requestId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Path saved;
        try (var capture = EvaluationCapture.open(project(), SECRET, warnings::add)) {
            saved = capture.directory();
            String request = " {\n \"input\":\"café and 測定\",\"value\":1.2300 }\n";
            UUID id = capture.request(requestId, request);
            String response = " {\"output\":\"unreviewed prose\"}\r\n";
            capture.response(id, 1, 200, response.getBytes(StandardCharsets.UTF_8));
            capture.attempt(id, observation(requestId, 1));
            capture.decision(requestId, "explanation", "EXPLANATION_REJECTED", runId, runId,
                    "References did not match supplied evidence");
            assertEquals(request, Files.readString(saved.resolve(id + "/request.json")));
            assertEquals(response, Files.readString(saved.resolve(id + "/attempt-1-response.txt")));
            List<JsonObject> events = events(saved);
            assertEquals(List.of("request", "response", "attempt", "java_decision"),
                    events.stream().map(e -> e.get("event").getAsString()).toList());
            assertFalse(events.get(1).get("trusted").getAsBoolean());
            assertEquals("EXACT_UNTRUSTED", events.get(1).get("body_status").getAsString());
            assertEquals(id.toString(), events.get(3).get("capture_id").getAsString());
            assertEquals(runId.toString(), events.get(3).get("run_id").getAsString());
            assertTrue(events.get(2).getAsJsonObject("metadata").get("inputTokens").isJsonNull());
            assertEquals("2026-09-25T00:00:00Z",
                    events.get(2).getAsJsonObject("metadata").get("startedAt").getAsString());
        }
        assertEquals("COMPLETE", json(saved.resolve("session-end.json")).get("recording_status").getAsString());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void repeatedClarificationIdGetsDistinctCapturesAndPreflightFailureHasNoStaleLink() throws Exception {
        UUID requestId = UUID.randomUUID();
        try (var capture = EvaluationCapture.open(project(), SECRET, ignored -> fail("Unexpected warning"))) {
            capture.beginInvocation(requestId);
            UUID first = capture.request(requestId, "{\"turn\":1}");
            capture.decision(requestId, "interpretation", "Clarification", null, null, "Need file");
            capture.beginInvocation(requestId);
            UUID second = capture.request(requestId, "{\"turn\":2}");
            capture.decision(requestId, "interpretation", "Unsupported", null, null, "Unsupported operation");
            capture.beginInvocation(requestId);
            capture.decision(requestId, "interpretation", "INVALID_CONFIGURATION", null, null, "No request sent");
            assertNotEquals(first, second);
            List<JsonObject> decisions = events(capture.directory()).stream()
                    .filter(e -> e.get("event").getAsString().equals("java_decision")).toList();
            assertEquals(first.toString(), decisions.get(0).get("capture_id").getAsString());
            assertEquals(second.toString(), decisions.get(1).get("capture_id").getAsString());
            assertTrue(decisions.get(2).get("capture_id").isJsonNull());
            assertEquals("{\"turn\":1}", Files.readString(capture.directory().resolve(first + "/request.json")));
        }
    }

    static Stream<String> credentialBodies() {
        String escaped = SECRET.chars().mapToObj(value -> "\\u" + String.format("%04x", value))
                .reduce("", String::concat);
        String nested = escaped.replace("\\", "\\u005c");
        return Stream.of(SECRET, "{\"error\":\"" + SECRET + "\"}",
                "malformed { \"text\":\"" + escaped,
                "{\"text\":\"" + nested + "\"}",
                "An unrelated credential sk-test-another-private-credential-value");
    }

    @ParameterizedTest
    @MethodSource("credentialBodies")
    void omitsCredentialBearingBodiesAndScreensDecisionsAndMetadata(String unsafe) throws Exception {
        Path saved;
        try (var capture = EvaluationCapture.open(project(), SECRET, ignored -> fail("Unexpected warning"))) {
            saved = capture.directory();
            UUID requestId = UUID.randomUUID();
            UUID id = capture.request(requestId, unsafe);
            capture.response(id, 1, 401, unsafe.getBytes(StandardCharsets.UTF_8));
            capture.decision(requestId, "interpretation", "REJECTED", null, null, unsafe);
            capture.attempt(id, new ApiCallObservation(requestId, unsafe, null, 1, Instant.now(), 4,
                    401, GptException.Code.AUTHENTICATION, null, null, null, null, null, null));
            assertFalse(Files.exists(saved.resolve(id + "/request.json")));
            assertFalse(Files.exists(saved.resolve(id + "/attempt-1-response.txt")));
            List<JsonObject> events = events(saved);
            assertEquals("OMITTED_CREDENTIAL", events.get(0).get("body_status").getAsString());
            assertEquals("OMITTED_CREDENTIAL", events.get(1).get("body_status").getAsString());
            assertEquals("[omitted: credential-like content]", events.get(2).get("detail").getAsString());
            assertEquals("[omitted: credential-like content]",
                    events.get(3).getAsJsonObject("metadata").get("requestedModel").getAsString());
            assertFalse(Files.readString(saved.resolve("events.jsonl")).contains(SECRET));
        }
        JsonObject end = json(saved.resolve("session-end.json"));
        assertEquals("COMPLETE_WITH_BODY_OMISSIONS", end.get("recording_status").getAsString());
        assertEquals(2, end.get("omitted_body_count").getAsInt());
    }

    @Test
    void invalidUtf8IsExplicitlyOmittedAndMissingResponseHasUnknownUsage() throws Exception {
        try (var capture = EvaluationCapture.open(project(), SECRET, ignored -> fail("Unexpected warning"))) {
            UUID requestId = UUID.randomUUID();
            UUID id = capture.request(requestId, "{}");
            capture.response(id, 1, 200, new byte[] {(byte) 0xc3, 0x28});
            capture.attempt(id, observation(requestId, 1));
            capture.attempt(id, observation(requestId, 2));
            List<JsonObject> events = events(capture.directory());
            assertEquals("OMITTED_INVALID_UTF8", events.get(1).get("body_status").getAsString());
            assertEquals("UNAVAILABLE", events.get(3).get("response_body").getAsString());
            assertTrue(events.get(3).getAsJsonObject("metadata").get("outputTokens").isJsonNull());
            assertFalse(Files.exists(capture.directory().resolve(id + "/attempt-1-response.txt")));
        }
    }

    @Test
    void separateSessionsNeverOverwriteEarlierArtifacts() throws Exception {
        Path project = project();
        Path first;
        try (var capture = EvaluationCapture.open(project, SECRET, ignored -> fail("Unexpected warning"))) {
            first = capture.directory();
            capture.request(UUID.randomUUID(), "{\"first\":true}");
        }
        String original = Files.readString(first.resolve("events.jsonl"));
        try (var capture = EvaluationCapture.open(project, SECRET, ignored -> fail("Unexpected warning"))) {
            assertNotEquals(first, capture.directory());
            assertEquals(original, Files.readString(first.resolve("events.jsonl")));
        }
    }

    @Test
    void runtimeWriteFailureWarnsOnceKeepsEarlierEvidenceAndMarksSessionIncomplete() throws Exception {
        List<String> warnings = new ArrayList<>();
        Path saved;
        try (var capture = EvaluationCapture.open(project(), SECRET, warnings::add)) {
            saved = capture.directory();
            UUID requestId = UUID.randomUUID();
            UUID id = capture.request(requestId, "{}");
            capture.response(id, 1, 200, "answer".getBytes(StandardCharsets.UTF_8));
            Files.move(saved.resolve("events.jsonl"), saved.resolve("events-before-failure.jsonl"));
            Files.createDirectory(saved.resolve("events.jsonl"));
            assertDoesNotThrow(() -> capture.attempt(id, observation(requestId, 1)));
            assertDoesNotThrow(() -> capture.decision(requestId, "explanation", "EXPLAINED", null, null, "ok"));
            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0).contains("evidence is incomplete"));
            assertEquals("answer", Files.readString(saved.resolve(id + "/attempt-1-response.txt")));
        }
        assertEquals("INCOMPLETE", json(saved.resolve("session-end.json")).get("recording_status").getAsString());
    }

    private static ApiCallObservation observation(UUID id, int attempt) {
        return new ApiCallObservation(id, "gpt-5-mini", null, attempt,
                Instant.parse("2026-09-25T00:00:00Z"), 8, 200, null, null,
                null, null, null, null, null);
    }

    private static List<JsonObject> events(Path directory) throws Exception {
        return Files.readAllLines(directory.resolve("events.jsonl")).stream()
                .map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
    }

    private static JsonObject json(Path path) throws Exception {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
}
