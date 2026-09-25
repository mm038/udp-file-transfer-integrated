package nettransfer.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared bounded HTTP transport; each client supplies and validates its own distinct request/response. */
final class ResponsesTransport {
    static final URI ENDPOINT = URI.create("https://api.openai.com/v1/responses");
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    private final String apiKey;
    private final GptSettings settings;
    private final URI endpoint;
    private final HttpClient http;
    private final Consumer<ApiCallObservation> observer;
    private final EvaluationCapture capture;

    ResponsesTransport(String apiKey, GptSettings settings, URI endpoint) {
        this(apiKey, settings, endpoint, observation -> { });
    }

    ResponsesTransport(String apiKey, GptSettings settings, URI endpoint,
                       Consumer<ApiCallObservation> observer) {
        this(apiKey, settings, endpoint, observer, EvaluationCapture.disabled());
    }

    ResponsesTransport(String apiKey, GptSettings settings, URI endpoint,
                       Consumer<ApiCallObservation> observer, EvaluationCapture capture) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.settings = Objects.requireNonNull(settings, "settings");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.capture = Objects.requireNonNull(capture, "capture");
        if (!ENDPOINT.equals(endpoint) && !isLoopbackEndpoint(endpoint)) {
            throw new GptException(GptException.Code.INVALID_CONFIGURATION);
        }
        http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    <T> T post(JsonObject payload, UUID requestId, Function<String, T> decoder) {
        capture.beginInvocation(requestId);
        if (apiKey.isBlank()) {
            throw new GptException(GptException.Code.MISSING_CREDENTIALS);
        }
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(decoder, "decoder");
        // No key is copied into model input, even if a user accidentally pastes it into their request.
        if (ResponsesJson.containsSecret(payload, apiKey)) {
            throw new GptException(GptException.Code.INVALID_CONFIGURATION);
        }
        String serializedBody = JSON.toJson(payload);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(endpoint).timeout(settings.requestTimeout())
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("X-Client-Request-Id", requestId.toString())
                    .POST(HttpRequest.BodyPublishers.ofString(serializedBody, StandardCharsets.UTF_8)).build();
        } catch (IllegalArgumentException exception) {
            throw new GptException(GptException.Code.INVALID_CONFIGURATION);
        }

        UUID captureId = capture.request(requestId, serializedBody);

        for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
            Instant startedAt = Instant.now();
            long startNanos = System.nanoTime();
            AtomicInteger receivedStatus = new AtomicInteger();
            HttpResponse<byte[]> response;
            try {
                response = send(request, receivedStatus);
            } catch (GptException exception) {
                observe(captureId, requestId, attempt, startedAt, elapsedMillis(startNanos), receivedStatus.get(),
                        exception.code(), Metadata.EMPTY);
                if (Thread.currentThread().isInterrupted() || attempt == settings.maxAttempts()
                        || (exception.code() != GptException.Code.TRANSPORT
                        && exception.code() != GptException.Code.TIMEOUT)) {
                    throw exception;
                }
                pause(attempt);
                continue;
            }
            long latencyMillis = elapsedMillis(startNanos);
            int status = response.statusCode();
            // Preserve bounded returned bytes before any parsing, refusal, or draft validation.
            capture.response(captureId, attempt, status, response.body());
            if (status == 200) {
                Metadata metadata = Metadata.EMPTY;
                try {
                    String body = decodeUtf8(response.body());
                    metadata = metadata(body);
                    T result = decoder.apply(body);
                    observe(captureId, requestId, attempt, startedAt, latencyMillis, status, null, metadata);
                    return result;
                } catch (GptException exception) {
                    observe(captureId, requestId, attempt, startedAt, latencyMillis, status, exception.code(), metadata);
                    throw exception;
                }
            }
            GptException failure = statusFailure(status);
            observe(captureId, requestId, attempt, startedAt, latencyMillis, status, failure.code(), Metadata.EMPTY);
            if (retryable(status) && attempt < settings.maxAttempts()) {
                pause(attempt);
                continue;
            }
            throw failure;
        }
        throw new GptException(GptException.Code.UNAVAILABLE);
    }

    /** Inspect already parsed outer or nested JSON, so escaped credentials cannot reach error output. */
    void rejectResponseSecret(JsonElement value) {
        if (ResponsesJson.containsSecret(value, apiKey)) {
            throw ResponsesJson.invalid();
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request, AtomicInteger receivedStatus) {
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request,
                responseInfo -> {
                    receivedStatus.set(responseInfo.statusCode());
                    return new ResponsesBodySubscriber();
                });
        try {
            // The explicit future deadline also bounds a stalled response body after successful headers.
            return pending.get(settings.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new GptException(GptException.Code.TIMEOUT);
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new GptException(GptException.Code.TRANSPORT);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (hasCause(cause, ResponsesBodySubscriber.BodyLimitException.class)) {
                throw ResponsesJson.invalid();
            }
            if (hasCause(cause, HttpTimeoutException.class)) {
                throw new GptException(GptException.Code.TIMEOUT);
            }
            throw new GptException(hasCause(cause, IOException.class)
                    ? GptException.Code.TRANSPORT : GptException.Code.UNAVAILABLE);
        }
    }

    private void observe(UUID captureId, UUID requestId, int attempt, Instant startedAt, long latencyMillis,
                         int status, GptException.Code failure, Metadata metadata) {
        var observation = new ApiCallObservation(requestId, settings.model(), metadata.model(), attempt,
                startedAt, latencyMillis, status == 0 ? null : status, failure, metadata.status(),
                metadata.input(), metadata.output(), metadata.total(), metadata.cached(), metadata.reasoning());
        capture.attempt(captureId, observation);
        try {
            observer.accept(observation);
        } catch (RuntimeException ignored) {
            // Optional diagnostics cannot turn a successful command into a failure or trigger a retry.
        }
    }

    private Metadata metadata(String body) {
        try {
            JsonObject response = ResponsesJson.parse(body);
            if (ResponsesJson.containsSecret(response, apiKey)) {
                return Metadata.EMPTY;
            }
            String model = optionalString(response, "model");
            if (model != null && (!model.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                    || model.startsWith("sk-"))) {
                model = null;
            }
            String status = optionalString(response, "status");
            if (status != null && !java.util.Set.of("completed", "incomplete", "in_progress", "queued",
                    "cancelled", "failed").contains(status)) {
                status = null;
            }
            JsonObject usage = optionalObject(response, "usage");
            Long input = tokenCount(usage, "input_tokens");
            Long output = tokenCount(usage, "output_tokens");
            Long total = tokenCount(usage, "total_tokens");
            Long cached = tokenCount(optionalObject(usage, "input_tokens_details"), "cached_tokens");
            Long reasoning = tokenCount(optionalObject(usage, "output_tokens_details"), "reasoning_tokens");
            if (cached != null && input != null && cached > input) cached = null;
            if (reasoning != null && output != null && reasoning > output) reasoning = null;
            if (total != null && input != null && output != null
                    && (input > Long.MAX_VALUE - output || total != input + output)) total = null;
            return new Metadata(model, status, input, output, total, cached, reasoning);
        } catch (GptException exception) {
            return Metadata.EMPTY;
        }
    }

    private static String optionalString(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : null;
    }

    private static JsonObject optionalObject(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static Long tokenCount(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            long count = value.getAsBigDecimal().longValueExact();
            return count >= 0 ? count : null;
        } catch (ArithmeticException | NumberFormatException exception) {
            return null;
        }
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private record Metadata(String model, String status, Long input, Long output, Long total,
                            Long cached, Long reasoning) {
        private static final Metadata EMPTY = new Metadata(null, null, null, null, null, null, null);
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw ResponsesJson.invalid();
        }
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (int depth = 0; throwable != null && depth < 16; depth++, throwable = throwable.getCause()) {
            if (type.isInstance(throwable)) {
                return true;
            }
        }
        return false;
    }

    private static boolean retryable(int status) {
        return status == 408 || status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static GptException statusFailure(int status) {
        return new GptException(switch (status) {
            case 401, 403 -> GptException.Code.AUTHENTICATION;
            case 429 -> GptException.Code.RATE_LIMIT;
            case 408 -> GptException.Code.TIMEOUT;
            default -> status >= 500 || status >= 300 && status < 400
                    ? GptException.Code.UNAVAILABLE : GptException.Code.INVALID_CONFIGURATION;
        });
    }

    private static void pause(int attempt) {
        try {
            Thread.sleep(Duration.ofMillis(100L * attempt).toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GptException(GptException.Code.TRANSPORT);
        }
    }

    private static boolean isLoopbackEndpoint(URI endpoint) {
        String host = endpoint.getHost();
        return "http".equals(endpoint.getScheme()) && endpoint.getUserInfo() == null
                && endpoint.getFragment() == null && endpoint.getQuery() == null
                && ("localhost".equals(host) || "127.0.0.1".equals(host)
                || "[::1]".equals(host) || "::1".equals(host));
    }
}
