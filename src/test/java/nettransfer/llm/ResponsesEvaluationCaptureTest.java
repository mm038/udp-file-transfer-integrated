package nettransfer.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP and temporary local artifacts only; no provider calls or real credentials. */
@Timeout(10)
class ResponsesEvaluationCaptureTest {
    private static final String KEY = "synthetic-capture-key-only-9714";
    @TempDir Path directory;
    private HttpServer server;
    private ExecutorService workers;
    private URI endpoint;
    private final List<Reply> replies = new CopyOnWriteArrayList<>();
    private final List<byte[]> sentBodies = new CopyOnWriteArrayList<>();
    private final List<ApiCallObservation> observations = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextReply = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "capture-http-test");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(workers);
        server.createContext("/v1/responses", exchange -> {
            sentBodies.add(exchange.getRequestBody().readAllBytes());
            int index = nextReply.getAndIncrement();
            if (index < replies.size()) replies.get(index).send(exchange);
            else {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        workers.shutdownNow();
    }

    @Test
    void capturedRequestMatchesExactSentBytesAndRepeatedRequestIdsGetSeparateInvocations() throws Exception {
        respond(200, "first response");
        respond(200, "second response");
        UUID requestId = UUID.randomUUID();
        try (EvaluationCapture capture = capture()) {
            ResponsesTransport transport = transport(capture, 1, 2000);
            assertEquals("first response", transport.post(payload("First \"question\"\nα"), requestId, value -> value));
            assertEquals("second response", transport.post(payload("Clarification reply"), requestId, value -> value));
            List<Path> requests = requestFiles(capture);
            assertEquals(2, requests.size());
            assertNotEquals(requests.get(0).getParent(), requests.get(1).getParent());
            for (int index = 0; index < sentBodies.size(); index++) {
                byte[] sent = sentBodies.get(index);
                Path recorded = requests.stream().filter(path -> {
                    try { return java.util.Arrays.equals(sent, Files.readAllBytes(path)); }
                    catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
                }).findFirst().orElseThrow();
                assertEquals(index == 0 ? "first response" : "second response",
                        Files.readString(recorded.getParent().resolve("attempt-1-response.txt")));
            }
            assertEquals(2, observations.size());
            assertTrue(observations.stream().allMatch(item -> item.requestId().equals(requestId) && item.attempt() == 1));
            assertFalse(Files.readString(capture.directory().resolve("events.jsonl")).contains(KEY));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"malformed", "incomplete", "refused"})
    void returnedBodyIsRetainedBeforeClientRejectsIt(String scenario) throws Exception {
        String body = switch (scenario) {
            case "malformed" -> "{malformed model prose";
            case "incomplete" -> "{\"status\":\"incomplete\",\"model\":\"gpt-5-mini\",\"output\":[],"
                    + "\"usage\":{\"input_tokens\":9,\"output_tokens\":3,\"total_tokens\":12}}";
            default -> "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\","
                    + "\"status\":\"completed\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"Fixture refusal prose\"}]}]}";
        };
        respond(200, body);
        try (EvaluationCapture capture = capture()) {
            var client = new ResponsesGptClient(KEY, settings(2, 2000), endpoint, observations::add, capture);
            GptException failure = assertThrows(GptException.class, () -> client.interpret(interpretation()));
            assertEquals(switch (scenario) {
                case "malformed" -> GptException.Code.INVALID_RESPONSE;
                case "incomplete" -> GptException.Code.INCOMPLETE;
                default -> GptException.Code.REFUSED;
            }, failure.code());
            Path request = onlyRequest(capture);
            assertEquals(body, Files.readString(request.getParent().resolve("attempt-1-response.txt")));
            assertEquals(1, sentBodies.size(), "Decoder failures must not be retried");
            assertEquals(failure.code(), observations.get(0).failureCode());
            String events = Files.readString(capture.directory().resolve("events.jsonl"));
            assertTrue(events.contains(failure.code().name()), "Capture must retain the failed-attempt metadata");
            if (scenario.equals("incomplete")) assertEquals(12L, observations.get(0).totalTokens());
        }
    }

    @Test
    void retryKeepsOneExactRequestAndEveryReturnedBodyAndAttempt() throws Exception {
        String throttled = "{\"error\":\"fixture throttling\"}";
        String completed = "{\"status\":\"completed\",\"model\":\"gpt-5-mini\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":4,\"total_tokens\":14}}";
        respond(429, throttled);
        respond(200, completed);
        try (EvaluationCapture capture = capture()) {
            assertEquals(completed, transport(capture, 2, 2000).post(payload("retry"), UUID.randomUUID(), value -> value));
            Path request = onlyRequest(capture);
            assertArrayEquals(sentBodies.get(0), Files.readAllBytes(request));
            assertArrayEquals(sentBodies.get(0), sentBodies.get(1));
            assertEquals(throttled, Files.readString(request.getParent().resolve("attempt-1-response.txt")));
            assertEquals(completed, Files.readString(request.getParent().resolve("attempt-2-response.txt")));
            assertEquals(List.of(1, 2), observations.stream().map(ApiCallObservation::attempt).toList());
            assertEquals(GptException.Code.RATE_LIMIT, observations.get(0).failureCode());
            assertNull(observations.get(1).failureCode());
            assertEquals(14L, observations.get(1).totalTokens());
            String events = Files.readString(capture.directory().resolve("events.jsonl"));
            assertTrue(events.contains("RATE_LIMIT"));
            assertTrue(events.contains("attempt-1-response.txt"));
            assertTrue(events.contains("attempt-2-response.txt"));
            List<JsonObject> attempts = events(capture, "attempt");
            assertEquals(2, attempts.size());
            assertEquals(attempts.get(0).get("capture_id"), attempts.get(1).get("capture_id"));
            assertEquals(1, attempts.get(0).get("attempt").getAsInt());
            assertEquals(2, attempts.get(1).get("attempt").getAsInt());
            assertEquals("RATE_LIMIT", attempts.get(0).getAsJsonObject("metadata").get("failureCode").getAsString());
            assertEquals(14, attempts.get(1).getAsJsonObject("metadata").get("totalTokens").getAsInt());
            assertEquals("SEE_RESPONSE_EVENT", attempts.get(1).get("response_body").getAsString());
        }
    }

    @Test
    void credentialBearingHttpErrorIsOmittedWithoutChangingStatusOrRetryBehavior() throws Exception {
        respond(401, "{\"error\":\"echo " + KEY + "\"}");
        try (EvaluationCapture capture = capture()) {
            assertEquals(GptException.Code.AUTHENTICATION, assertThrows(GptException.class,
                    () -> transport(capture, 2, 2000).post(payload("authentication"), UUID.randomUUID(), value -> value)).code());
            Path request = onlyRequest(capture);
            assertFalse(Files.exists(request.getParent().resolve("attempt-1-response.txt")));
            String events = Files.readString(capture.directory().resolve("events.jsonl"));
            assertTrue(events.contains("OMITTED_CREDENTIAL"));
            assertTrue(events.contains("AUTHENTICATION"));
            assertFalse(events.contains(KEY));
            assertEquals(1, sentBodies.size());
        }
    }

    @Test
    void oversizedBodyKeepsFailureMetadataWithoutInventingACompleteResponse() throws Exception {
        respond(200, "x".repeat(1_048_577));
        try (EvaluationCapture capture = capture()) {
            assertEquals(GptException.Code.INVALID_RESPONSE, assertThrows(GptException.class,
                    () -> transport(capture, 1, 2000).post(payload("oversize"), UUID.randomUUID(), value -> value)).code());
            assertFalse(Files.exists(onlyRequest(capture).getParent().resolve("attempt-1-response.txt")));
            assertEquals(200, observations.get(0).httpStatus());
            assertEquals(GptException.Code.INVALID_RESPONSE, observations.get(0).failureCode());
            assertTrue(Files.readString(capture.directory().resolve("events.jsonl")).contains("INVALID_RESPONSE"));
            assertEquals("UNAVAILABLE", events(capture, "attempt").get(0).get("response_body").getAsString());
        }
    }

    @Test
    void timeoutDuringBodyKeepsAttemptMetadataWithoutSavingPartialBody() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        replies.add(exchange -> {
            exchange.sendResponseHeaders(200, 20);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {
                releaseBody.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        try (EvaluationCapture capture = capture()) {
            assertEquals(GptException.Code.TIMEOUT, assertThrows(GptException.class,
                    () -> transport(capture, 1, 500).post(payload("stalled body"), UUID.randomUUID(), value -> value)).code());
            assertFalse(Files.exists(onlyRequest(capture).getParent().resolve("attempt-1-response.txt")));
            assertEquals(200, observations.get(0).httpStatus());
            assertEquals(GptException.Code.TIMEOUT, observations.get(0).failureCode());
            assertTrue(Files.readString(capture.directory().resolve("events.jsonl")).contains("TIMEOUT"));
            assertEquals("UNAVAILABLE", events(capture, "attempt").get(0).get("response_body").getAsString());
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void captureAndExistingObserverCanBothRunWithoutObserverFailureCausingRetry() throws Exception {
        respond(200, "safe response");
        try (EvaluationCapture capture = capture()) {
            ResponsesTransport transport = new ResponsesTransport(KEY, settings(2, 2000), endpoint,
                    observation -> { throw new IllegalStateException("observer fixture failure"); }, capture);
            assertEquals("safe response", transport.post(payload("observer failure"), UUID.randomUUID(), value -> value));
            assertEquals(1, sentBodies.size());
            assertEquals("safe response", Files.readString(onlyRequest(capture).getParent().resolve("attempt-1-response.txt")));
        }
    }

    @Test
    void recordingWriteFailureDoesNotRetrySuccessfulHttpOrSuppressExistingObserver() throws Exception {
        respond(200, "successful response despite recording failure");
        Path captureDirectory;
        try (EvaluationCapture capture = capture()) {
            captureDirectory = capture.directory();
            Path eventFile = captureDirectory.resolve("events.jsonl");
            Files.move(eventFile, captureDirectory.resolve("events-before-failure.jsonl"));
            Files.createDirectory(eventFile);
            assertEquals("successful response despite recording failure",
                    transport(capture, 3, 2000).post(payload("recording unavailable"), UUID.randomUUID(), value -> value));
            assertEquals(1, sentBodies.size());
            assertEquals(1, observations.size());
            assertNull(observations.get(0).failureCode());
        }
        JsonObject completed = JsonParser.parseString(Files.readString(captureDirectory.resolve("session-end.json")))
                .getAsJsonObject();
        assertEquals("INCOMPLETE", completed.get("recording_status").getAsString());
        assertTrue(Files.isRegularFile(captureDirectory.resolve("events-before-failure.jsonl")));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preflightFailureOnReusedRequestIdCannotInheritEarlierCapture(boolean missingCredentials) throws Exception {
        respond(200, "earlier clarification");
        UUID requestId = UUID.randomUUID();
        try (EvaluationCapture capture = capture()) {
            transport(capture, 1, 2000).post(payload("first"), requestId, value -> value);
            ResponsesTransport next = missingCredentials
                    ? new ResponsesTransport("", settings(1, 2000), endpoint, observations::add, capture)
                    : transport(capture, 1, 2000);
            assertThrows(GptException.class,
                    () -> next.post(payload(missingCredentials ? "second" : KEY), requestId, value -> value));
            capture.decision(requestId, "INTERPRETATION", "REJECTED", null, null, "preflight failed");
            JsonObject decision = events(capture, "java_decision").get(0);
            assertTrue(decision.get("capture_id").isJsonNull());
            assertEquals(requestId.toString(), decision.get("request_id").getAsString());
            assertEquals(1, requestFiles(capture).size());
            assertEquals(1, sentBodies.size());
        }
    }

    @Test
    void explanationAdapterAlsoRetainsReturnedBytesBeforeRejectingMalformedDraft() throws Exception {
        String body = "{fixture malformed explanation response";
        respond(200, body);
        var fixture = nettransfer.explanation.SyntheticExplanationFixtures.baseline();
        var request = new nettransfer.explanation.ExplanationRequest(UUID.randomUUID(), "Explain fixture",
                fixture.evidence(), fixture.state(), fixture.integrity());
        try (EvaluationCapture capture = capture()) {
            var client = new ResponsesExplanationClient(KEY, settings(1, 2000), endpoint, observations::add, capture);
            assertEquals(GptException.Code.INVALID_RESPONSE,
                    assertThrows(GptException.class, () -> client.explain(request)).code());
            Path requestFile = onlyRequest(capture);
            assertArrayEquals(sentBodies.get(0), Files.readAllBytes(requestFile));
            assertEquals(body, Files.readString(requestFile.getParent().resolve("attempt-1-response.txt")));
            assertEquals(request.requestId(), observations.get(0).requestId());
        }
    }

    private EvaluationCapture capture() throws IOException {
        String evidenceRoot = System.getProperty("nettransfer.testEvidenceRoot");
        Path projectRoot = directory;
        if (evidenceRoot != null && !evidenceRoot.isBlank()) {
            Path parent = Path.of(evidenceRoot).resolve("transport-tests");
            Files.createDirectories(parent);
            projectRoot = Files.createTempDirectory(parent, "case-");
        }
        return EvaluationCapture.open(projectRoot, KEY, ignored -> { });
    }

    private ResponsesTransport transport(EvaluationCapture capture, int attempts, int timeoutMillis) {
        return new ResponsesTransport(KEY, settings(attempts, timeoutMillis), endpoint, observations::add, capture);
    }

    private static GptSettings settings(int attempts, int timeoutMillis) {
        return new GptSettings("gpt-5-mini", Duration.ofSeconds(1), Duration.ofMillis(timeoutMillis), attempts);
    }

    private static JsonObject payload(String text) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", "gpt-5-mini");
        payload.addProperty("input", text);
        return payload;
    }

    private static InterpretationRequest interpretation() {
        return new InterpretationRequest(UUID.randomUUID(), "Fixture question", List.of("report"),
                List.of("receiver-a"), null, null, null, null, List.of());
    }

    private static Path onlyRequest(EvaluationCapture capture) throws IOException {
        List<Path> requests = requestFiles(capture);
        assertEquals(1, requests.size());
        return requests.get(0);
    }

    private static List<Path> requestFiles(EvaluationCapture capture) throws IOException {
        try (var files = Files.walk(capture.directory())) {
            return files.filter(path -> path.getFileName().toString().equals("request.json")).sorted().toList();
        }
    }

    private static List<JsonObject> events(EvaluationCapture capture, String type) throws IOException {
        return Files.readAllLines(capture.directory().resolve("events.jsonl")).stream()
                .map(line -> JsonParser.parseString(line).getAsJsonObject())
                .filter(event -> event.get("event").getAsString().equals(type)).toList();
    }

    private void respond(int status, String body) {
        replies.add(exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            } catch (IOException ignored) {
                // An intentional oversized-body test cancels the client subscription before write completes.
            } finally {
                exchange.close();
            }
        });
    }

    @FunctionalInterface
    private interface Reply {
        void send(HttpExchange exchange) throws IOException;
    }
}
