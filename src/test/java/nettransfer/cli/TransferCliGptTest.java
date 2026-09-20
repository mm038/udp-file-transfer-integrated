package nettransfer.cli;

import nettransfer.control.*;
import nettransfer.control.command.CommandProposal;
import nettransfer.control.command.TransferConfiguration;
import nettransfer.control.simulation.FakeTransferService;
import nettransfer.llm.GptClient;
import nettransfer.llm.GptException;
import nettransfer.llm.InterpretationRequest;
import nettransfer.llm.StubGptClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TransferCliGptTest {
    private static final String START_ARGUMENTS = """
            {"file_id":"report","receiver_id":"receiver-a","window_bytes":2500,"timeout_ms":350}
            """;
    private static final CommandProposal START = call("start_transfer", START_ARGUMENTS);
    private static final CommandProposal STATUS = call("status", "{\"transfer_id\":null}");
    private static final TransferMetrics UNKNOWN = TransferMetrics.unavailable("Synthetic fixture");

    @TempDir Path root;
    private TransferConfiguration configuration;
    private CountingService service;
    private StringWriter output;
    private TransferCli cli;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("data/input"));
        Files.writeString(root.resolve("data/input/report.txt"), "PRIVATE FILE CONTENT NOT FOR GPT");
        configuration = TransferConfiguration.localhost(root,
                Map.of("report", Path.of("data/input/report.txt")));
        service = new CountingService();
        output = new StringWriter();
    }

    @Test
    void stubStartCrossesTheExistingValidatorAndReportsOnlyJavaResults() throws Exception {
        StubGptClient stub = useStub(START);

        String displayed = command("Send report to receiver-a with a 2500 byte window and 350 ms timeout");

        assertEquals(1, service.starts);
        assertEquals(2, service.lastRequest.settings().windowPackets());
        assertEquals(350, service.lastRequest.settings().timeoutMillis());
        assertEquals(root.resolve("data/input/report.txt").toRealPath(), service.lastRequest.sourcePath());
        assertEquals(stub.requests().get(0).requestId(), service.lastRequest.requestId());
        assertTrue(displayed.contains("Start accepted [SYNTHETIC]"), displayed);
        InterpretationRequest request = stub.requests().get(0);
        assertEquals(List.of("report"), request.fileIds());
        assertEquals(List.of("receiver-a"), request.receiverIds());
        assertFalse(request.toString().contains(root.toString()));
        assertFalse(request.toString().contains("PRIVATE FILE CONTENT"));
    }

    static Stream<CommandProposal> invalidProposals() {
        return Stream.of(
                call("start_transfer", START_ARGUMENTS.replace("report", "unapproved")),
                call("start_transfer", START_ARGUMENTS.replace("receiver-a", "elsewhere")),
                call("start_transfer", START_ARGUMENTS.replace("2500", "99999999999999999999")),
                call("start_transfer", START_ARGUMENTS.replace("2500", "2500.5")),
                call("start_transfer", START_ARGUMENTS.replace("350", "0")),
                call("start_transfer", START_ARGUMENTS.replace("\"report\"", "\"report\",\"host\":\"example.com\"")),
                call("start_transfer", START_ARGUMENTS.replace("\"report\"", "\"report\",\"file_id\":\"report\"")),
                call("start_transfer", "{\"file_id\":\"report\",\"receiver_id\":\"receiver-a\"}"),
                call("start_transfer", "not json"),
                call("shell", "{\"command\":\"anything\"}"),
                new CommandProposal.Calls(List.of(new CommandProposal.ToolCall("start_transfer", START_ARGUMENTS),
                        new CommandProposal.ToolCall("status", "{\"transfer_id\":null}"))),
                new CommandProposal.Calls(List.of()));
    }

    @ParameterizedTest
    @MethodSource("invalidProposals")
    void invalidModelProposalsNeverReachServiceStart(CommandProposal proposal) {
        useStub(proposal);
        assertFalse(command("Please send my file").contains("Start accepted"));
        assertEquals(0, service.starts);
    }

    @Test
    void clarificationReplyRetainsIntentAndRequestIdThenClearsAfterDispatch() {
        StubGptClient stub = useStub(new CommandProposal.Clarification("Which approved receiver?"), START, STATUS);

        assertTrue(command("Send report").contains("no command dispatched"));
        assertEquals(0, service.starts);
        command("receiver-a");
        command("How is it going?");

        assertEquals(1, service.starts);
        var first = stub.requests().get(0);
        var reply = stub.requests().get(1);
        var next = stub.requests().get(2);
        assertEquals(first.requestId(), reply.requestId());
        assertEquals(List.of(new InterpretationRequest.Turn("user", "Send report"),
                new InterpretationRequest.Turn("assistant", "Which approved receiver?")), reply.clarificationHistory());
        assertNotEquals(first.requestId(), next.requestId());
        assertTrue(next.clarificationHistory().isEmpty());
        assertEquals(service.lastRequest.transferId(), next.currentTransferId());
    }

    @Test
    void clarificationHistoryIsBoundedWithoutSilentlyDroppingOriginalIntent() {
        var clarification = new CommandProposal.Clarification("Which file and receiver?");
        StubGptClient stub = useStub(clarification, clarification, clarification, clarification);
        command("Send something");
        command("That file");
        assertTrue(command("Over there").contains("Please restate the full request"));
        command("New request");

        assertEquals(4, stub.requests().get(2).clarificationHistory().size());
        assertTrue(stub.requests().get(3).clarificationHistory().isEmpty());
        assertNotEquals(stub.requests().get(0).requestId(), stub.requests().get(3).requestId());
        assertEquals(0, service.starts);
    }

    @Test
    void directCommandEndsClarificationAndDoesNotContactGpt() {
        StubGptClient stub = useStub(new CommandProposal.Clarification("Which receiver?"), STATUS);
        command("Send report");
        command("help");
        command("catalog");
        command("status");
        command("How is it going?");

        assertEquals(2, stub.requests().size());
        assertTrue(stub.requests().get(1).clarificationHistory().isEmpty());
        assertNotEquals(stub.requests().get(0).requestId(), stub.requests().get(1).requestId());
        assertEquals(0, service.starts);
    }

    @Test
    void modelTextCannotClaimTransferExecution() {
        useStub(new CommandProposal.Clarification("Transfer started and completed!"));
        String displayed = command("Send something");
        assertTrue(displayed.startsWith("Model clarification (no command dispatched):"), displayed);
        assertFalse(displayed.contains("Start accepted"));
        assertEquals(0, service.starts);
    }

    @ParameterizedTest
    @EnumSource(GptException.Code.class)
    void apiFailureKeepsActiveTransferAndLocalCommandsAvailable(GptException.Code code) {
        useClient(request -> { throw new GptException(code); });
        command("start_transfer " + START_ARGUMENTS);
        UUID active = service.lastRequest.transferId();

        assertTrue(command("How is it going?").contains("GPT " + code));
        assertTrue(command("status").contains("[SYNTHETIC] RUNNING"));
        assertEquals(1, service.starts);
        assertEquals(TransferState.RUNNING, service.status(active).state());
        service.fake.advance(active);
        assertTrue(command("status").contains("[SYNTHETIC] COMPLETED"));
    }

    @Test
    void failedInterpretationClearsPendingContext() {
        var requests = new java.util.ArrayList<InterpretationRequest>();
        useClient(request -> {
            requests.add(request);
            if (requests.size() == 2) throw new GptException(GptException.Code.TIMEOUT);
            return new CommandProposal.Clarification("Which receiver?");
        });
        command("Send report");
        command("receiver-a");
        command("New request");
        assertTrue(requests.get(2).clarificationHistory().isEmpty());
        assertNotEquals(requests.get(0).requestId(), requests.get(2).requestId());
        assertEquals(0, service.starts);
    }

    @Test
    void naturalLanguageExplainSelectsEvidenceWithoutGeneratingAnExplanation() {
        StubGptClient stub = useStub(START,
                call("explain", "{\"run_id\":null,\"question\":\"Why was it slow?\"}"));
        command("Send report to receiver-a");
        service.fake.advance(service.lastRequest.transferId());

        String displayed = command("explain why it was slow");

        assertTrue(displayed.contains("Selected frozen outcome; question: Why was it slow?"));
        assertTrue(displayed.contains("No GPT explanation is generated"));
        assertEquals(service.lastRequest.transferId(), stub.requests().get(1).lastTransferId());
        assertNull(stub.requests().get(1).currentTransferId());
        assertEquals(2, stub.requests().size());
    }

    @Test
    void javaSuppliesCurrentAndLastIdsToInterpreterWithoutGuessingHistoricalSelection() {
        StubGptClient stub = useStub(START, START, STATUS);
        command("Send report to receiver-a");
        UUID first = service.lastRequest.transferId();
        service.fake.advance(first);
        command("Send report again to receiver-a");
        UUID second = service.lastRequest.transferId();
        command("How is this transfer going?");
        var request = stub.requests().get(2);
        assertEquals(first, request.lastTransferId());
        assertEquals(first, request.lastRunId());
        assertEquals(second, request.currentTransferId());
        assertEquals(second, request.currentRunId());
        assertEquals(2, service.starts);
    }

    @Test
    void askExplicitlyRoutesAReservedWordSentenceThroughInterpreter() {
        StubGptClient stub = useStub(STATUS);
        assertTrue(command("ask status of my last transfer").contains("No transfer is selected"));
        assertEquals("status of my last transfer", stub.requests().get(0).text());
        assertEquals(0, service.starts);
    }

    @Test
    void blankAndOversizedRequestsDoNotCallInterpreter() {
        StubGptClient stub = useStub(START);
        assertTrue(command("ask").contains("INVALID_COMMAND"));
        assertTrue(command("x".repeat(4001)).contains("INVALID_COMMAND"));
        command(" ");
        assertTrue(stub.requests().isEmpty());
        assertEquals(0, service.starts);
    }

    @Test
    void stubExhaustionFailsClosedRatherThanReplayingLastProposal() {
        useStub(START);
        command("Send report to receiver-a");
        service.fake.advance(service.lastRequest.transferId());
        assertTrue(command("Send report again").contains("GPT UNAVAILABLE"));
        assertEquals(1, service.starts);
    }

    private StubGptClient useStub(CommandProposal... proposals) {
        StubGptClient stub = new StubGptClient(List.of(proposals));
        useClient(stub);
        return stub;
    }

    private void useClient(GptClient client) {
        cli = new TransferCli(service, configuration, new StringReader(""), new PrintWriter(output), client);
    }

    private String command(String line) {
        output.getBuffer().setLength(0);
        assertTrue(cli.handleLine(line));
        return output.toString();
    }

    private static CommandProposal call(String name, String arguments) {
        return new CommandProposal.Calls(List.of(new CommandProposal.ToolCall(name, arguments)));
    }

    private static final class CountingService implements TransferService {
        final FakeTransferService fake = new FakeTransferService(
                FakeTransferService.Scenario.success(List.of(UNKNOWN), UNKNOWN));
        int starts;
        TransferRequest lastRequest;
        @Override public TransferStart start(TransferRequest request) {
            starts++;
            lastRequest = request;
            return fake.start(request);
        }
        @Override public TransferSnapshot status(UUID id) { return fake.status(id); }
        @Override public TransferSummary summary(UUID id) { return fake.summary(id); }
    }
}
