package nettransfer.cli;

import nettransfer.control.TransferError;
import nettransfer.control.TransferMetrics;
import nettransfer.control.TransferRequest;
import nettransfer.control.TransferService;
import nettransfer.control.TransferServiceException;
import nettransfer.control.TransferSnapshot;
import nettransfer.control.TransferStart;
import nettransfer.control.TransferState;
import nettransfer.control.TransferSummary;
import nettransfer.control.command.TransferConfiguration;
import nettransfer.control.simulation.FakeTransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class TransferCliTest {
    private static final String START = "start_transfer {\"file_id\":\"report\",\"receiver_id\":\"receiver-a\","
            + "\"window_bytes\":null,\"timeout_ms\":null}";
    private static final String EXPLAIN = "explain {\"run_id\":null,\"question\":\"What happened?\"}";
    private static final TransferMetrics UNKNOWN = TransferMetrics.unavailable("Synthetic unavailable fixture");

    @TempDir Path root;
    private TransferConfiguration configuration;
    private CountingService service;
    private TransferCli cli;
    private StringWriter output;

    @BeforeEach
    void setUp() throws Exception {
        Path source = root.resolve("data/input/report.txt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "report\n");
        configuration = TransferConfiguration.localhost(root, Map.of("report", Path.of("data/input/report.txt")));
        useScenario(FakeTransferService.Scenario.success(List.of(UNKNOWN), UNKNOWN));
    }

    @Test
    void helpAndCatalogExposeApprovedInputsWithoutStarting() {
        String help = command("help");
        String catalog = command("catalog");

        assertTrue(help.contains("start_transfer"));
        assertTrue(help.contains("null IDs select the current active run, or last terminal run"));
        assertTrue(catalog.contains("report = data"));
        assertTrue(catalog.contains("receiver-a = 127.0.0.1:9000"));
        assertEquals(0, service.starts);
    }

    @Test
    void missingAndAmbiguousReferencesClarifyWithoutStarting() {
        assertTrue(command("status").contains("Clarification: No transfer is selected"));
        assertTrue(command("status current").contains("no transfer is currently active"));
        assertTrue(command("status last").contains("no completed or failed transfer"));
        assertTrue(command("status first or second").contains("Clarification: select one transfer"));
        assertTrue(command(EXPLAIN).contains("Clarification: No run is selected"));
        assertEquals(0, service.starts);
    }

    @Test
    void malformedUnknownUnsupportedAndInvalidSettingsCannotStartService() {
        assertTrue(command("start_transfer {").contains("INVALID_COMMAND"));
        assertTrue(command(START.replace("report", "missing")).contains("UNKNOWN_FILE"));
        assertTrue(command(START.replace("\"timeout_ms\":null", "\"timeout_ms\":0")).contains("INVALID_PARAMETER"));
        assertTrue(command("please send the report").contains("UNSUPPORTED_REQUEST"));
        assertTrue(command("start_transfer " + START.substring(15) + " " + START.substring(15))
                .contains("INVALID_COMMAND"));
        assertEquals(0, service.starts);
    }

    @Test
    void acceptedStartIsLabelledAndStatusPreservesUnavailableMeasurements() {
        String acceptance = command(START);
        UUID id = service.lastRequest.transferId();
        String status = command("status {\"transfer_id\":null}");

        assertEquals(1, service.starts);
        assertTrue(acceptance.contains("Start accepted [SYNTHETIC]"));
        assertTrue(acceptance.contains("Requested window: 1024 bytes; effective window: 1 packet (1024 bytes)"));
        assertTrue(status.contains("[SYNTHETIC] RUNNING"));
        assertTrue(status.contains("transfer_id=" + id));
        assertTrue(status.contains("protocol_transfer_id=unavailable"));
        assertTrue(status.contains("unique_payload_bytes_acked=unavailable"));
        assertTrue(status.contains("elapsed_ms=unavailable; total_chunks=unavailable"));
        assertTrue(status.contains("Synthetic unavailable fixture"));
        assertFalse(status.contains("100%"));
        assertEquals(TransferState.RUNNING, service.fake.status(id).state());
    }

    @Test
    void activeExitIsRefusedButTerminalExitEndsTheSession() {
        command(START);
        output.getBuffer().setLength(0);
        assertTrue(cli.handleLine("exit"));
        assertTrue(output.toString().contains("exit refused"));
        assertTrue(command("help").contains("start_transfer"), "Console remains usable after refusal");

        service.fake.advance(service.lastRequest.transferId());
        assertFalse(cli.handleLine("exit"));
        assertTrue(output.toString().contains("Goodbye"));
    }

    @Test
    void currentAndLastSelectDifferentRunsAndHistoricalIdsRemainAvailable() {
        command(START);
        UUID first = service.lastRequest.transferId();
        service.fake.advance(first);
        assertSelected("status", first, "COMPLETED");
        assertTrue(command("status current").contains("no transfer is currently active"));

        command(START);
        UUID second = service.lastRequest.transferId();
        assertSelected("status current", second, "RUNNING");
        assertSelected("status this transfer", second, "RUNNING");
        assertSelected("status last", first, "COMPLETED");
        assertSelected("status last transfer", first, "COMPLETED");
        assertSelected("status " + first, first, "COMPLETED");
        assertSelected("status", second, "RUNNING");

        service.fake.advance(second);
        assertSelected("status last", second, "COMPLETED");
        assertSelected("status " + first, first, "COMPLETED");
    }

    @Test
    void lastRequiresATerminalRunEvenWhenTheFirstTransferIsActive() {
        command(START);

        assertTrue(command("status last").contains("no completed or failed transfer"));
        assertSelected("status", service.lastRequest.transferId(), "RUNNING");
    }

    @Test
    void busyAndRejectedStartsDoNotReplaceCurrentOrLastSelection() {
        command(START);
        UUID first = service.lastRequest.transferId();
        assertTrue(command(START).contains("TRANSFER_BUSY"));
        assertSelected("status", first, "RUNNING");

        service.fake.advance(first);
        service.startFailure = new TransferServiceException(TransferError.Code.TRANSFER_FAILED, "Start failed");
        assertTrue(command(START).contains("TRANSFER_FAILED: Start failed"));
        assertSelected("status", first, "COMPLETED");
        assertSelected("status last", first, "COMPLETED");
    }

    @Test
    void completionBetweenRefreshAndNextStartStillPreservesLastTerminalRun() {
        command(START);
        UUID first = service.lastRequest.transferId();
        service.completePreviousOnStart = true;

        // refreshCurrent sees RUNNING; the test service completes it inside the next start.
        command(START);

        assertSelected("status last", first, "COMPLETED");
        assertSelected("status current", service.lastRequest.transferId(), "RUNNING");
        assertNotEquals(first, service.lastRequest.transferId());
    }

    @Test
    void failedTerminalRunIsSelectedAndANewTransferMayFollow() {
        useScenario(FakeTransferService.Scenario.engineFailure(List.of(UNKNOWN), UNKNOWN));
        command(START);
        UUID failed = service.lastRequest.transferId();
        service.fake.advance(failed);

        assertSelected("status", failed, "FAILED");
        assertTrue(command(EXPLAIN).contains("integrity=UNCONFIRMED"));
        assertTrue(command(START).contains("Start accepted"));
        assertSelected("status last", failed, "FAILED");
        assertSelected("status current", service.lastRequest.transferId(), "RUNNING");
    }

    @Test
    void explainSelectsFrozenEvidenceOnlyAndDoesNotSubstitutePreviousRunWhileActive() {
        command(START);
        UUID first = service.lastRequest.transferId();
        assertTrue(command(EXPLAIN).contains("SUMMARY_NOT_READY"));
        service.fake.advance(first);
        String evidence = command(EXPLAIN);
        assertTrue(evidence.contains("Selected frozen outcome; question: What happened?"));
        assertTrue(evidence.contains("integrity=VERIFIED"));
        assertTrue(evidence.contains("No GPT explanation is generated"));
        assertTrue(evidence.contains("not a persisted experiment log"));

        command(START);
        assertTrue(command(EXPLAIN).contains("SUMMARY_NOT_READY"));
        String historical = command("explain {\"run_id\":\"" + first + "\",\"question\":\"Earlier result?\"}");
        assertTrue(historical.contains("run_id=" + first));
        assertTrue(historical.contains("[SYNTHETIC] COMPLETED"));
    }

    @Test
    void unknownAndInvalidIdsLeaveExistingSelectionIntact() {
        command(START);
        UUID active = service.lastRequest.transferId();
        assertTrue(command("status " + UUID.randomUUID()).contains("UNKNOWN_TRANSFER"));
        assertTrue(command("status invalid-id").contains("INVALID_PARAMETER"));
        assertTrue(command("explain {\"run_id\":\"" + UUID.randomUUID()
                + "\",\"question\":\"Why?\"}").contains("UNKNOWN_TRANSFER"));
        assertSelected("status", active, "RUNNING");
    }

    @Test
    void emptyEofReturnsOnceWithoutInventingATransfer() throws Exception {
        cli.run();

        assertTrue(output.toString().contains("Input closed; leaving console"));
        assertEquals(0, service.starts);
    }

    @Test
    void eofDuringActiveWorkReportsInterruptionAndReturnsToResourceOwner() throws Exception {
        new TransferCli(service, configuration, new StringReader(START + "\nstatus\nexit\nhelp\n"),
                new PrintWriter(output)).run();

        assertTrue(output.toString().contains("[SYNTHETIC] RUNNING"));
        assertTrue(output.toString().contains("exit refused"));
        assertTrue(output.toString().contains("Input closed while transfer is active; shutdown will interrupt it"));
        assertFalse(output.toString().contains("[SYNTHETIC] COMPLETED"));
        assertEquals(1, service.starts);
    }

    @Test
    void consoleReadsStatusWhileCompletionIsGatedByAnotherThread() throws Exception {
        CountDownLatch runningDisplayed = new CountDownLatch(1);
        StringWriter recorded = new StringWriter() {
            @Override
            public void write(String text, int offset, int length) {
                super.write(text, offset, length);
                if (toString().contains("[SYNTHETIC] RUNNING")) {
                    runningDisplayed.countDown();
                }
            }
        };
        var consoleWorker = Executors.newSingleThreadExecutor();
        try (PipedWriter commands = new PipedWriter(); PipedReader input = new PipedReader(commands)) {
            TransferCli interactive = new TransferCli(service, configuration, input, new PrintWriter(recorded));
            var console = consoleWorker.submit(() -> {
                interactive.run();
                return null;
            });
            commands.write(START + "\nstatus\n");
            commands.flush();

            assertTrue(runningDisplayed.await(3, TimeUnit.SECONDS), "Status must be rendered before completion is released");
            assertFalse(console.isDone(), "Console is still accepting commands");
            assertEquals(TransferState.RUNNING, service.fake.status(service.lastRequest.transferId()).state());
            service.fake.advance(service.lastRequest.transferId());
            commands.write("status\nexit\n");
            commands.flush();
            console.get(3, TimeUnit.SECONDS);
            assertTrue(recorded.toString().contains("[SYNTHETIC] COMPLETED"));
            assertTrue(recorded.toString().contains("Goodbye"));
        } finally {
            consoleWorker.shutdownNow();
            assertTrue(consoleWorker.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private void assertSelected(String command, UUID expected, String state) {
        String rendered = command(command);
        assertTrue(rendered.contains("transfer_id=" + expected), rendered);
        assertTrue(rendered.contains("[SYNTHETIC] " + state), rendered);
    }

    private String command(String command) {
        output.getBuffer().setLength(0);
        assertTrue(cli.handleLine(command));
        return output.toString();
    }

    private void useScenario(FakeTransferService.Scenario scenario) {
        service = new CountingService(new FakeTransferService(scenario));
        output = new StringWriter();
        cli = new TransferCli(service, configuration, new StringReader(""), new PrintWriter(output));
    }

    private static final class CountingService implements TransferService {
        private final FakeTransferService fake;
        private int starts;
        private volatile TransferRequest lastRequest;
        private TransferServiceException startFailure;
        private boolean completePreviousOnStart;

        private CountingService(FakeTransferService fake) {
            this.fake = fake;
        }

        @Override
        public TransferStart start(TransferRequest request) {
            starts++;
            if (startFailure != null) {
                throw startFailure;
            }
            if (completePreviousOnStart) {
                fake.advance(lastRequest.transferId());
                completePreviousOnStart = false;
            }
            TransferStart accepted = fake.start(request);
            lastRequest = request;
            return accepted;
        }

        @Override public TransferSnapshot status(UUID id) { return fake.status(id); }
        @Override public TransferSummary summary(UUID id) { return fake.summary(id); }
    }
}
