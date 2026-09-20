package nettransfer.control.command;

import nettransfer.control.TransferError;
import nettransfer.control.TransferServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;
import java.util.stream.Stream;

import static nettransfer.control.TransferError.Code.INVALID_COMMAND;
import static nettransfer.control.TransferError.Code.INVALID_PARAMETER;
import static nettransfer.control.TransferError.Code.UNSUPPORTED_REQUEST;
import static org.junit.jupiter.api.Assertions.*;

class CommandParserTest {
    private final CommandParser parser = new CommandParser();
    private static final UUID ID = UUID.fromString("abcdefab-1234-5678-90ab-abcdef123456");

    @Test
    void parsesAllCommandsWithoutApplyingDefaultsOrInventingIds() {
        assertEquals(new TransferCommand.Start("report", "receiver-a", null, null),
                parse("start_transfer", startArguments("null", "null")));
        assertEquals(new TransferCommand.Start("report", "receiver-a", 65536L, 80L),
                parse("start_transfer", startArguments("65536", "80")));
        assertEquals(new TransferCommand.Status(null), parse("status", "{\"transfer_id\":null}"));
        assertEquals(new TransferCommand.Status(ID),
                parse("status", "{\"transfer_id\":\"" + ID + "\"}"));
        assertEquals(new TransferCommand.Explain(null, "Why was it slow?"),
                parse("explain", "{\"run_id\":null,\"question\":\"Why was it slow?\"}"));
        assertEquals(new TransferCommand.Explain(ID, "Show the evidence"),
                parse("explain", "{\"run_id\":\"" + ID + "\",\"question\":\"Show the evidence\"}"));
    }

    @Test
    void preservesBlankSelectionsForValidatorClarificationAndAcceptsFieldReordering() {
        assertEquals(new TransferCommand.Start("", " ", null, 200L), parse("start_transfer", """
                {"timeout_ms":200, "receiver_id":" ", "window_bytes":null, "file_id":""}
                """));
    }

    @Test
    void parsesIntegerLimitsExactlyLeavingPolicyBoundsToValidator() {
        TransferCommand.Start start = (TransferCommand.Start) parse("start_transfer",
                startArguments(Long.toString(Long.MAX_VALUE), Long.toString(Long.MIN_VALUE)));
        assertEquals(Long.MAX_VALUE, start.windowBytes());
        assertEquals(Long.MIN_VALUE, start.timeoutMillis());
    }

    @Test
    void acceptsSurroundingJsonWhitespaceAndNormalizesUuidLetterCase() {
        assertEquals(new TransferCommand.Status(ID),
                parse("status", " \n\t{\"transfer_id\":\"ABCDEFAB-1234-5678-90AB-ABCDEF123456\"} \r\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1024.0", "1024.5", "1e3", "1E+3", "9223372036854775808",
            "-9223372036854775809", "\"1024\"", "true", "false", "[]", "{}",
            "NaN", "Infinity", "+1024", "01024"})
    void rejectsNonIntegerNumericArgumentsWithoutCoercion(String literal) {
        assertRejected(INVALID_COMMAND, "start_transfer", startArguments(literal, "200"));
        assertRejected(INVALID_COMMAND, "start_transfer", startArguments("1024", literal));
    }

    @ParameterizedTest
    @MethodSource("invalidSchemas")
    void rejectsMissingUnexpectedDuplicateAndWronglyTypedFields(String name, String json) {
        assertRejected(INVALID_COMMAND, name, json);
    }

    private static Stream<Arguments> invalidSchemas() {
        return Stream.of(
                Arguments.of("start_transfer", "{}"),
                Arguments.of("start_transfer", "{\"receiver_id\":\"receiver-a\",\"window_bytes\":null,\"timeout_ms\":null}"),
                Arguments.of("start_transfer", "{\"file_id\":\"report\",\"window_bytes\":null,\"timeout_ms\":null}"),
                Arguments.of("start_transfer", "{\"file_id\":\"report\",\"receiver_id\":\"receiver-a\",\"timeout_ms\":null}"),
                Arguments.of("start_transfer", "{\"file_id\":\"report\",\"receiver_id\":\"receiver-a\",\"window_bytes\":null}"),
                Arguments.of("start_transfer", "{\"file_id\":null,\"receiver_id\":\"receiver-a\",\"window_bytes\":null,\"timeout_ms\":null}"),
                Arguments.of("start_transfer", "{\"file_id\":\"report\",\"receiver_id\":null,\"window_bytes\":null,\"timeout_ms\":null}"),
                Arguments.of("start_transfer", "{\"file_id\":1,\"receiver_id\":\"receiver-a\",\"window_bytes\":null,\"timeout_ms\":null}"),
                Arguments.of("start_transfer", "{\"file_id\":\"report\",\"receiver_id\":{},\"window_bytes\":null,\"timeout_ms\":null}"),
                Arguments.of("start_transfer", "{\"file_id\":\"report\",\"receiver_id\":\"receiver-a\",\"window_bytes\":null,\"timeout_ms\":null,\"rate\":100}"),
                Arguments.of("start_transfer", "{\"file_id\":\"report\",\"receiver_id\":\"receiver-a\",\"window_bytes\":null,\"timeout_ms\":null,\"source_path\":\"outside.txt\"}"),
                Arguments.of("start_transfer", "{\"file_id\":\"report\",\"receiver_id\":\"receiver-a\",\"window_bytes\":null,\"timeout_ms\":null,\"window_bytes\":1024}"),
                Arguments.of("start_transfer", "{\"file_id\":\"report\",\"receiver_id\":\"receiver-a\",\"window_bytes\":null,\"timeout_ms\":null,\"\\u0066ile_id\":\"report\"}"),
                Arguments.of("status", "{}"),
                Arguments.of("status", "{\"transfer_id\":false}"),
                Arguments.of("status", "{\"transfer_id\":[]}"),
                Arguments.of("status", "{\"transfer_id\":null,\"transfer_id\":null}"),
                Arguments.of("status", "{\"transfer_id\":null,\"run_id\":null}"),
                Arguments.of("explain", "{\"question\":\"Why?\"}"),
                Arguments.of("explain", "{\"run_id\":null}"),
                Arguments.of("explain", "{\"run_id\":12,\"question\":\"Why?\"}"),
                Arguments.of("explain", "{\"run_id\":null,\"question\":null}"),
                Arguments.of("explain", "{\"run_id\":null,\"question\":true}"),
                Arguments.of("explain", "{\"run_id\":null,\"question\":{\"text\":\"Why?\"}}"),
                Arguments.of("explain", "{\"run_id\":null,\"question\":\"Why?\",\"question\":\"Why?\"}")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "42", "\"text\"", "{", "{\"transfer_id\":}",
            "{'transfer_id':null}", "{transfer_id:null}", "{\"transfer_id\":null,}",
            "{\"transfer_id\":null} {}", "{\"transfer_id\":null} garbage",
            "/* comment */ {\"transfer_id\":null}", "{\"transfer_id\":null}//comment"})
    void rejectsMalformedNonObjectLenientOrTrailingJson(String json) {
        assertRejected(INVALID_COMMAND, "status", json);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1-1-1-1-1", "not-a-uuid", " abcdefab-1234-5678-90ab-abcdef123456",
            "abcdefab1234567890ababcdef123456", "abcdefab-1234-5678-90ab-abcdef123456 "})
    void rejectsIncompleteOrInvalidUuidStrings(String id) {
        assertRejected(INVALID_PARAMETER, "status", "{\"transfer_id\":\"" + id + "\"}");
        assertRejected(INVALID_PARAMETER, "explain", "{\"run_id\":\"" + id + "\",\"question\":\"Why?\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pause", "resume", "cancel", "set_rate", "shell", "START_TRANSFER", "start_transfer "})
    void unsupportedToolsCannotBecomeCommands(String toolName) {
        assertRejected(UNSUPPORTED_REQUEST, toolName, "{}");
    }

    @Test
    void rejectsOversizedArgumentsBeforeParsing() {
        String base = "{\"transfer_id\":null}";
        String atLimit = base + " ".repeat(CommandParser.MAX_ARGUMENT_CHARACTERS - base.length());
        assertEquals(new TransferCommand.Status(null), parse("status", atLimit));
        assertRejected(INVALID_COMMAND, "status", atLimit + " ");
    }

    private TransferCommand parse(String name, String json) {
        return parser.parse(new CommandProposal.ToolCall(name, json));
    }

    private void assertRejected(TransferError.Code expected, String name, String json) {
        TransferServiceException error = assertThrows(TransferServiceException.class, () -> parse(name, json));
        assertEquals(expected, error.error().code());
        assertFalse(error.error().message().isBlank());
    }

    private static String startArguments(String window, String timeout) {
        return "{\"file_id\":\"report\",\"receiver_id\":\"receiver-a\",\"window_bytes\":"
                + window + ",\"timeout_ms\":" + timeout + "}";
    }
}
