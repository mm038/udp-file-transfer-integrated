package nettransfer.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import nettransfer.control.command.CommandProposal;
import nettransfer.explanation.ExplanationRequest;
import nettransfer.explanation.SyntheticExplanationFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Offline HTTP metadata checks only; no provider calls or real keys are used. */
@Timeout(10)
class ApiCallObservationTest {
    private static final String KEY = "fixture-telemetry-key-92784";
    private static final String PRIVATE_TEXT = "private synthetic prompt content";
    private HttpServer server;
    private ExecutorService workers;
    private URI endpoint;
    private final List<Reply> replies = new CopyOnWriteArrayList<>();
    private final List<ApiCallObservation> observations = new CopyOnWriteArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workers = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "telemetry-http-test");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(workers);
        server.createContext("/v1/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int index = requests.getAndIncrement();
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
    void successfulCommandRetainsOnlySafeModelUsageAndHttpTiming() {
        respond(200, envelope("completed", "Synthetic clarification", true).toString());
        var request = interpretation();
        Instant before = Instant.now();

        assertInstanceOf(CommandProposal.Clarification.class, commandClient(1).interpret(request));

        ApiCallObservation observation = onlyObservation();
        assertEquals(request.requestId(), observation.requestId());
        assertEquals("test-model", observation.requestedModel());
        assertEquals("gpt-5-mini-2025-08-07", observation.returnedModel());
        assertEquals(1, observation.attempt());
        assertFalse(observation.startedAt().isBefore(before));
        assertTrue(observation.apiLatencyMillis() >= 0);
        assertEquals(200, observation.httpStatus());
        assertNull(observation.failureCode());
        assertEquals("completed", observation.responseStatus());
        assertEquals(120L, observation.inputTokens());
        assertEquals(40L, observation.outputTokens());
        assertEquals(160L, observation.totalTokens());
        assertEquals(80L, observation.cachedInputTokens());
        assertEquals(20L, observation.reasoningTokens());
        assertFalse(observation.toString().contains(PRIVATE_TEXT));
        assertFalse(observation.toString().contains("Synthetic clarification"));
        assertSafe(observation);
    }

    @Test
    void explanationClientSharesUsageObserverWithoutExposingEvidence() {
        var request = explanation();
        JsonObject draft = new JsonObject();
        draft.addProperty("run_id", request.evidence().runId().toString());
        draft.addProperty("transfer_id", request.evidence().transferId().toString());
        draft.add("observations", new JsonArray());
        draft.add("hypotheses", new JsonArray());
        JsonArray limitations = new JsonArray();
        limitations.add("SYNTHETIC evidence only.");
        draft.add("limitations", limitations);
        respond(200, envelope("completed", draft.toString(), true).toString());

        assertEquals(request.evidence().runId(), explanationClient().explain(request).runId());

        ApiCallObservation observation = onlyObservation();
        assertEquals(request.requestId(), observation.requestId());
        assertEquals(160L, observation.totalTokens());
        assertNull(observation.failureCode());
        assertFalse(observation.toString().contains(request.evidence().runId().toString()));
        assertFalse(observation.toString().contains("SYNTHETIC evidence"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void incompleteHttp200RetainsUsageAndDecodeFailure(boolean explanation) {
        respond(200, envelope("incomplete", "", true).toString());

        GptException error = assertThrows(GptException.class, () -> {
            if (explanation) explanationClient().explain(explanation());
            else commandClient(1).interpret(interpretation());
        });

        assertEquals(GptException.Code.INCOMPLETE, error.code());
        ApiCallObservation observation = onlyObservation();
        assertEquals(GptException.Code.INCOMPLETE, observation.failureCode());
        assertEquals("incomplete", observation.responseStatus());
        assertEquals(200, observation.httpStatus());
        assertEquals(160L, observation.totalTokens());
    }

    @Test
    void retryRecordsEachAttemptUsingSameCorrelationAndNoProviderErrorText() {
        respond(429, "{\"error\":\"private provider detail " + KEY + "\"}");
        respond(200, envelope("completed", "Clarify", false).toString());
        var request = interpretation();

        commandClient(2).interpret(request);

        assertEquals(2, observations.size());
        assertEquals(2, requests.get());
        ApiCallObservation first = observations.get(0);
        assertEquals(1, first.attempt());
        assertEquals(429, first.httpStatus());
        assertEquals(GptException.Code.RATE_LIMIT, first.failureCode());
        assertNull(first.totalTokens());
        assertNull(first.returnedModel());
        assertFalse(first.toString().contains("private provider detail"));
        assertEquals(2, observations.get(1).attempt());
        assertNull(observations.get(1).failureCode());
        observations.forEach(observation -> {
            assertEquals(request.requestId(), observation.requestId());
            assertSafe(observation);
        });
    }

    @Test
    void authenticationFailureIsRecordedWithoutRetryOrBodyMetadata() {
        respond(401, "{\"model\":\"" + KEY + "\",\"error\":\"private diagnostic\"}");

        assertEquals(GptException.Code.AUTHENTICATION, assertThrows(GptException.class,
                () -> commandClient(3).interpret(interpretation())).code());

        ApiCallObservation observation = onlyObservation();
        assertEquals(401, observation.httpStatus());
        assertEquals(GptException.Code.AUTHENTICATION, observation.failureCode());
        assertNull(observation.returnedModel());
        assertSafe(observation);
    }

    @Test
    void missingUsageIsUnavailableRatherThanZero() {
        respond(200, envelope("completed", "Clarify", false).toString());
        commandClient(1).interpret(interpretation());

        ApiCallObservation observation = onlyObservation();
        assertNull(observation.inputTokens());
        assertNull(observation.outputTokens());
        assertNull(observation.totalTokens());
        assertNull(observation.cachedInputTokens());
        assertNull(observation.reasoningTokens());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0.5", "\"20\"", "true", "[]", "null", "9223372036854775808"})
    void malformedUsageIsOmittedWithoutChangingSuccessfulDecode(String malformed) {
        JsonObject body = envelope("completed", "Clarify", false);
        body.add("usage", JsonParser.parseString("{\"input_tokens\":" + malformed
                + ",\"output_tokens\":3,\"total_tokens\":" + malformed
                + ",\"input_tokens_details\":{\"cached_tokens\":" + malformed + "}}"));
        respond(200, body.toString());

        commandClient(1).interpret(interpretation());

        ApiCallObservation observation = onlyObservation();
        assertNull(observation.inputTokens());
        assertEquals(3L, observation.outputTokens());
        assertNull(observation.totalTokens());
        assertNull(observation.cachedInputTokens());
        assertNull(observation.failureCode());
    }

    @Test
    void contradictoryUsageTotalsAndDetailsAreOmitted() {
        JsonObject body = envelope("completed", "Clarify", false);
        body.add("usage", JsonParser.parseString("""
                {"input_tokens":2,"output_tokens":3,"total_tokens":99,
                 "input_tokens_details":{"cached_tokens":3},
                 "output_tokens_details":{"reasoning_tokens":4}}
                """));
        respond(200, body.toString());

        commandClient(1).interpret(interpretation());

        ApiCallObservation observation = onlyObservation();
        assertEquals(2L, observation.inputTokens());
        assertEquals(3L, observation.outputTokens());
        assertNull(observation.totalTokens());
        assertNull(observation.cachedInputTokens());
        assertNull(observation.reasoningTokens());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a model with spaces", "sk-an-unknown-secret", "gpt\nprivate diagnostic"})
    void unsafeReturnedModelIsOmitted(String model) {
        JsonObject body = envelope("completed", "Clarify", false);
        body.addProperty("model", model);
        respond(200, body.toString());

        commandClient(1).interpret(interpretation());

        assertNull(onlyObservation().returnedModel());
    }

    @Test
    void unicodeEscapedCredentialInMetadataIsRejectedAndNeverRecorded() {
        JsonObject body = envelope("completed", "Clarify", true);
        body.addProperty("model", KEY);
        StringBuilder encoded = new StringBuilder();
        for (char character : KEY.toCharArray()) encoded.append(String.format("\\u%04x", (int) character));
        respond(200, body.toString().replace(KEY, encoded));

        assertEquals(GptException.Code.INVALID_RESPONSE, assertThrows(GptException.class,
                () -> commandClient(1).interpret(interpretation())).code());

        ApiCallObservation observation = onlyObservation();
        assertEquals(GptException.Code.INVALID_RESPONSE, observation.failureCode());
        assertNull(observation.returnedModel());
        assertNull(observation.totalTokens());
        assertSafe(observation);
    }

    @Test
    void malformedJsonIsRecordedAsDecodeFailureWithoutBody() {
        respond(200, "private invalid body " + KEY);

        assertEquals(GptException.Code.INVALID_RESPONSE, assertThrows(GptException.class,
                () -> commandClient(1).interpret(interpretation())).code());

        ApiCallObservation observation = onlyObservation();
        assertEquals(GptException.Code.INVALID_RESPONSE, observation.failureCode());
        assertEquals(200, observation.httpStatus());
        assertNull(observation.responseStatus());
        assertNull(observation.totalTokens());
        assertSafe(observation);
    }

    @Test
    void bodyTimeoutRetainsKnownHttpStatusAndFailureCode() {
        CountDownLatch release = new CountDownLatch(1);
        replies.add(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        try {
            var settings = new GptSettings("test-model", Duration.ofSeconds(1), Duration.ofMillis(200), 1);
            var client = new ResponsesGptClient(KEY, settings, endpoint, observations::add);
            assertEquals(GptException.Code.TIMEOUT, assertThrows(GptException.class,
                    () -> client.interpret(interpretation())).code());

            ApiCallObservation observation = onlyObservation();
            assertEquals(GptException.Code.TIMEOUT, observation.failureCode());
            assertEquals(200, observation.httpStatus());
            assertTrue(observation.apiLatencyMillis() >= 100);
            assertNull(observation.totalTokens());
        } finally {
            release.countDown();
        }
    }

    @Test
    void failingObserverCannotChangeClientResultOrCauseDuplicateApiCalls() {
        respond(200, envelope("completed", "Clarify", false).toString());
        var client = new ResponsesGptClient(KEY, settings(3), endpoint, observation -> {
            throw new IllegalStateException("observer failure");
        });

        assertInstanceOf(CommandProposal.Clarification.class, client.interpret(interpretation()));
        assertEquals(1, requests.get());
    }

    @Test
    void missingCredentialsProduceNoHttpAttemptOrObservation() {
        var client = new ResponsesGptClient("", settings(1), endpoint, observations::add);

        assertEquals(GptException.Code.MISSING_CREDENTIALS, assertThrows(GptException.class,
                () -> client.interpret(interpretation())).code());

        assertEquals(0, requests.get());
        assertTrue(observations.isEmpty());
    }

    private ApiCallObservation onlyObservation() {
        assertEquals(1, observations.size());
        return observations.get(0);
    }

    private static void assertSafe(ApiCallObservation observation) {
        assertFalse(observation.toString().contains(KEY));
        assertFalse(observation.toString().contains("Authorization"));
        assertFalse(observation.toString().contains("Bearer"));
    }

    private ResponsesGptClient commandClient(int attempts) {
        return new ResponsesGptClient(KEY, settings(attempts), endpoint, observations::add);
    }

    private ResponsesExplanationClient explanationClient() {
        return new ResponsesExplanationClient(KEY, settings(1), endpoint, observations::add);
    }

    private static GptSettings settings(int attempts) {
        return new GptSettings("test-model", Duration.ofSeconds(1), Duration.ofSeconds(2), attempts);
    }

    private static InterpretationRequest interpretation() {
        return new InterpretationRequest(UUID.randomUUID(), PRIVATE_TEXT, List.of("demo"),
                List.of("receiver-a"), null, null, null, null, List.of());
    }

    private static ExplanationRequest explanation() {
        var fixture = SyntheticExplanationFixtures.baseline();
        return new ExplanationRequest(UUID.randomUUID(), PRIVATE_TEXT, fixture.evidence(),
                fixture.state(), fixture.integrity());
    }

    private static JsonObject envelope(String status, String text, boolean usage) {
        JsonObject body = new JsonObject();
        body.addProperty("status", status);
        body.addProperty("model", "gpt-5-mini-2025-08-07");
        JsonObject content = new JsonObject();
        content.addProperty("type", "output_text");
        content.addProperty("text", text);
        JsonArray parts = new JsonArray();
        parts.add(content);
        JsonObject message = new JsonObject();
        message.addProperty("type", "message");
        message.addProperty("role", "assistant");
        message.addProperty("status", "completed");
        message.add("content", parts);
        JsonArray output = new JsonArray();
        output.add(message);
        body.add("output", output);
        if (usage) body.add("usage", JsonParser.parseString("""
                {"input_tokens":120,"output_tokens":40,"total_tokens":160,
                 "input_tokens_details":{"cached_tokens":80},
                 "output_tokens_details":{"reasoning_tokens":20}}
                """));
        return body;
    }

    private void respond(int status, String body) {
        replies.add(exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
    }

    @FunctionalInterface
    private interface Reply {
        void send(HttpExchange exchange) throws IOException;
    }
}
