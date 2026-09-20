package nettransfer.cli;

import nettransfer.control.EvidenceSource;
import nettransfer.control.TransferError;
import nettransfer.control.TransferRequest;
import nettransfer.control.TransferService;
import nettransfer.control.TransferServiceException;
import nettransfer.control.TransferSnapshot;
import nettransfer.control.TransferStart;
import nettransfer.control.TransferState;
import nettransfer.control.TransferSummary;
import nettransfer.control.command.CommandProposal;
import nettransfer.control.command.TransferConfiguration;
import nettransfer.explanation.ExplanationClient;
import nettransfer.explanation.ExplanationDraft;
import nettransfer.explanation.ExplanationFlow;
import nettransfer.explanation.ExplanationRequest;
import nettransfer.explanation.RecordedSummary;
import nettransfer.explanation.StubExplanationClient;
import nettransfer.explanation.SummaryProvider;
import nettransfer.explanation.SyntheticExplanationFixtures;
import nettransfer.explanation.SyntheticExplanationFixtures.Fixture;
import nettransfer.explanation.SyntheticSummaryProvider;
import nettransfer.llm.GptClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** CLI integration uses only authored fixtures and scripted clients; no sockets or provider calls. */
class TransferCliExplanationTest {
    private static final String START = "start_transfer {\"file_id\":\"report\",\"receiver_id\":\"receiver-a\","
            + "\"window_bytes\":null,\"timeout_ms\":null}";

    @TempDir Path root;
    private TransferConfiguration configuration;
    private FixtureService service;
    private StringWriter output;
    private TransferCli cli;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("data/input"));
        Files.writeString(root.resolve("data/input/report.txt"), "synthetic test input");
        configuration = TransferConfiguration.localhost(root,
                Map.of("report", Path.of("data/input/report.txt")));
        service = new FixtureService();
        output = new StringWriter();
    }

    @Test
    void directExplainDisplaysVerifiedIdentityOriginalValuesAndDraftWithoutInterpretation() {
        Fixture fixture = SyntheticExplanationFixtures.baseline();
        service.add(fixture);
        var client = new StubExplanationClient(List.of(fixture.draft()));
        use(flow(fixture, client));

        String text = command(explain(fixture.evidence().runId()));

        assertTrue(text.contains("EXPLAINED:"), text);
        assertTrue(text.contains("Evidence [SYNTHETIC] SYNTHETIC fixture: baseline"), text);
        assertTrue(text.contains("definition_version=" + RecordedSummary.FIXTURE_DEFINITION_VERSION));
        assertTrue(text.contains("prompt_version=" + ExplanationRequest.PROMPT_VERSION));
        assertTrue(text.contains("run_id=" + fixture.evidence().runId()));
        assertTrue(text.contains("transfer_id=" + fixture.evidence().transferId()));
        assertTrue(text.contains("protocol_transfer_id=" + fixture.evidence().protocolTransferId()));
        assertTrue(text.contains("captured_at=" + fixture.evidence().capturedAt()));
        assertTrue(text.contains("duration_ms=1000 ms [OBSERVED]"));
        assertTrue(text.contains("retransmitted_packets=0 packets [OBSERVED]"));
        assertTrue(text.contains("[run=" + fixture.evidence().runId() + "; field=duration_ms] 1000 ms"));
        assertTrue(text.contains("Explanation draft (no command dispatched)"));
        assertEquals(fixture.evidence(), client.requests().get(0).evidence());
        assertEquals(0, service.starts);
    }

    @Test
    void interpretedExplanationSelectsTheSameReadOnlyFlowAndCannotExecuteProse() {
        Fixture fixture = SyntheticExplanationFixtures.loss();
        service.add(fixture);
        var client = new StubExplanationClient(List.of(fixture.draft()));
        AtomicInteger interpretations = new AtomicInteger();
        GptClient interpreter = request -> {
            interpretations.incrementAndGet();
            return new CommandProposal.Calls(List.of(new CommandProposal.ToolCall("explain",
                    "{\"run_id\":\"" + fixture.evidence().runId() + "\",\"question\":\"Why was it slow?\"}")));
        };
        use(interpreter, flow(fixture, client));

        String text = command("Why was that transfer slow?");

        assertEquals(1, interpretations.get());
        assertEquals(1, client.requests().size());
        assertEquals("Why was it slow?", client.requests().get(0).question());
        assertTrue(text.contains("EXPLAINED:"), text);
        assertFalse(text.contains("Start accepted"));
        assertEquals(0, service.starts);
    }

    @Test
    void configuredImpairmentMissingMeasurementsAndCausalLimitsAreClearlyLabelled() {
        Fixture fixture = SyntheticExplanationFixtures.loss();
        service.add(fixture);
        use(flow(fixture, request -> fixture.draft()));

        String text = command(explain(fixture.evidence().runId()));

        assertTrue(text.contains("configured_loss_percent=10 percent [CONFIGURED]"), text);
        assertTrue(text.contains("retransmitted_packets=12 packets [OBSERVED]"));
        assertTrue(text.contains("observed_drops=unavailable packets [OBSERVED]"));
        assertTrue(text.contains("Missing evidence: The synthetic fixture supplies no drop observations."));
        assertTrue(text.contains("Draft: configured synthetic impairment probability, not measured packet loss."));
        assertTrue(text.contains("Configured impairment is not observed loss"));
        assertTrue(text.contains("retransmissions do not establish loss percentage"));
        assertTrue(text.contains("timeouts do not prove congestion"));
        assertTrue(text.contains("A single run cannot establish which setting is faster"));
        assertTrue(text.contains("Hypothesis (unproven):"));
        assertTrue(text.contains("Limitation: SYNTHETIC test data; draft definitions are not a team contract."));
        assertEquals(0, service.starts);
    }

    @Test
    void noMeasurementsRemainUnavailableInsteadOfBecomingZero() {
        Fixture fixture = SyntheticExplanationFixtures.missing();
        service.add(fixture);
        use(flow(fixture, request -> fixture.draft()));

        String text = command(explain(fixture.evidence().runId()));

        assertTrue(text.contains("EXPLAINED:"), text);
        assertTrue(text.contains("duration_ms=unavailable ms [OBSERVED]"));
        assertTrue(text.contains("throughput_bps=unavailable bits/s [OBSERVED]"));
        assertTrue(text.contains("Missing evidence: No protocol timing observations supplied."));
        assertTrue(text.contains("Missing evidence: No throughput measurement supplied."));
        assertTrue(text.contains("The evidence cannot establish transfer performance."));
        assertFalse(text.contains("duration_ms=0"));
        assertFalse(text.contains("throughput_bps=0"));
        assertFalse(text.contains("Observation:"));
        assertEquals(0, service.starts);
    }

    @Test
    void failedOutcomeRetainsPartialEvidenceAndUnconfirmedIntegrity() {
        Fixture fixture = SyntheticExplanationFixtures.failure();
        service.add(fixture);
        var client = new StubExplanationClient(List.of(fixture.draft()));
        use(flow(fixture, client));

        String text = command(explain(fixture.evidence().runId()));

        assertTrue(text.contains("[SYNTHETIC] FAILED"), text);
        assertTrue(text.contains("integrity=UNCONFIRMED"));
        assertTrue(text.contains("unique_payload_bytes_acked=2048 bytes [OBSERVED]"));
        assertTrue(text.contains("partial ACK progress is not verified completion"));
        assertFalse(text.contains("[SYNTHETIC] COMPLETED"));
        assertEquals(TransferState.FAILED, client.requests().get(0).state());
        assertEquals(fixture.integrity(), client.requests().get(0).integrity());
        assertEquals(0, service.starts);
    }

    @Test
    void activeCurrentRunDoesNotSubstituteOlderSummaryAndExplicitHistoryStillWorks() {
        service.allowStarts = true;
        List<UUID> lookups = new ArrayList<>();
        List<UUID> analyses = new ArrayList<>();
        use(new ExplanationFlow(runId -> {
            lookups.add(runId);
            return Optional.ofNullable(service.fixtures.get(runId)).map(Fixture::evidence);
        }, request -> {
            analyses.add(request.evidence().runId());
            return service.fixtures.get(request.evidence().runId()).draft();
        }));
        command(START);
        UUID firstRun = service.lastStartedRun;
        service.finish(firstRun);
        command(START);
        UUID activeRun = service.lastStartedRun;

        String active = command(explain(null));
        assertTrue(active.contains("SUMMARY_NOT_READY"), active);
        assertEquals(List.of(), lookups);
        assertEquals(List.of(), analyses);

        String historical = command(explain(firstRun));
        assertTrue(historical.contains("EXPLAINED:"), historical);
        assertTrue(historical.contains("run_id=" + firstRun));
        assertFalse(historical.contains("run_id=" + activeRun));
        assertEquals(List.of(firstRun), lookups);
        assertEquals(List.of(firstRun), analyses);

        service.finish(activeRun);
        assertTrue(command(explain(null)).contains("run_id=" + activeRun));
        assertEquals(List.of(firstRun, activeRun), analyses);
        assertEquals(2, service.starts, "Only the two explicit setup commands may start work");
    }

    @Test
    void unknownOrUnselectedRunNeverCallsProviderOrAnalysis() {
        use(new ExplanationFlow(runId -> {
            fail("A nonexistent selection must not reach the provider");
            return Optional.empty();
        }, request -> {
            fail("A nonexistent selection must not reach the analysis client");
            return null;
        }));

        assertTrue(command(explain(UUID.randomUUID())).contains("UNKNOWN_TRANSFER"));
        assertTrue(command(explain(null)).contains("Clarification: No run is selected"));
        assertEquals(0, service.starts);
    }

    @Test
    void productionDefaultReportsMissingRealEvidence() {
        Fixture fixture = SyntheticExplanationFixtures.baseline();
        service.add(asReal(fixture.selected()));
        cli = new TransferCli(service, configuration, new StringReader(""), new PrintWriter(output));

        String text = command(explain(fixture.evidence().runId()));

        assertTrue(text.contains("[REAL] COMPLETED"), text);
        assertTrue(text.contains("EVIDENCE_UNAVAILABLE: Real recorded measurements are unavailable"));
        assertTrue(text.contains("Person 2's summaries and the agreed identity/metric contract are pending"));
        assertTrue(text.contains("No GPT explanation is generated"));
        assertFalse(text.contains("Evidence [SYNTHETIC]"));
        assertFalse(text.contains("duration_ms=1000"));
        assertEquals(0, service.starts);
    }

    @Test
    void syntheticFixtureWithMatchingIdsCannotReplaceRealMeasurements() {
        Fixture fixture = SyntheticExplanationFixtures.baseline();
        service.add(asReal(fixture.selected()));
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger clientCalls = new AtomicInteger();
        use(new ExplanationFlow(runId -> {
            providerCalls.incrementAndGet();
            return Optional.of(fixture.evidence());
        }, request -> {
            clientCalls.incrementAndGet();
            return fixture.draft();
        }));

        String text = command(explain(fixture.evidence().runId()));

        assertTrue(text.contains("EVIDENCE_UNAVAILABLE:"), text);
        assertFalse(text.contains("Evidence [SYNTHETIC]"));
        assertFalse(text.contains("duration_ms=1000"));
        assertEquals(0, providerCalls.get());
        assertEquals(0, clientCalls.get());
        assertEquals(0, service.starts);
    }

    @Test
    void absentOrFailedProviderPreservesFrozenOutcomeWithoutInventingMeasurements() {
        Fixture fixture = SyntheticExplanationFixtures.failure();
        service.add(fixture);
        for (SummaryProvider provider : List.<SummaryProvider>of(SummaryProvider.unavailable(), runId -> {
            throw new IllegalStateException("private provider internals");
        })) {
            use(new ExplanationFlow(provider, request -> {
                fail("Analysis cannot run without evidence");
                return null;
            }));

            String text = command(explain(fixture.evidence().runId()));

            assertTrue(text.contains("EVIDENCE_UNAVAILABLE:"), text);
            assertTrue(text.contains("[SYNTHETIC] FAILED"));
            assertTrue(text.contains("integrity=UNCONFIRMED"));
            assertTrue(text.contains("No GPT explanation is generated"));
            assertFalse(text.contains("private provider internals"));
            assertFalse(text.contains("unique_payload_bytes_acked=2048"));
        }
        assertEquals(0, service.starts);
    }

    @Test
    void failedAnalysisKeepsSourceValuesAndMissingEvidenceVisible() {
        Fixture fixture = SyntheticExplanationFixtures.failure();
        service.add(fixture);
        use(flow(fixture, request -> {
            throw new IllegalStateException("private client internals");
        }));

        String text = command(explain(fixture.evidence().runId()));

        assertTrue(text.contains("EXPLANATION_UNAVAILABLE:"), text);
        assertTrue(text.contains("Evidence [SYNTHETIC]"));
        assertTrue(text.contains("unique_payload_bytes_acked=2048 bytes [OBSERVED]"));
        assertTrue(text.contains("duration_ms=unavailable ms [OBSERVED]"));
        assertTrue(text.contains("Missing evidence: No terminal protocol timing observation supplied."));
        assertTrue(text.contains("No GPT explanation is generated"));
        assertFalse(text.contains("private client internals"));
        assertFalse(text.contains("Observation:"));
        assertEquals(0, service.starts);
    }

    @Test
    void invalidAnalysisReferencesCannotReplaceOriginalOrMissingEvidence() {
        Fixture fixture = SyntheticExplanationFixtures.failure();
        service.add(fixture);
        var invalidReferences = List.of(
                new ExplanationDraft.Reference("unique_payload_bytes_acked", new BigDecimal("999"), "bytes"),
                new ExplanationDraft.Reference("unique_payload_bytes_acked", new BigDecimal("2048"), "packets"),
                new ExplanationDraft.Reference("duration_ms", BigDecimal.ZERO, "ms"),
                new ExplanationDraft.Reference("invented_loss", new BigDecimal("20"), "percent"));
        for (var reference : invalidReferences) {
            var draft = new ExplanationDraft(fixture.evidence().runId(), fixture.evidence().transferId(),
                    List.of(new ExplanationDraft.Observation("Unsupported model narrative", List.of(reference))),
                    List.of(), List.of("Synthetic draft"));
            use(flow(fixture, request -> draft));

            String text = command(explain(fixture.evidence().runId()));

            assertTrue(text.contains("EXPLANATION_REJECTED:"), text);
            assertTrue(text.contains("unique_payload_bytes_acked=2048 bytes [OBSERVED]"));
            assertTrue(text.contains("duration_ms=unavailable ms [OBSERVED]"));
            assertTrue(text.contains("Missing evidence: No terminal protocol timing observation supplied."));
            assertFalse(text.contains("Unsupported model narrative"));
            assertFalse(text.contains("unique_payload_bytes_acked=999"));
            assertFalse(text.contains("duration_ms=0"));
            assertFalse(text.contains("invented_loss"));
        }
        assertEquals(0, service.starts);
    }

    @Test
    void mismatchedProviderIdentityIsRejectedBeforeValuesOrAnalysisAreDisplayed() {
        Fixture selected = SyntheticExplanationFixtures.baseline();
        Fixture wrong = selected.withIdentity(selected.evidence().runId(), UUID.randomUUID(),
                selected.evidence().protocolTransferId());
        service.add(selected);
        use(new ExplanationFlow(runId -> Optional.of(wrong.evidence()), request -> {
            fail("Mismatched evidence must not reach analysis");
            return wrong.draft();
        }));

        String text = command(explain(selected.evidence().runId()));

        assertTrue(text.contains("EVIDENCE_REJECTED:"), text);
        assertFalse(text.contains("Evidence [SYNTHETIC]"));
        assertFalse(text.contains("duration_ms=1000"));
        assertFalse(text.contains(wrong.evidence().transferId().toString()));
        assertEquals(0, service.starts);
    }

    private void use(ExplanationFlow flow) {
        use(request -> {
            fail("Direct explanation must not call the command interpreter");
            return null;
        }, flow);
    }

    private void use(GptClient interpreter, ExplanationFlow flow) {
        cli = new TransferCli(service, configuration, new StringReader(""), new PrintWriter(output),
                interpreter, flow);
    }

    private static ExplanationFlow flow(Fixture fixture, ExplanationClient client) {
        return new ExplanationFlow(new SyntheticSummaryProvider(List.of(fixture.evidence())), client);
    }

    private String command(String command) {
        output.getBuffer().setLength(0);
        assertTrue(cli.handleLine(command));
        return output.toString();
    }

    private static String explain(UUID runId) {
        return "explain {\"run_id\":" + (runId == null ? "null" : "\"" + runId + "\"")
                + ",\"question\":\"What happened?\"}";
    }

    private static TransferSummary asReal(TransferSummary summary) {
        TransferSnapshot original = summary.finalSnapshot();
        var real = new TransferSnapshot(original.transferId(), original.runId(), original.protocolTransferId(),
                original.state(), original.snapshotAt(), EvidenceSource.REAL, original.metrics(), original.error());
        return new TransferSummary(summary.request(), real, summary.integrity(), "Real frozen outcome; metrics unavailable");
    }

    /** In-memory history only. Starts are permitted solely in the current/last selection test. */
    private static final class FixtureService implements TransferService {
        private final Map<UUID, TransferSummary> summaries = new HashMap<>();
        private final Map<UUID, TransferSnapshot> snapshots = new HashMap<>();
        private final Map<UUID, Fixture> fixtures = new HashMap<>();
        private boolean allowStarts;
        private int starts;
        private UUID lastStartedRun;

        private void add(Fixture fixture) {
            fixtures.put(fixture.evidence().runId(), fixture);
            add(fixture.selected());
        }

        private void add(TransferSummary summary) {
            TransferSnapshot snapshot = summary.finalSnapshot();
            summaries.put(snapshot.runId(), summary);
            snapshots.put(snapshot.transferId(), snapshot);
        }

        private void finish(UUID runId) {
            add(summaries.get(runId));
        }

        @Override
        public TransferStart start(TransferRequest request) {
            starts++;
            assertTrue(allowStarts, "Explanation must never start transfer execution");
            Fixture fixture = SyntheticExplanationFixtures.baseline()
                    .withIdentity(UUID.randomUUID(), request.transferId(), UUID.randomUUID());
            add(fixture);
            TransferSnapshot terminal = fixture.selected().finalSnapshot();
            snapshots.put(terminal.transferId(), new TransferSnapshot(terminal.transferId(), terminal.runId(),
                    terminal.protocolTransferId(), TransferState.RUNNING, terminal.snapshotAt(),
                    terminal.evidenceSource(), terminal.metrics(), null));
            lastStartedRun = terminal.runId();
            return new TransferStart(terminal.transferId(), terminal.runId(), terminal.protocolTransferId(),
                    terminal.snapshotAt(), terminal.evidenceSource());
        }

        @Override
        public TransferSnapshot status(UUID transferId) {
            TransferSnapshot snapshot = snapshots.get(transferId);
            if (snapshot == null) {
                throw new TransferServiceException(TransferError.Code.UNKNOWN_TRANSFER, "Unknown synthetic transfer");
            }
            return snapshot;
        }

        @Override
        public TransferSummary summary(UUID runId) {
            TransferSummary summary = summaries.get(runId);
            if (summary == null) {
                throw new TransferServiceException(TransferError.Code.UNKNOWN_TRANSFER, "Unknown synthetic run");
            }
            if (status(summary.finalSnapshot().transferId()).state() == TransferState.RUNNING) {
                throw new TransferServiceException(TransferError.Code.SUMMARY_NOT_READY, "Synthetic run is still active");
            }
            return summary;
        }
    }
}
