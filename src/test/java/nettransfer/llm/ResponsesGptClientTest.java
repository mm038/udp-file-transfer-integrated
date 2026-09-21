package nettransfer.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import nettransfer.cli.TransferCli;
import nettransfer.control.TransferMetrics;
import nettransfer.control.TransferRequest;
import nettransfer.control.TransferService;
import nettransfer.control.TransferSnapshot;
import nettransfer.control.TransferStart;
import nettransfer.control.TransferSummary;
import nettransfer.control.command.CommandParser;
import nettransfer.control.command.CommandProposal;
import nettransfer.control.command.TransferConfiguration;
import nettransfer.control.TransferServiceException;
import nettransfer.control.simulation.FakeTransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP against local fixtures only: these checks do not call OpenAI or evaluate model quality. */
@Timeout(10)
class ResponsesGptClientTest {
    private static final String KEY = "fixture-key-never-use-with-a-live-provider-92784";
    private static final String START_ARGUMENTS = "{\"file_id\":\"report\",\"receiver_id\":\"receiver-a\","
            + "\"window_bytes\":null,\"timeout_ms\":null}";
    private HttpServer server;
    private ExecutorService worker;
    private URI endpoint;
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final List<Reply> replies = new CopyOnWriteArrayList<>();
    private final AtomicInteger replyIndex = new AtomicInteger();

    @BeforeEach
    void startLocalServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        worker = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "gpt-http-test");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(worker);
        server.createContext("/v1/responses", this::serve);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses");
    }

    @AfterEach
    void stopLocalServer() {
        server.stop(0);
        worker.shutdownNow();
    }

    @Test
    void requestContainsStrictToolsAndOnlyApprovedResourceIds() {
        respond(200, response(functionCall("start_transfer", START_ARGUMENTS)));
        UUID current = UUID.randomUUID();
        UUID last = UUID.randomUUID();
        InterpretationRequest request = new InterpretationRequest(UUID.randomUUID(), "Send report to receiver-a",
                List.of("report", "notes"), List.of("receiver-a"), current, current, last, last,
                List.of(new InterpretationRequest.Turn("user", "send report"),
                        new InterpretationRequest.Turn("assistant", "Which receiver?")));

        CommandProposal.Calls proposal = assertInstanceOf(CommandProposal.Calls.class, client(1).interpret(request));
        assertEquals("start_transfer", proposal.calls().get(0).name());
        assertEquals(START_ARGUMENTS, proposal.calls().get(0).argumentsJson());
        CapturedRequest captured = requests.get(0);
        assertEquals("POST", captured.method());
        assertEquals("Bearer " + KEY, captured.authorization());
        assertTrue(captured.contentType().startsWith("application/json"));
        JsonObject body = JsonParser.parseString(captured.body()).getAsJsonObject();
        assertEquals("test-model", body.get("model").getAsString());
        assertFalse(body.get("parallel_tool_calls").getAsBoolean());
        assertFalse(body.get("store").getAsBoolean());
        String instructions = body.get("instructions").getAsString();
        assertTrue(instructions.contains("KB means 1000 bytes"));
        assertTrue(instructions.contains("KiB means 1024 bytes"));
        assertTrue(instructions.contains("one second is 1000 ms"));
        assertTrue(instructions.contains("never ask for them or confirmation of defaults"));
        assertTrue(instructions.contains("this console cannot cancel or delete anything"));
        assertTrue(instructions.contains("Preserve explicit invalid integer settings for Java to reject"));
        assertTrue(instructions.contains("current or last"));
        assertEquals("commands-v2", body.getAsJsonObject("metadata").get("prompt_schema_version").getAsString());
        assertEquals(request.requestId().toString(), captured.requestId());
        assertEquals(request.requestId().toString(), body.getAsJsonObject("metadata").get("request_id").getAsString());
        assertFalse(captured.body().contains(KEY));
        assertTrue(captured.body().contains("report"));
        assertTrue(captured.body().contains("receiver-a"));
        assertTrue(captured.body().contains("Which receiver?"));
        assertTrue(captured.body().contains(current.toString()));
        assertTrue(captured.body().contains(last.toString()));
        assertFalse(captured.body().contains("data/input/"));

        JsonArray tools = body.getAsJsonArray("tools");
        assertEquals(3, tools.size());
        Map<String, JsonObject> schemas = new java.util.HashMap<>();
        for (JsonElement tool : tools) {
            JsonObject function = tool.getAsJsonObject();
            assertEquals("function", function.get("type").getAsString());
            assertTrue(function.get("strict").getAsBoolean());
            JsonObject schema = function.getAsJsonObject("parameters");
            assertEquals("object", schema.get("type").getAsString());
            assertFalse(schema.get("additionalProperties").getAsBoolean());
            Set<String> properties = schema.getAsJsonObject("properties").keySet();
            Set<String> required = new java.util.HashSet<>();
            schema.getAsJsonArray("required").forEach(field -> required.add(field.getAsString()));
            assertEquals(properties, required, "Strict schemas require every declared property");
            schemas.put(function.get("name").getAsString(), schema);
        }
        assertEquals(Set.of("start_transfer", "status", "explain"), schemas.keySet());
        assertEquals(Set.of("file_id", "receiver_id", "window_bytes", "timeout_ms"),
                schemas.get("start_transfer").getAsJsonObject("properties").keySet());
        assertNullable(schemas.get("start_transfer"), "window_bytes", "integer");
        assertNullable(schemas.get("start_transfer"), "timeout_ms", "integer");
        assertNullable(schemas.get("status"), "transfer_id", "string");
        assertNullable(schemas.get("explain"), "run_id", "string");
    }

    @Test
    void reasoningItemsCanPrecedeTheSingleProposedCall() {
        respond(200, response("{\"type\":\"reasoning\",\"id\":\"rs_fixture\",\"summary\":[]}",
                functionCall("status", "{\"transfer_id\":null}")));

        CommandProposal.Calls proposal = assertInstanceOf(CommandProposal.Calls.class, client(1).interpret(request()));
        assertEquals(1, proposal.calls().size());
        assertEquals("status", proposal.calls().get(0).name());
    }

    @Test
    void ordinaryOutputTextIsOnlyAClarificationNotAStart() {
        respond(200, response(message("Which approved file should I send?")));

        CommandProposal.Clarification proposal = assertInstanceOf(CommandProposal.Clarification.class,
                client(1).interpret(request()));
        assertEquals("Which approved file should I send?", proposal.question());
    }

    @Test
    void allToolCallsAreReturnedTogetherSoTheDispatcherCanRejectMultipleBeforeStarting() {
        respond(200, response(functionCall("start_transfer", START_ARGUMENTS),
                functionCall("status", "{\"transfer_id\":null}")));

        CommandProposal.Calls proposal = assertInstanceOf(CommandProposal.Calls.class, client(1).interpret(request()));
        assertEquals(2, proposal.calls().size());
    }

    @Test
    void modelArgumentsRemainUntrustedAndCrossTheExistingStrictParser() {
        String invalidArguments = "{\"file_id\":\"report\",\"receiver_id\":\"receiver-a\","
                + "\"window_bytes\":\"4096\",\"timeout_ms\":null}";
        respond(200, response(functionCall("start_transfer", invalidArguments)));

        CommandProposal.Calls proposal = assertInstanceOf(CommandProposal.Calls.class, client(1).interpret(request()));
        assertEquals(invalidArguments, proposal.calls().get(0).argumentsJson());
        assertThrows(TransferServiceException.class, () -> new CommandParser().parse(proposal.calls().get(0)));
    }

    @Test
    void malformedInnerArgumentsAreRejectedBeforeReturningAProposal() {
        String brokenArguments = START_ARGUMENTS.substring(0, START_ARGUMENTS.length() - 1);
        respond(200, response(functionCall("start_transfer", brokenArguments)));

        assertError(GptException.Code.INVALID_RESPONSE, client(2));
        assertEquals(1, requests.size(), "Malformed arguments cannot be fixed by retrying the same response");
    }

    @Test
    void unknownToolCannotAcquireExecutionAuthorityFromTheAdapter() {
        respond(200, response(functionCall("delete_files", "{}")));

        CommandProposal.Calls proposal = assertInstanceOf(CommandProposal.Calls.class, client(1).interpret(request()));
        assertThrows(TransferServiceException.class, () -> new CommandParser().parse(proposal.calls().get(0)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "[]", "null", "not-json",
            "{\"status\":\"completed\"}",
            "{\"status\":\"completed\",\"output\":[]}",
            "{\"status\":\"completed\",\"output\":null}",
            "{\"status\":\"completed\",\"output\":{}}",
            "{\"status\":\"completed\",\"output\":[null]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"web_search_call\"}]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\"}]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"status\",\"arguments\":{}}]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\"status\",\"arguments\":\"{}\"}]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[]}]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"image\"}]}]}",
            "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":17}]}]}",
            "{\"status\":\"completed\",\"status\":\"completed\",\"output\":[]}",
            "{\"status\":\"completed\",\"output\":[]} trailing",
            "{\"status\":\"completed\",\"output\":[],}"
    })
    void malformedOrUnexpectedResponseFailsClosed(String body) {
        respond(200, body);

        assertError(GptException.Code.INVALID_RESPONSE, client(1));
        assertEquals(1, requests.size(), "Invalid responses are not retried");
    }

    @Test
    void mixedTextAndToolCallFailsBeforeAnyProposalIsReturned() {
        respond(200, response(message("The transfer already completed"), functionCall("start_transfer", START_ARGUMENTS)));

        assertError(GptException.Code.INVALID_RESPONSE, client(1));
    }

    @Test
    void refusalWinsEvenWhenTheSameResponseIncludesAToolCall() {
        String refusal = "{\"type\":\"message\",\"role\":\"assistant\",\"status\":\"completed\","
                + "\"content\":[{\"type\":\"refusal\",\"refusal\":\"Do not expose this provider text\"}]}";
        respond(200, response(functionCall("start_transfer", START_ARGUMENTS), refusal));

        GptException error = assertError(GptException.Code.REFUSED, client(1));
        assertFalse(error.getMessage().contains("provider text"));
    }

    @Test
    void incompleteResponseWithOtherwiseValidCallCannotBeExecuted() {
        respond(200, response(functionCall("start_transfer", START_ARGUMENTS))
                .replaceFirst("\"status\":\"completed\"", "\"status\":\"incomplete\""));

        assertError(GptException.Code.INCOMPLETE, client(1));
    }

    @Test
    void incompleteOutputItemCannotBeExecuted() {
        respond(200, response(functionCall("start_transfer", START_ARGUMENTS)
                .replace("\"status\":\"completed\"", "\"status\":\"incomplete\"")));

        assertError(GptException.Code.INCOMPLETE, client(1));
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    void transientErrorRetriesTheSameLogicalRequestAndReturnsOneProposal(int status) {
        respond(status, "{\"error\":{\"message\":\"temporary fixture\"}}");
        respond(200, response(functionCall("start_transfer", START_ARGUMENTS)));

        CommandProposal.Calls proposal = assertInstanceOf(CommandProposal.Calls.class, client(2).interpret(request()));
        assertEquals(1, proposal.calls().size());
        assertEquals(2, requests.size());
        assertEquals(requests.get(0).body(), requests.get(1).body());
        assertEquals(requests.get(0).authorization(), requests.get(1).authorization());
        assertEquals(requests.get(0).requestId(), requests.get(1).requestId());
    }

    @Test
    void exhaustedRateLimitUsesFiniteAttemptsAndSafeError() {
        respond(429, "{\"error\":\"" + KEY + "\"}");
        respond(429, "{\"error\":\"" + KEY + "\"}");

        GptException error = assertError(GptException.Code.RATE_LIMIT, client(2));
        assertEquals(2, requests.size());
        assertSafe(error);
    }

    @Test
    void httpRetryThroughCliStartsExactlyOnceAndPreservesJavaRequestIdentity(@TempDir Path root) throws Exception {
        Path source = root.resolve("data/input/report.txt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "File contents must stay on this machine.");
        TransferConfiguration configuration = TransferConfiguration.localhost(root,
                Map.of("report", Path.of("data/input/report.txt")));
        TransferMetrics unknown = TransferMetrics.unavailable("Synthetic API integration fixture");
        FakeTransferService fake = new FakeTransferService(FakeTransferService.Scenario.success(List.of(unknown), unknown));
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<TransferRequest> accepted = new AtomicReference<>();
        TransferService service = new TransferService() {
            @Override public TransferStart start(TransferRequest request) {
                starts.incrementAndGet();
                accepted.set(request);
                return fake.start(request);
            }
            @Override public TransferSnapshot status(UUID transferId) { return fake.status(transferId); }
            @Override public TransferSummary summary(UUID runId) { return fake.summary(runId); }
        };
        respond(429, "{\"error\":\"temporary rate limit\"}");
        respond(200, response(functionCall("start_transfer", START_ARGUMENTS)));
        StringWriter output = new StringWriter();
        TransferCli cli = new TransferCli(service, configuration, new StringReader(""), new PrintWriter(output), client(2));

        assertTrue(cli.handleLine("send report to receiver-a"));

        assertEquals(2, requests.size());
        assertEquals(1, starts.get());
        assertEquals(accepted.get().requestId().toString(), requests.get(0).requestId());
        assertEquals(requests.get(0).requestId(), requests.get(1).requestId());
        assertTrue(output.toString().contains("Start accepted [SYNTHETIC]"));
        assertFalse(output.toString().contains(KEY));
        assertFalse(requests.get(0).body().contains(Files.readString(source)));
        assertFalse(requests.get(0).body().contains("report.txt"));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void authenticationFailureNeverRetriesAndNeverExposesProviderBody(int status) {
        respond(status, "{\"error\":{\"message\":\"Rejected credential " + KEY + "\"}}");

        GptException error = assertError(GptException.Code.AUTHENTICATION, client(3));
        assertEquals(1, requests.size());
        assertSafe(error);
        assertFalse(error.getMessage().contains("Rejected credential"));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 422})
    void configurationErrorsAreNotRetried(int status) {
        respond(status, "private provider detail");

        assertError(GptException.Code.INVALID_CONFIGURATION, client(3));
        assertEquals(1, requests.size());
    }

    @Test
    void exhaustedServerFailureHasNoRawResponseDetails() {
        respond(503, "private provider detail");
        respond(503, "private provider detail");

        GptException error = assertError(GptException.Code.UNAVAILABLE, client(2));
        assertEquals(2, requests.size());
        assertFalse(error.getMessage().contains("private provider"));
    }

    @Test
    void apiFailureTextCannotBeMistakenForAFunctionResult() {
        respond(200, "{\"status\":\"failed\",\"error\":{\"message\":\"private detail\"},\"output\":[]}");

        GptException error = assertThrows(GptException.class, () -> client(1).interpret(request()));
        assertFalse(error.getMessage().contains("private detail"));
        assertEquals(1, requests.size());
    }

    @Test
    void missingCredentialIsRejectedBeforeSendingHttp() {
        GptException error = assertThrows(GptException.class,
                () -> new ResponsesGptClient("", settings(1), endpoint).interpret(request()));

        assertEquals(GptException.Code.MISSING_CREDENTIALS, error.code());
        assertTrue(requests.isEmpty());
    }

    @Test
    void environmentFactoryDoesNotNeedNetworkToReportMissingCredential() {
        GptException error = assertThrows(GptException.class,
                () -> ResponsesGptClient.fromEnvironment(Map.of()).interpret(request()));

        assertEquals(GptException.Code.MISSING_CREDENTIALS, error.code());
        assertTrue(requests.isEmpty());
    }

    @Test
    void responseEchoingTheActualCredentialIsRejectedAndRedacted() {
        respond(200, response(message("Here is a secret: " + KEY)));

        GptException error = assertError(GptException.Code.INVALID_RESPONSE, client(1));
        assertSafe(error);
    }

    @Test
    void unicodeEscapedCredentialInsideFunctionArgumentsIsAlsoRejected() {
        StringBuilder escapedKey = new StringBuilder();
        for (char character : KEY.toCharArray()) {
            escapedKey.append('\\').append('u').append(String.format("%04x", (int) character));
        }
        String arguments = "{\"run_id\":null,\"question\":\"" + escapedKey + "\"}";
        String body = response(functionCall("explain", arguments));
        assertFalse(body.contains(KEY), "Fixture hides the key inside the nested arguments JSON");
        respond(200, body);

        GptException error = assertError(GptException.Code.INVALID_RESPONSE, client(1));
        assertSafe(error);
    }

    @Test
    void escapedCredentialInUnknownArgumentNameWithMalformedTailCannotReachParserErrors() {
        StringBuilder escapedKey = new StringBuilder();
        for (char character : KEY.toCharArray()) {
            escapedKey.append('\\').append('u').append(String.format("%04x", (int) character));
        }
        String body = response(functionCall("start_transfer", "{\"" + escapedKey + "\":1, invalid}"));
        assertFalse(body.contains(KEY), "Secret is encoded in a field name before malformed JSON");
        respond(200, body);

        GptException error = assertError(GptException.Code.INVALID_RESPONSE, client(1));
        assertSafe(error);
    }

    @Test
    void userInputContainingTheActualCredentialIsNotSentToTheProvider() {
        InterpretationRequest request = new InterpretationRequest(UUID.randomUUID(), "my key is " + KEY,
                List.of("report"), List.of("receiver-a"), null, null, null, null, List.of());

        GptException error = assertThrows(GptException.class, () -> client(1).interpret(request));
        assertEquals(GptException.Code.INVALID_CONFIGURATION, error.code());
        assertTrue(requests.isEmpty());
        assertSafe(error);
    }

    @Test
    void bodyDeadlineAppliesAfterSuccessfulHeaders() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        replies.add(exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {
                releaseBody.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        GptSettings shortDeadline = new GptSettings("test-model", Duration.ofSeconds(1), Duration.ofMillis(150), 1);
        try {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertError(GptException.Code.TIMEOUT, new ResponsesGptClient(KEY, shortDeadline, endpoint)));
            assertEquals(1, requests.size());
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void connectionFailureIsReportedAsTransportWithoutAddressOrCredentialDetails() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            unusedPort = socket.getLocalPort();
        }
        URI unavailable = URI.create("http://127.0.0.1:" + unusedPort + "/v1/responses");

        GptException error = assertError(GptException.Code.TRANSPORT,
                new ResponsesGptClient(KEY, settings(1), unavailable));
        assertSafe(error);
        assertFalse(error.getMessage().contains(Integer.toString(unusedPort)));
    }

    @Test
    void oversizedResponseFailsClosedBeforeParsing() {
        respond(200, response(message("x".repeat(1024 * 1024 + 1))));

        assertError(GptException.Code.INVALID_RESPONSE, client(1));
    }

    @Test
    void redirectIsNotFollowedWithCredential() {
        replies.add(exchange -> {
            exchange.getResponseHeaders().set("Location", endpoint.toString());
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        assertThrows(GptException.class, () -> client(1).interpret(request()));
        assertEquals(1, requests.size());
    }

    private void serve(HttpExchange exchange) throws IOException {
        requests.add(new CapturedRequest(exchange.getRequestMethod(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                exchange.getRequestHeaders().getFirst("X-Client-Request-Id"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
        int index = replyIndex.getAndIncrement();
        if (index >= replies.size()) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            return;
        }
        replies.get(index).send(exchange);
    }

    private void respond(int status, String body) {
        replies.add(exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
    }

    private ResponsesGptClient client(int attempts) {
        return new ResponsesGptClient(KEY, settings(attempts), endpoint);
    }

    private static GptSettings settings(int attempts) {
        return new GptSettings("test-model", Duration.ofSeconds(1), Duration.ofSeconds(2), attempts);
    }

    private static InterpretationRequest request() {
        return new InterpretationRequest(UUID.randomUUID(), "send report to receiver-a",
                List.of("report"), List.of("receiver-a"), null, null, null, null, List.of());
    }

    private static GptException assertError(GptException.Code expected, ResponsesGptClient client) {
        GptException error = assertThrows(GptException.class, () -> client.interpret(request()));
        assertEquals(expected, error.code());
        return error;
    }

    private static void assertSafe(GptException error) {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains(KEY));
    }

    private static void assertNullable(JsonObject schema, String field, String type) {
        JsonArray types = schema.getAsJsonObject("properties").getAsJsonObject(field).getAsJsonArray("type");
        Set<String> values = new java.util.HashSet<>();
        types.forEach(value -> values.add(value.getAsString()));
        assertEquals(Set.of(type, "null"), values);
    }

    private static String functionCall(String name, String arguments) {
        JsonObject call = new JsonObject();
        call.addProperty("type", "function_call");
        call.addProperty("call_id", "call_fixture");
        call.addProperty("name", name);
        call.addProperty("arguments", arguments);
        call.addProperty("status", "completed");
        return call.toString();
    }

    private static String message(String text) {
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
        return message.toString();
    }

    private static String response(String... outputItems) {
        return "{\"id\":\"resp_fixture\",\"status\":\"completed\",\"output\":["
                + String.join(",", outputItems) + "]}";
    }

    private record CapturedRequest(String method, String authorization, String contentType,
                                   String requestId, String body) { }

    @FunctionalInterface
    private interface Reply {
        void send(HttpExchange exchange) throws IOException;
    }
}
