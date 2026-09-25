package nettransfer.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import nettransfer.explanation.ExplanationClient;
import nettransfer.explanation.ExplanationDraft;
import nettransfer.explanation.ExplanationRequest;
import nettransfer.explanation.RecordedSummary;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Tool-free analysis HTTP adapter. Returned drafts still require ExplanationFlow's evidence checks. */
public final class ResponsesExplanationClient implements ExplanationClient {
    // The API counts reasoning and visible answer tokens against this same allowance.
    private static final int MAX_OUTPUT_TOKENS = 32768;
    private static final int MAX_DRAFT_CHARS = 32_768;
    private final GptSettings settings;
    private final ResponsesTransport transport;

    public ResponsesExplanationClient(String apiKey, GptSettings settings) {
        this(apiKey, settings, ResponsesTransport.ENDPOINT);
    }

    /** Optional safe API metadata for deliberate evaluations; never contains evidence or credentials. */
    public ResponsesExplanationClient(String apiKey, GptSettings settings,
                                      Consumer<ApiCallObservation> observer) {
        this(apiKey, settings, ResponsesTransport.ENDPOINT, observer);
    }

    /** Optional local evaluation evidence; credentials and HTTP headers are never captured. */
    public ResponsesExplanationClient(String apiKey, GptSettings settings, EvaluationCapture capture) {
        this(apiKey, settings, ResponsesTransport.ENDPOINT, observation -> { }, capture);
    }

    /** Package-private loopback seam for offline HTTP tests. */
    ResponsesExplanationClient(String apiKey, GptSettings settings, URI endpoint) {
        this(apiKey, settings, endpoint, observation -> { });
    }

    ResponsesExplanationClient(String apiKey, GptSettings settings, URI endpoint,
                               Consumer<ApiCallObservation> observer) {
        this(apiKey, settings, endpoint, observer, EvaluationCapture.disabled());
    }

    ResponsesExplanationClient(String apiKey, GptSettings settings, URI endpoint, EvaluationCapture capture) {
        this(apiKey, settings, endpoint, observation -> { }, capture);
    }

    ResponsesExplanationClient(String apiKey, GptSettings settings, URI endpoint,
                               Consumer<ApiCallObservation> observer, EvaluationCapture capture) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.transport = new ResponsesTransport(apiKey, settings, endpoint, observer, capture);
    }

    public static ResponsesExplanationClient fromEnvironment(Map<String, String> environment) {
        return fromEnvironment(environment, EvaluationCapture.disabled());
    }

    public static ResponsesExplanationClient fromEnvironment(Map<String, String> environment,
                                                            EvaluationCapture capture) {
        Objects.requireNonNull(environment, "environment");
        return new ResponsesExplanationClient(environment.get("OPENAI_API_KEY"),
                GptSettings.fromEnvironment(environment), capture);
    }

    @Override
    public ExplanationDraft explain(ExplanationRequest request) {
        Objects.requireNonNull(request, "request");
        return transport.post(payload(request), request.requestId(), this::decode);
    }

    private JsonObject payload(ExplanationRequest request) {
        JsonObject payload = new JsonObject();
        payload.addProperty("model", settings.model());
        payload.addProperty("instructions", ExplanationRequest.INSTRUCTIONS);
        payload.addProperty("store", false);
        payload.addProperty("max_output_tokens", MAX_OUTPUT_TOKENS);
        payload.add("tools", new JsonArray());
        payload.addProperty("tool_choice", "none");

        JsonObject format = new JsonObject();
        format.addProperty("type", "json_schema");
        format.addProperty("name", "transfer_explanation");
        format.addProperty("strict", true);
        format.add("schema", schema());
        JsonObject text = new JsonObject();
        text.add("format", format);
        payload.add("text", text);

        JsonObject data = new JsonObject();
        data.addProperty("question", request.question());
        data.addProperty("state", request.state().name());
        data.addProperty("integrity", request.integrity().name());
        data.add("evidence", evidence(request.evidence()));
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", data.toString());
        JsonArray input = new JsonArray();
        input.add(message);
        payload.add("input", input);

        JsonObject metadata = new JsonObject();
        metadata.addProperty("request_id", request.requestId().toString());
        metadata.addProperty("run_id", request.evidence().runId().toString());
        metadata.addProperty("transfer_id", request.evidence().transferId().toString());
        metadata.addProperty("prompt_schema_version", ExplanationRequest.PROMPT_VERSION);
        payload.add("metadata", metadata);
        return payload;
    }

    /** Explicit projection: never serialize a service, outcome, path or command context. */
    private static JsonObject evidence(RecordedSummary summary) {
        JsonObject result = new JsonObject();
        result.addProperty("run_id", summary.runId().toString());
        result.addProperty("transfer_id", summary.transferId().toString());
        result.addProperty("protocol_transfer_id", summary.protocolTransferId() == null
                ? null : summary.protocolTransferId().toString());
        result.addProperty("evidence_source", summary.source().name());
        result.addProperty("captured_at", summary.capturedAt().toString());
        result.addProperty("definition_version", summary.definitionVersion());
        result.addProperty("label", summary.label());
        result.add("evidence_metadata", evidenceMetadata(summary.metadata()));
        JsonArray fields = new JsonArray();
        for (RecordedSummary.Field field : summary.fields()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", field.id());
            item.addProperty("value", field.value());
            item.addProperty("unit", field.unit());
            item.addProperty("kind", field.kind().name());
            item.addProperty("definition", field.definition());
            item.addProperty("unavailable_reason", field.unavailableReason());
            fields.add(item);
        }
        result.add("fields", fields);
        return result;
    }

    private static JsonObject evidenceMetadata(RecordedSummary.EvidenceMetadata metadata) {
        JsonObject result = new JsonObject();
        result.addProperty("scope", metadata.scope().name());
        result.addProperty("completeness", metadata.completeness().name());
        result.addProperty("finalization_status", metadata.finalizationStatus().name());
        result.addProperty("application_transfer_id", metadata.applicationTransferId());
        result.addProperty("sender_run_id", metadata.senderRunId());
        result.addProperty("receiver_run_id", metadata.receiverRunId());
        result.addProperty("protocol_transfer_id", metadata.protocolTransferId() == null
                ? null : metadata.protocolTransferId().toString());
        result.addProperty("metrics_schema_version", metadata.metricsSchemaVersion());
        result.addProperty("metric_definition_version", metadata.metricDefinitionVersion());
        result.addProperty("sender_terminal_outcome", metadata.senderTerminalOutcome());
        result.addProperty("receiver_integrity_verified", metadata.receiverIntegrityVerified());
        result.addProperty("failure_category", metadata.failureCategory());
        result.addProperty("failure_reason", metadata.failureReason());
        JsonArray references = new JsonArray();
        metadata.sourceReferences().forEach(references::add);
        result.add("source_references", references);
        if (metadata.impairment() != null) {
            var supplied = metadata.impairment();
            JsonObject impairment = new JsonObject();
            impairment.addProperty("mechanism", supplied.mechanism());
            impairment.addProperty("scenario", supplied.scenario());
            impairment.addProperty("seed", supplied.seed());
            impairment.addProperty("affected_traffic", supplied.affectedTraffic());
            impairment.addProperty("receiver_drop_observations_available", supplied.receiverDropObservationsAvailable());
            result.add("impairment", impairment);
        }
        return result;
    }

    private static JsonObject schema() {
        JsonObject reference = objectSchema("field_id", type("string"), "value", type("number"),
                "unit", type("string"));
        JsonObject observation = objectSchema("text", proseSchema(), "references", arraySchema(reference, 1, 8));
        return objectSchema("run_id", type("string"), "transfer_id", type("string"),
                "observations", arraySchema(observation, 0, 8), "hypotheses", arraySchema(proseSchema(), 0, 4),
                "limitations", arraySchema(proseSchema(), 1, 8));
    }

    private static JsonObject proseSchema() {
        JsonObject schema = type("string");
        // Constrain generation as well as validating afterward. Java remains authoritative
        // for blank/control-character checks and its UTF-16 length definition.
        schema.addProperty("pattern", "^[\\s\\S]{1,600}$");
        return schema;
    }

    private static JsonObject type(String name) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", name);
        return schema;
    }

    private static JsonObject arraySchema(JsonObject items, int minimum, int maximum) {
        JsonObject schema = type("array");
        schema.add("items", items);
        schema.addProperty("minItems", minimum);
        schema.addProperty("maxItems", maximum);
        return schema;
    }

    private static JsonObject objectSchema(Object... pairs) {
        JsonObject schema = type("object");
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (int i = 0; i < pairs.length; i += 2) {
            String name = (String) pairs[i];
            properties.add(name, (JsonObject) pairs[i + 1]);
            required.add(name);
        }
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    private ExplanationDraft decode(String body) {
        JsonObject response = ResponsesJson.parse(body);
        transport.rejectResponseSecret(response);
        switch (ResponsesJson.string(response, "status")) {
            case "completed" -> { }
            case "incomplete", "queued", "in_progress", "cancelled" ->
                    throw new GptException(GptException.Code.INCOMPLETE);
            case "failed" -> throw new GptException(GptException.Code.UNAVAILABLE);
            default -> throw ResponsesJson.invalid();
        }
        if (nonNull(response, "error") || nonNull(response, "incomplete_details")) {
            throw ResponsesJson.invalid();
        }
        JsonArray output = array(response, "output", 1, 64);
        String draft = null;
        for (JsonElement element : output) {
            JsonObject item = object(element);
            String itemType = ResponsesJson.string(item, "type");
            if (itemType.equals("reasoning")) {
                continue;
            }
            // A function/tool call is always invalid here, even beside valid explanation text.
            if (!itemType.equals("message") || draft != null
                    || !ResponsesJson.string(item, "role").equals("assistant")) {
                throw ResponsesJson.invalid();
            }
            if (!ResponsesJson.string(item, "status").equals("completed")) {
                throw new GptException(GptException.Code.INCOMPLETE);
            }
            JsonObject content = object(array(item, "content", 1, 1).get(0));
            String contentType = ResponsesJson.string(content, "type");
            if (contentType.equals("refusal")) {
                throw new GptException(GptException.Code.REFUSED);
            }
            if (!contentType.equals("output_text")) {
                throw ResponsesJson.invalid();
            }
            draft = ResponsesJson.string(content, "text");
        }
        if (draft == null || draft.length() > MAX_DRAFT_CHARS) {
            throw ResponsesJson.invalid();
        }
        return decodeDraft(draft);
    }

    private ExplanationDraft decodeDraft(String text) {
        JsonObject draft = ResponsesJson.parse(text);
        transport.rejectResponseSecret(draft);
        exactKeys(draft, "run_id", "transfer_id", "observations", "hypotheses", "limitations");
        try {
            List<ExplanationDraft.Observation> observations = new ArrayList<>();
            for (JsonElement element : array(draft, "observations", 0, 8)) {
                JsonObject observation = object(element);
                exactKeys(observation, "text", "references");
                List<ExplanationDraft.Reference> references = new ArrayList<>();
                for (JsonElement cited : array(observation, "references", 1, 8)) {
                    JsonObject reference = object(cited);
                    exactKeys(reference, "field_id", "value", "unit");
                    JsonElement value = reference.get("value");
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                        throw ResponsesJson.invalid();
                    }
                    references.add(new ExplanationDraft.Reference(ResponsesJson.string(reference, "field_id"),
                            new BigDecimal(value.getAsString()), ResponsesJson.string(reference, "unit")));
                }
                observations.add(new ExplanationDraft.Observation(ResponsesJson.string(observation, "text"),
                        references));
            }
            return new ExplanationDraft(uuid(draft, "run_id"), uuid(draft, "transfer_id"), observations,
                    strings(draft, "hypotheses", 0, 4), strings(draft, "limitations", 1, 8));
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException exception) {
            // Discard raw provider content and constructor diagnostics at this boundary.
            throw ResponsesJson.invalid();
        }
    }

    private static UUID uuid(JsonObject object, String key) {
        String text = ResponsesJson.string(object, key);
        UUID value = UUID.fromString(text);
        if (!value.toString().equals(text)) {
            throw ResponsesJson.invalid();
        }
        return value;
    }

    private static List<String> strings(JsonObject object, String key, int minimum, int maximum) {
        List<String> result = new ArrayList<>();
        for (JsonElement item : array(object, key, minimum, maximum)) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw ResponsesJson.invalid();
            }
            result.add(item.getAsString());
        }
        return result;
    }

    private static JsonObject object(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw ResponsesJson.invalid();
        }
        return element.getAsJsonObject();
    }

    private static JsonArray array(JsonObject object, String key, int minimum, int maximum) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonArray()) {
            throw ResponsesJson.invalid();
        }
        JsonArray result = element.getAsJsonArray();
        if (result.size() < minimum || result.size() > maximum) {
            throw ResponsesJson.invalid();
        }
        return result;
    }

    private static void exactKeys(JsonObject object, String... keys) {
        if (!object.keySet().equals(Set.of(keys))) {
            throw ResponsesJson.invalid();
        }
    }

    private static boolean nonNull(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull();
    }
}
