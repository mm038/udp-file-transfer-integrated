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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Shared bounded HTTP transport; each client supplies and validates its own distinct request/response. */
final class ResponsesTransport {
    static final URI ENDPOINT = URI.create("https://api.openai.com/v1/responses");
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    private final String apiKey;
    private final GptSettings settings;
    private final URI endpoint;
    private final HttpClient http;

    ResponsesTransport(String apiKey, GptSettings settings, URI endpoint) {
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.settings = Objects.requireNonNull(settings, "settings");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        if (!ENDPOINT.equals(endpoint) && !isLoopbackEndpoint(endpoint)) {
            throw new GptException(GptException.Code.INVALID_CONFIGURATION);
        }
        http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    String post(JsonObject payload, UUID requestId) {
        if (apiKey.isBlank()) {
            throw new GptException(GptException.Code.MISSING_CREDENTIALS);
        }
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(requestId, "requestId");
        // No key is copied into model input, even if a user accidentally pastes it into their request.
        if (ResponsesJson.containsSecret(payload, apiKey)) {
            throw new GptException(GptException.Code.INVALID_CONFIGURATION);
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(endpoint).timeout(settings.requestTimeout())
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("X-Client-Request-Id", requestId.toString())
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(payload), StandardCharsets.UTF_8)).build();
        } catch (IllegalArgumentException exception) {
            throw new GptException(GptException.Code.INVALID_CONFIGURATION);
        }

        for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
            HttpResponse<byte[]> response;
            try {
                response = send(request);
            } catch (GptException exception) {
                if (Thread.currentThread().isInterrupted() || attempt == settings.maxAttempts()
                        || (exception.code() != GptException.Code.TRANSPORT
                        && exception.code() != GptException.Code.TIMEOUT)) {
                    throw exception;
                }
                pause(attempt);
                continue;
            }
            int status = response.statusCode();
            if (status == 200) {
                return decodeUtf8(response.body());
            }
            if (retryable(status) && attempt < settings.maxAttempts()) {
                pause(attempt);
                continue;
            }
            throw statusFailure(status);
        }
        throw new GptException(GptException.Code.UNAVAILABLE);
    }

    /** Inspect already parsed outer or nested JSON, so escaped credentials cannot reach error output. */
    void rejectResponseSecret(JsonElement value) {
        if (ResponsesJson.containsSecret(value, apiKey)) {
            throw ResponsesJson.invalid();
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request,
                responseInfo -> new ResponsesBodySubscriber());
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
