package nettransfer.control.command;

import nettransfer.control.EvidenceSource;
import nettransfer.control.TransferError;
import nettransfer.control.TransferMetrics;
import nettransfer.control.TransferRequest;
import nettransfer.control.TransferService;
import nettransfer.control.TransferServiceException;
import nettransfer.control.TransferSnapshot;
import nettransfer.control.TransferStart;
import nettransfer.control.TransferState;
import nettransfer.control.TransferSummary;
import nettransfer.control.simulation.FakeTransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static nettransfer.control.TransferError.Code.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class CommandDispatcherTest {
    private static final TransferMetrics INITIAL = new TransferMetrics(7L, 0L, 0L, 1, null);
    private static final TransferMetrics COMPLETE = new TransferMetrics(7L, 7L, 100L, 1, null);

    @TempDir
    Path applicationRoot;

    private Path source;
    private TransferConfiguration configuration;
    private CountingService service;
    private CommandDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        source = applicationRoot.resolve("data/input/report.txt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "report\n");
        configuration = TransferConfiguration.localhost(applicationRoot, Map.of(
                "report", Path.of("data/input/report.txt"),
                "missing", Path.of("data/input/missing.txt")));
        useScenario(FakeTransferService.Scenario.success(List.of(INITIAL), COMPLETE));
    }

    @Test
    void validStartResolvesApprovedInputsAndReportsAcceptanceWithDefaults() throws Exception {
        UUID requestId = UUID.randomUUID();

        DispatchResult.Started result = assertInstanceOf(DispatchResult.Started.class,
                dispatcher.dispatch(requestId, start("report", "receiver-a", "null", "null")));

        assertEquals(1, service.starts.get());
        assertEquals(0, service.statusReads.get());
        assertEquals(0, service.summaryReads.get());
        assertEquals(requestId, service.lastRequest.requestId());
        assertEquals(source.toRealPath(), service.lastRequest.sourcePath());
        assertEquals(configuration.approvedReceivers().get("receiver-a"), service.lastRequest.receiver());
        assertEquals(service.lastRequest.transferId(), result.acknowledgement().transferId());
        assertEquals(EvidenceSource.SYNTHETIC, result.acknowledgement().evidenceSource());
        assertEquals(TransferState.RUNNING,
                service.fake.status(result.acknowledgement().transferId()).state());
        assertEquals("Requested window: 1024 bytes; effective window: 1 packet (1024 bytes); "
                + "timeout: 200 ms; retry limit: 5 consecutive rounds without progress",
                result.settingsDescription());
    }

    @Test
    void nonmultipleWindowDisplaysRequestedAndEffectiveCapacity() {
        DispatchResult.Started result = assertInstanceOf(DispatchResult.Started.class,
                dispatch(start("report", "receiver-a", "2500", "350")));

        assertEquals(2500L, result.settings().requestedWindowBytes());
        assertEquals(2, result.settings().windowPackets());
        assertEquals("Requested window: 2500 bytes; effective window: 2 packets (2048 bytes); "
                + "timeout: 350 ms; retry limit: 5 consecutive rounds without progress",
                result.settingsDescription());
        assertEquals(1, service.starts.get());
    }

    @Test
    void rejectedJsonIdsSettingsAndMissingSourcesNeverReachService() {
        record InvalidCase(CommandProposal proposal, TransferError.Code code) { }
        List<InvalidCase> cases = List.of(
                new InvalidCase(call("start_transfer", "{"), INVALID_COMMAND),
                new InvalidCase(call("start_transfer", "{}"), INVALID_COMMAND),
                new InvalidCase(call("start_transfer", """
                        {"file_id":"report","receiver_id":"receiver-a","window_bytes":null,
                         "timeout_ms":null,"path":"data/input/other.txt"}
                        """), INVALID_COMMAND),
                new InvalidCase(start("unknown", "receiver-a", "null", "null"), UNKNOWN_FILE),
                new InvalidCase(start("report", "unknown", "null", "null"), UNKNOWN_RECEIVER),
                new InvalidCase(start("report", "receiver-a", "1023", "null"), INVALID_PARAMETER),
                new InvalidCase(start("report", "receiver-a", "1048577", "null"), INVALID_PARAMETER),
                new InvalidCase(start("report", "receiver-a", "null", "49"), INVALID_PARAMETER),
                new InvalidCase(start("report", "receiver-a", "null", "5001"), INVALID_PARAMETER),
                new InvalidCase(start("missing", "receiver-a", "null", "null"), FILE_UNAVAILABLE));

        for (InvalidCase invalid : cases) {
            assertRejected(invalid.code(), dispatch(invalid.proposal()));
        }

        assertNoServiceCalls();
    }

    @Test
    void multipleCallsAreRejectedBeforeExecutingEvenTheValidFirstCall() {
        var validStart = start("report", "receiver-a", "null", "null").calls().get(0);
        var validStatus = new CommandProposal.ToolCall("status",
                "{\"transfer_id\":\"" + UUID.randomUUID() + "\"}");
        var malformed = new CommandProposal.ToolCall("start_transfer", "{");

        assertRejected(INVALID_COMMAND,
                dispatch(new CommandProposal.Calls(List.of(validStart, malformed))));
        assertRejected(INVALID_COMMAND,
                dispatch(new CommandProposal.Calls(List.of(validStatus, validStart))));

        assertNoServiceCalls();
    }

    @Test
    void clarificationUnsupportedAndEmptyProposalsDoNotExecuteAnything() {
        assertEquals(new DispatchResult.Clarification("Which file?"),
                dispatch(new CommandProposal.Clarification("Which file?")));
        assertEquals(new DispatchResult.Unsupported("Deleting files is unsupported"),
                dispatch(new CommandProposal.Unsupported("Deleting files is unsupported")));
        assertInstanceOf(DispatchResult.Clarification.class,
                dispatch(new CommandProposal.Calls(List.of())));
        assertInstanceOf(DispatchResult.Unsupported.class, dispatch(call("delete_file", "{}")));
        assertInstanceOf(DispatchResult.Clarification.class,
                dispatch(start("", "receiver-a", "null", "null")));

        assertNoServiceCalls();
    }

    @Test
    void nullSelectionAndBlankQuestionsAskForClarificationWithoutLookingUpEvidence() {
        assertInstanceOf(DispatchResult.Clarification.class, dispatch(call("status", "{\"transfer_id\":null}")));
        assertInstanceOf(DispatchResult.Clarification.class,
                dispatch(call("explain", "{\"run_id\":null,\"question\":\"Why?\"}")));
        assertInstanceOf(DispatchResult.Clarification.class,
                dispatcher.dispatch(UUID.randomUUID(),
                        call("explain", "{\"run_id\":null,\"question\":\" \"}"),
                        new CommandDispatcher.Selection(null, UUID.randomUUID())));
        assertRejected(INVALID_PARAMETER,
                dispatch(call("status", "{\"transfer_id\":\"not-a-uuid\"}")));

        assertNoServiceCalls();
    }

    @Test
    void statusUsesConcreteIdsOrTrustedSelectionAndPreservesUnknownIdErrors() {
        TransferStart accepted = begin();
        var selection = new CommandDispatcher.Selection(accepted.transferId(), accepted.runId());
        var wrongSelection = new CommandDispatcher.Selection(UUID.randomUUID(), UUID.randomUUID());

        DispatchResult.Status explicit = assertInstanceOf(DispatchResult.Status.class,
                dispatcher.dispatch(UUID.randomUUID(), call("status",
                        "{\"transfer_id\":\"" + accepted.transferId() + "\"}"), wrongSelection));
        DispatchResult.Status selected = assertInstanceOf(DispatchResult.Status.class,
                dispatcher.dispatch(UUID.randomUUID(), call("status", "{\"transfer_id\":null}"), selection));
        assertSame(explicit.snapshot(), selected.snapshot());
        assertEquals(TransferState.RUNNING, selected.snapshot().state());
        assertEquals(EvidenceSource.SYNTHETIC, selected.snapshot().evidenceSource());
        assertRejected(UNKNOWN_TRANSFER, dispatcher.dispatch(UUID.randomUUID(), call("status",
                "{\"transfer_id\":\"" + UUID.randomUUID() + "\"}"), selection));

        assertEquals(1, service.starts.get());
        assertEquals(3, service.statusReads.get());
        assertEquals(0, service.summaryReads.get());
    }

    @Test
    void explainSelectsFrozenSyntheticEvidenceAndQuestionOnlyAfterCompletion() {
        TransferStart accepted = begin();
        var selection = new CommandDispatcher.Selection(accepted.transferId(), accepted.runId());
        var explainSelected = call("explain", "{\"run_id\":null,\"question\":\"Why this result?\"}");
        assertRejected(SUMMARY_NOT_READY,
                dispatcher.dispatch(UUID.randomUUID(), explainSelected, selection));
        assertRejected(UNKNOWN_TRANSFER,
                dispatcher.dispatch(UUID.randomUUID(), call("explain",
                        "{\"run_id\":\"" + UUID.randomUUID() + "\",\"question\":\"Why?\"}"), selection));

        service.fake.advance(accepted.transferId());
        TransferSummary frozen = service.fake.summary(accepted.runId());
        DispatchResult.SummarySelected explicit = assertInstanceOf(DispatchResult.SummarySelected.class,
                dispatcher.dispatch(UUID.randomUUID(), call("explain",
                        "{\"run_id\":\"" + accepted.runId() + "\",\"question\":\"Why this result?\"}"),
                        new CommandDispatcher.Selection(UUID.randomUUID(), UUID.randomUUID())));
        DispatchResult.SummarySelected selected = assertInstanceOf(DispatchResult.SummarySelected.class,
                dispatcher.dispatch(UUID.randomUUID(), explainSelected, selection));

        assertSame(frozen, explicit.summary());
        assertEquals(explicit, selected);
        assertEquals("Why this result?", selected.question());
        assertEquals(EvidenceSource.SYNTHETIC, selected.summary().finalSnapshot().evidenceSource());
        assertEquals(1, service.starts.get());
        assertEquals(0, service.statusReads.get());
        assertEquals(4, service.summaryReads.get());
    }

    @Test
    void replayOfCompletedRequestCannotStartAgainButNewRequestCan() {
        UUID requestId = UUID.randomUUID();
        var proposal = start("report", "receiver-a", "null", "null");
        TransferStart first = assertInstanceOf(DispatchResult.Started.class,
                dispatcher.dispatch(requestId, proposal)).acknowledgement();
        service.fake.advance(first.transferId());

        assertRejected(REQUEST_ALREADY_DISPATCHED, dispatcher.dispatch(requestId, proposal));
        assertEquals(1, service.starts.get());
        TransferStart next = begin();

        assertNotEquals(first.transferId(), next.transferId());
        assertEquals(2, service.starts.get());
        assertEquals(TransferState.COMPLETED, service.fake.summary(first.runId()).finalSnapshot().state());
    }

    @Test
    void concurrentProposalsForSameRequestInvokeStartExactlyOnce() throws Exception {
        UUID requestId = UUID.randomUUID();

        List<DispatchResult> results = simultaneousStarts(requestId, requestId);

        assertEquals(1, results.stream().filter(DispatchResult.Started.class::isInstance).count());
        DispatchResult rejected = results.stream().filter(DispatchResult.Rejected.class::isInstance)
                .findFirst().orElseThrow();
        assertRejected(REQUEST_ALREADY_DISPATCHED, rejected);
        assertEquals(1, service.starts.get());
    }

    @Test
    void concurrentDistinctRequestsRelyOnAtomicServiceReservation() throws Exception {
        List<DispatchResult> results = simultaneousStarts(UUID.randomUUID(), UUID.randomUUID());

        assertEquals(1, results.stream().filter(DispatchResult.Started.class::isInstance).count());
        DispatchResult rejected = results.stream().filter(DispatchResult.Rejected.class::isInstance)
                .findFirst().orElseThrow();
        assertRejected(TRANSFER_BUSY, rejected);
        assertEquals(2, service.starts.get(), "Both distinct valid requests reach the atomic service boundary");
        assertEquals(0, service.statusReads.get(), "A preflight status check cannot reserve an active slot");
    }

    @Test
    void serviceStartFailureConsumesRequestSoRetryNeedsANewExplicitRequest() {
        UUID requestId = UUID.randomUUID();
        var proposal = start("report", "receiver-a", "null", "null");
        service.startFailure = new TransferServiceException(TRANSFER_FAILED, "Synthetic start failure");

        assertRejected(TRANSFER_FAILED, dispatcher.dispatch(requestId, proposal));
        service.startFailure = null;
        assertRejected(REQUEST_ALREADY_DISPATCHED, dispatcher.dispatch(requestId, proposal));
        assertEquals(1, service.starts.get());
        assertInstanceOf(DispatchResult.Started.class, dispatch(proposal));
        assertEquals(2, service.starts.get());
    }

    @Test
    void failedTerminalRunAllowsANewRequestAndRetainsTheFailedEvidence() {
        useScenario(FakeTransferService.Scenario.engineFailure(List.of(INITIAL), INITIAL));
        TransferStart first = begin();
        service.fake.advance(first.transferId());
        TransferSummary failed = service.fake.summary(first.runId());

        TransferStart next = begin();

        assertEquals(TransferState.FAILED, failed.finalSnapshot().state());
        assertEquals(TRANSFER_FAILED, failed.finalSnapshot().error().code());
        assertSame(failed, service.fake.summary(first.runId()));
        assertEquals(TransferState.RUNNING, service.fake.status(next.transferId()).state());
        assertEquals(2, service.starts.get());
    }

    private void useScenario(FakeTransferService.Scenario scenario) {
        service = new CountingService(new FakeTransferService(scenario));
        dispatcher = new CommandDispatcher(new CommandParser(), new CommandValidator(configuration), service);
    }

    private TransferStart begin() {
        return assertInstanceOf(DispatchResult.Started.class,
                dispatch(start("report", "receiver-a", "null", "null"))).acknowledgement();
    }

    private DispatchResult dispatch(CommandProposal proposal) {
        return dispatcher.dispatch(UUID.randomUUID(), proposal);
    }

    private static CommandProposal.Calls call(String name, String arguments) {
        return new CommandProposal.Calls(List.of(new CommandProposal.ToolCall(name, arguments)));
    }

    private static CommandProposal.Calls start(String fileId, String receiverId, String window, String timeout) {
        return call("start_transfer", """
                {"file_id":"%s","receiver_id":"%s","window_bytes":%s,"timeout_ms":%s}
                """.formatted(fileId, receiverId, window, timeout));
    }

    private static void assertRejected(TransferError.Code code, DispatchResult result) {
        assertEquals(code, assertInstanceOf(DispatchResult.Rejected.class, result).error().code());
    }

    private void assertNoServiceCalls() {
        assertEquals(0, service.starts.get());
        assertEquals(0, service.statusReads.get());
        assertEquals(0, service.summaryReads.get());
    }

    private List<DispatchResult> simultaneousStarts(UUID first, UUID second) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<DispatchResult>> attempts = new ArrayList<>();
            for (UUID requestId : List.of(first, second)) {
                attempts.add(workers.submit(() -> {
                    ready.countDown();
                    if (!go.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("Dispatch start gate did not open");
                    }
                    return dispatcher.dispatch(requestId, start("report", "receiver-a", "null", "null"));
                }));
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            go.countDown();
            return List.of(attempts.get(0).get(2, TimeUnit.SECONDS), attempts.get(1).get(2, TimeUnit.SECONDS));
        } finally {
            go.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    /** Counts boundary calls while preserving the fake's actual slot and history semantics. */
    private static final class CountingService implements TransferService {
        private final FakeTransferService fake;
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger statusReads = new AtomicInteger();
        private final AtomicInteger summaryReads = new AtomicInteger();
        private volatile TransferRequest lastRequest;
        private volatile TransferServiceException startFailure;

        private CountingService(FakeTransferService fake) {
            this.fake = fake;
        }

        @Override
        public TransferStart start(TransferRequest request) {
            starts.incrementAndGet();
            lastRequest = request;
            TransferServiceException failure = startFailure;
            if (failure != null) {
                throw failure;
            }
            return fake.start(request);
        }

        @Override
        public TransferSnapshot status(UUID transferId) {
            statusReads.incrementAndGet();
            return fake.status(transferId);
        }

        @Override
        public TransferSummary summary(UUID runId) {
            summaryReads.incrementAndGet();
            return fake.summary(runId);
        }
    }
}
