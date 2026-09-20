package nettransfer.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;

/** Gson's tree parser silently replaces duplicate keys; reject them at this boundary. */
final class ResponsesJson {
    private ResponsesJson() { }

    static JsonObject parse(String json) {
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = read(reader, 0);
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) {
                throw invalid();
            }
            return value.getAsJsonObject();
        } catch (IOException | IllegalArgumentException | IllegalStateException exception) {
            throw invalid();
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 64) {
            throw invalid();
        }
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject result = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (result.has(name)) {
                        throw invalid();
                    }
                    result.add(name, read(reader, depth + 1));
                }
                reader.endObject();
                yield result;
            }
            case BEGIN_ARRAY -> {
                JsonArray result = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    result.add(read(reader, depth + 1));
                }
                reader.endArray();
                yield result;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw invalid();
        };
    }

    static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw invalid();
        }
        return element.getAsString();
    }

    static boolean containsSecret(JsonElement element, String secret) {
        if (element.isJsonPrimitive()) {
            return element.getAsJsonPrimitive().isString() && element.getAsString().contains(secret);
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (containsSecret(child, secret)) {
                    return true;
                }
            }
        } else if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                if (entry.getKey().contains(secret) || containsSecret(entry.getValue(), secret)) {
                    return true;
                }
            }
        }
        return false;
    }

    static GptException invalid() {
        return new GptException(GptException.Code.INVALID_RESPONSE);
    }
}
