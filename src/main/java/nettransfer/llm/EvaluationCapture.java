package nettransfer.llm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Local opt-in evidence, separate from transfer measurements and API acceptance decisions. */
public final class EvaluationCapture implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping()
            .registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>)
                    (value, type, context) -> new JsonPrimitive(value.toString())).create();
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");
    private static final Pattern KEY_LIKE = Pattern.compile("(?i)\\bsk-[a-z0-9_-]{8,}");
    private static final String OMITTED = "[omitted: credential-like content]";
    private static final String FAILURE_WARNING = "Evaluation recording failed; evidence is incomplete. "
            + "Stop paid evaluation and inspect the capture folder. Transfer and API decisions are unchanged.";

    private final Path directory;
    private final String secret;
    private final Consumer<String> warning;
    private final Map<UUID, Invocation> invocations = new HashMap<>();
    private final Map<UUID, UUID> latest = new HashMap<>();
    private boolean failed;
    private boolean closed;
    private int omittedBodies;
    private long eventSequence;

    private EvaluationCapture(Path directory, String apiKey, Consumer<String> warning) {
        this.directory = directory;
        this.secret = apiKey == null ? "" : apiKey.strip();
        this.warning = warning;
    }

    public static EvaluationCapture disabled() {
        return new EvaluationCapture(null, "", ignored -> { });
    }

    /** A Java startup option chooses this location; model output never chooses files or paths. */
    public static EvaluationCapture open(Path projectRoot, String apiKey, Consumer<String> warning)
            throws IOException {
        java.util.Objects.requireNonNull(warning, "warning");
        Path base = projectRoot.toAbsolutePath().normalize().resolve("target/evaluation");
        Files.createDirectories(base);
        Path directory = Files.createTempDirectory(base, "llm-" + Instant.now().toString().replace(':', '-') + "-");
        EvaluationCapture capture = new EvaluationCapture(directory, apiKey, warning);
        JsonObject session = new JsonObject();
        session.addProperty("capture_version", "llm-evidence-v1");
        session.addProperty("started_at", Instant.now().toString());
        session.addProperty("notice", "All saved model responses are UNTRUSTED. Read the associated Java decision "
                + "and manually review prose against the exact supplied request. Recording is not acceptance.");
        session.addProperty("scope", "Exact safe UTF-8 request/response bodies, HTTP attempt metadata and Java decisions. "
                + "No authorization headers or environment dump. Missing usage is unknown, not zero. "
                + "Timeout, interrupted, oversized or invalid UTF-8 bodies may be unavailable. "
                + "Credential-bearing bodies are omitted with an explicit reason.");
        session.addProperty("completion", "A missing session-end.json means the session did not close normally; "
                + "do not assume capture is complete.");
        capture.writeJson(directory.resolve("session.json"), session);
        Files.writeString(directory.resolve("events.jsonl"), "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return capture;
    }

    public Path directory() {
        return directory;
    }

    /** Clarification intentionally reuses request IDs; a preflight failure must not inherit its old call. */
    public synchronized void beginInvocation(UUID requestId) {
        if (active()) latest.remove(requestId);
    }

    public synchronized UUID request(UUID requestId, String exactSerializedBody) {
        if (!active()) return null;
        UUID captureId = UUID.randomUUID();
        try {
            Path invocationDirectory = Files.createDirectory(directory.resolve(captureId.toString()));
            Invocation invocation = new Invocation(requestId);
            invocations.put(captureId, invocation);
            latest.put(requestId, captureId);
            JsonObject event = event("request", captureId, requestId);
            if (containsCredential(exactSerializedBody)) {
                omittedBodies++;
                event.addProperty("body_status", "OMITTED_CREDENTIAL");
            } else {
                writeBody(invocationDirectory.resolve("request.json"), exactSerializedBody);
                event.addProperty("body_status", "EXACT");
                event.addProperty("artifact", captureId + "/request.json");
            }
            append(event);
        } catch (IOException | RuntimeException exception) {
            fail();
        }
        return captureId;
    }

    /** Called on a complete, bounded HTTP body before response parsing or reference validation. */
    public synchronized void response(UUID captureId, int attempt, int httpStatus, byte[] body) {
        if (!active() || captureId == null) return;
        try {
            Invocation invocation = invocations.get(captureId);
            if (invocation == null) throw new IllegalStateException("Unknown capture");
            JsonObject event = event("response", captureId, invocation.requestId);
            event.addProperty("attempt", attempt);
            event.addProperty("http_status", httpStatus);
            event.addProperty("trusted", false);
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            } catch (java.nio.charset.CharacterCodingException exception) {
                omittedBodies++;
                event.addProperty("body_status", "OMITTED_INVALID_UTF8");
                invocation.responses.add(attempt);
                append(event);
                return;
            }
            if (containsCredential(text)) {
                omittedBodies++;
                event.addProperty("body_status", "OMITTED_CREDENTIAL");
            } else {
                String filename = "attempt-" + attempt + "-response.txt";
                writeBody(directory.resolve(captureId.toString()).resolve(filename), text);
                event.addProperty("body_status", "EXACT_UNTRUSTED");
                event.addProperty("artifact", captureId + "/" + filename);
            }
            invocation.responses.add(attempt);
            append(event);
        } catch (IOException | RuntimeException exception) {
            fail();
        }
    }

    public synchronized void attempt(UUID captureId, ApiCallObservation observation) {
        if (!active() || captureId == null) return;
        try {
            Invocation invocation = invocations.get(captureId);
            if (invocation == null) throw new IllegalStateException("Unknown capture");
            JsonObject event = event("attempt", captureId, observation.requestId());
            event.addProperty("attempt", observation.attempt());
            event.add("metadata", JSON.toJsonTree(observation));
            event.addProperty("response_body", invocation.responses.contains(observation.attempt())
                    ? "SEE_RESPONSE_EVENT" : "UNAVAILABLE");
            append(event);
        } catch (IOException | RuntimeException exception) {
            fail();
        }
    }

    public synchronized void decision(UUID requestId, String stage, String status, UUID runId,
                                      UUID transferId, String detail) {
        if (!active()) return;
        try {
            JsonObject event = event("java_decision", latest.get(requestId), requestId);
            event.addProperty("stage", stage);
            event.addProperty("status", status);
            event.addProperty("run_id", runId == null ? null : runId.toString());
            event.addProperty("transfer_id", transferId == null ? null : transferId.toString());
            event.addProperty("detail", detail);
            append(event);
        } catch (IOException | RuntimeException exception) {
            fail();
        }
    }

    @Override
    public synchronized void close() {
        if (directory == null || closed) return;
        closed = true;
        JsonObject end = new JsonObject();
        end.addProperty("finished_at", Instant.now().toString());
        end.addProperty("recording_status", failed ? "INCOMPLETE" : omittedBodies > 0
                ? "COMPLETE_WITH_BODY_OMISSIONS" : "COMPLETE");
        end.addProperty("request_count", invocations.size());
        end.addProperty("omitted_body_count", omittedBodies);
        end.addProperty("notice", "This status describes local recording only, not transfer success, "
                + "model correctness or availability of timed-out response bodies. Check each attempt and Java decision.");
        try {
            writeJson(directory.resolve("session-end.json"), end);
        } catch (IOException | RuntimeException exception) {
            fail();
        }
    }

    private boolean active() {
        return directory != null && !failed && !closed;
    }

    private JsonObject event(String type, UUID captureId, UUID requestId) {
        JsonObject event = new JsonObject();
        event.addProperty("sequence", ++eventSequence);
        event.addProperty("recorded_at", Instant.now().toString());
        event.addProperty("event", type);
        event.addProperty("capture_id", captureId == null ? null : captureId.toString());
        event.addProperty("request_id", requestId == null ? null : requestId.toString());
        return event;
    }

    private void append(JsonObject event) throws IOException {
        Files.writeString(directory.resolve("events.jsonl"), JSON.toJson(sanitize(event)) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }

    private void writeJson(Path path, JsonObject value) throws IOException {
        Files.writeString(path, JSON.toJson(sanitize(value)) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /** A failed write leaves an explicitly partial artifact, never a seemingly exact completed body. */
    private void writeBody(Path path, String text) throws IOException {
        Path partial = path.resolveSibling(path.getFileName() + ".partial");
        Files.writeString(partial, text, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        // Both names are inside a newly created, private invocation directory. Never replace a file.
        Files.move(partial, path);
    }

    /** Screen every string, including decision text and metadata; never serialize headers or exceptions. */
    private JsonElement sanitize(JsonElement value) {
        if (value == null || value.isJsonNull()) return value;
        if (value.isJsonPrimitive()) {
            return value.getAsJsonPrimitive().isString() && containsCredential(value.getAsString())
                    ? new JsonPrimitive(OMITTED) : value;
        }
        if (value.isJsonArray()) {
            JsonArray safe = new JsonArray();
            for (JsonElement child : value.getAsJsonArray()) safe.add(sanitize(child));
            return safe;
        }
        JsonObject safe = new JsonObject();
        for (var entry : value.getAsJsonObject().entrySet()) {
            safe.add(containsCredential(entry.getKey()) ? OMITTED : entry.getKey(), sanitize(entry.getValue()));
        }
        return safe;
    }

    /** Conservative normalization also catches escaped credentials in malformed/nested JSON strings. */
    private boolean containsCredential(String original) {
        String text = original;
        for (int depth = 0; depth < 128; depth++) {
            if ((!secret.isEmpty() && text.contains(secret)) || KEY_LIKE.matcher(text).find()) return true;
            var matcher = UNICODE_ESCAPE.matcher(text);
            String decoded = matcher.replaceAll(match -> java.util.regex.Matcher.quoteReplacement(
                    String.valueOf((char) Integer.parseInt(match.group(1), 16))));
            decoded = decoded.replace("\\\\", "\\").replace("\\/", "/")
                    .replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
                    .replace("\\b", "\b").replace("\\f", "\f");
            if (decoded.equals(text)) return false;
            text = decoded;
        }
        // Excessive escaping cannot be confidently screened, so omit it conservatively.
        return true;
    }

    private void fail() {
        if (failed) return;
        failed = true;
        try {
            warning.accept(FAILURE_WARNING);
        } catch (RuntimeException ignored) {
            // Diagnostics must never change dispatch, transfer results or HTTP retry behavior.
        }
    }

    private static final class Invocation {
        final UUID requestId;
        final Set<Integer> responses = new HashSet<>();
        Invocation(UUID requestId) { this.requestId = requestId; }
    }
}
