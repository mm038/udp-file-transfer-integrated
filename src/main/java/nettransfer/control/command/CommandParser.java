package nettransfer.control.command;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import nettransfer.control.TransferServiceException;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static nettransfer.control.TransferError.Code.INVALID_COMMAND;
import static nettransfer.control.TransferError.Code.INVALID_PARAMETER;
import static nettransfer.control.TransferError.Code.UNSUPPORTED_REQUEST;

/**
 * Strict, flat command parsing without Gson's value coercion or duplicate-key loss.
 * Numeric arguments are integer literals in bytes/milliseconds; defaults, bounds
 * and approved resources are checked separately by the Java validator.
 */
public final class CommandParser {
    /** Bounds untrusted argument text before parsing; counts Java characters. */
    public static final int MAX_ARGUMENT_CHARACTERS = 16_384;
    private static final Pattern INTEGER = Pattern.compile("-?(0|[1-9][0-9]*)");

    public TransferCommand parse(CommandProposal.ToolCall call) {
        Objects.requireNonNull(call, "call");
        List<String> fields = switch (call.name()) {
            case "start_transfer" -> List.of("file_id", "receiver_id", "window_bytes", "timeout_ms");
            case "status" -> List.of("transfer_id");
            case "explain" -> List.of("run_id", "question");
            default -> throw new TransferServiceException(UNSUPPORTED_REQUEST,
                    "Unsupported command: " + call.name());
        };
        if (call.argumentsJson().length() > MAX_ARGUMENT_CHARACTERS) {
            throw invalid("Command arguments exceed " + MAX_ARGUMENT_CHARACTERS + " characters");
        }

        Map<String, Object> values = new HashMap<>();
        Set<String> seen = new HashSet<>();
        try (JsonReader reader = new JsonReader(new StringReader(call.argumentsJson()))) {
            reader.setStrictness(Strictness.STRICT);
            requireToken(reader, JsonToken.BEGIN_OBJECT, "Command arguments must be a JSON object");
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!fields.contains(name)) {
                    throw invalid("Unexpected argument: " + name);
                }
                if (!seen.add(name)) {
                    throw invalid("Duplicate argument: " + name);
                }
                Object value = switch (name) {
                    case "window_bytes", "timeout_ms" -> nullableInteger(reader, name);
                    case "transfer_id", "run_id" -> nullableUuid(reader, name);
                    default -> string(reader, name);
                };
                values.put(name, value);
            }
            reader.endObject();
            requireToken(reader, JsonToken.END_DOCUMENT, "Unexpected content after command arguments");
            for (String field : fields) {
                if (!seen.contains(field)) {
                    throw invalid("Missing required argument: " + field);
                }
            }
        } catch (IOException | IllegalStateException malformed) {
            throw invalid("Command arguments must contain one strict JSON object");
        }

        return switch (call.name()) {
            case "start_transfer" -> new TransferCommand.Start((String) values.get("file_id"),
                    (String) values.get("receiver_id"), (Long) values.get("window_bytes"),
                    (Long) values.get("timeout_ms"));
            case "status" -> new TransferCommand.Status((UUID) values.get("transfer_id"));
            case "explain" -> new TransferCommand.Explain((UUID) values.get("run_id"),
                    (String) values.get("question"));
            default -> throw new AssertionError("Tool name was already checked");
        };
    }

    private static String string(JsonReader reader, String name) throws IOException {
        requireToken(reader, JsonToken.STRING, name + " must be a JSON string");
        return reader.nextString();
    }

    private static Long nullableInteger(JsonReader reader, String name) throws IOException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        requireToken(reader, JsonToken.NUMBER, name + " must be an integer or null");
        String literal = reader.nextString();
        if (!INTEGER.matcher(literal).matches()) {
            throw invalid(name + " must be an integer literal without a fraction or exponent");
        }
        try {
            return Long.parseLong(literal);
        } catch (NumberFormatException overflow) {
            throw invalid(name + " exceeds the supported integer range");
        }
    }

    private static UUID nullableUuid(JsonReader reader, String name) throws IOException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        String supplied = string(reader, name);
        try {
            UUID parsed = UUID.fromString(supplied);
            // UUID.fromString also accepts shortened groups; require all 36 characters.
            if (!parsed.toString().equalsIgnoreCase(supplied)) {
                throw new IllegalArgumentException("Noncanonical UUID");
            }
            return parsed;
        } catch (IllegalArgumentException malformed) {
            throw new TransferServiceException(INVALID_PARAMETER,
                    name + " must be a complete UUID string or null");
        }
    }

    private static void requireToken(JsonReader reader, JsonToken expected, String message)
            throws IOException {
        if (reader.peek() != expected) {
            throw invalid(message);
        }
    }

    private static TransferServiceException invalid(String message) {
        return new TransferServiceException(INVALID_COMMAND, message);
    }
}
