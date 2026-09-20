package nettransfer.explanation;

import nettransfer.control.EvidenceSource;
import nettransfer.control.TransferState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static nettransfer.explanation.SyntheticExplanationFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class ExplanationBoundaryTest {
    @Test
    void missingEvidenceRemainsNullWithItsReasonWhileAnObservedZeroRemainsZero() {
        var absent = missing().evidence().fields().get(0);
        var zero = baseline().evidence().fields().get(1);
        assertNull(absent.value());
        assertEquals("No protocol timing observations supplied.", absent.unavailableReason());
        assertEquals(BigDecimal.ZERO, zero.value());
        assertNull(zero.unavailableReason());
        assertThrows(IllegalArgumentException.class,
                () -> missingField("duration_ms", "ms", "Draft definition", null));
        assertThrows(IllegalArgumentException.class,
                () -> missingField("duration_ms", "ms", "Draft definition", " "));
        assertThrows(IllegalArgumentException.class, () -> new RecordedSummary.Field("duration_ms",
                BigDecimal.ZERO, "ms", RecordedSummary.Kind.OBSERVED, "Draft definition", "Unavailable"));
    }

    @Test
    void fixtureKeepsImpairmentSettingsSeparateFromObservedPacketCounts() {
        var fields = loss().evidence().fields();
        assertEquals(RecordedSummary.Kind.CONFIGURED, fields.get(0).kind());
        assertEquals("configured_loss_percent", fields.get(0).id());
        assertEquals(RecordedSummary.Kind.OBSERVED, fields.get(1).kind());
        assertEquals("retransmitted_packets", fields.get(1).id());
        assertNull(fields.get(2).value());
    }

    @Test
    void providerAcceptsOnlyExplicitSyntheticFixturesWithSupportedDraftDefinitions() {
        var original = baseline().evidence();
        var real = copy(original, EvidenceSource.REAL, original.definitionVersion(), original.fields());
        var wrongVersion = copy(original, EvidenceSource.SYNTHETIC, "agreed-contract-does-not-exist", original.fields());
        assertThrows(IllegalArgumentException.class, () -> new SyntheticSummaryProvider(List.of(real)));
        assertThrows(IllegalArgumentException.class, () -> new SyntheticSummaryProvider(List.of(wrongVersion)));
        assertThrows(IllegalArgumentException.class, () -> new SyntheticSummaryProvider(List.of(original, original)));
    }

    @Test
    void providerFreezesFixtureListAndSelectsOnlyTheRequestedRun() {
        var baseline = baseline().evidence();
        var loss = loss().evidence();
        var fixtures = new ArrayList<>(List.of(baseline, loss));
        var provider = new SyntheticSummaryProvider(fixtures);
        fixtures.clear();

        assertSame(baseline, provider.load(baseline.runId()).orElseThrow());
        assertSame(loss, provider.load(loss.runId()).orElseThrow());
        assertTrue(provider.load(UUID.randomUUID()).isEmpty());
        assertTrue(SummaryProvider.unavailable().load(baseline.runId()).isEmpty());
    }

    @Test
    void evidenceFreezesFieldsAndRejectsDuplicateAmbiguousIds() {
        var original = baseline().evidence();
        var fields = new ArrayList<>(original.fields());
        var frozen = copy(original, original.source(), original.definitionVersion(), fields);
        fields.clear();
        assertEquals(original.fields(), frozen.fields());
        assertThrows(UnsupportedOperationException.class, () -> frozen.fields().clear());
        assertThrows(IllegalArgumentException.class, () -> copy(original, original.source(),
                original.definitionVersion(), List.of(original.fields().get(0), original.fields().get(0))));
    }

    @Test
    void evidenceFieldCountIsBounded() {
        var original = baseline().evidence();
        var fields = IntStream.range(0, RecordedSummary.MAX_FIELDS + 1)
                .mapToObj(i -> observed("field_" + i, "1", "count", "Draft definition"))
                .toList();
        assertThrows(IllegalArgumentException.class,
                () -> copy(original, original.source(), original.definitionVersion(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> copy(original, original.source(), original.definitionVersion(), fields));
        assertEquals(RecordedSummary.MAX_FIELDS, copy(original, original.source(), original.definitionVersion(),
                fields.subList(0, RecordedSummary.MAX_FIELDS)).fields().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1000000000000000000000000", "1E+13", "0.0000000000001"})
    void rejectsNegativeOrUnboundedNumericEvidenceAndReferences(String value) {
        assertThrows(IllegalArgumentException.class, () -> observed("count", value, "count", "Draft definition"));
        assertThrows(IllegalArgumentException.class, () -> ref("count", value, "count"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "UPPERCASE", "field with spaces", "../file", "0count"})
    void rejectsFieldIdsUnsuitableForUnambiguousReferences(String id) {
        assertThrows(IllegalArgumentException.class, () -> observed(id, "1", "count", "Draft definition"));
    }

    @Test
    void textBoundsRejectControlCharactersAndOversizedEvidence() {
        assertThrows(IllegalArgumentException.class, () -> observed("count", "1", "x".repeat(65), "Draft"));
        assertThrows(IllegalArgumentException.class,
                () -> observed("count", "1", "count", "x".repeat(401)));
        assertThrows(IllegalArgumentException.class, () -> observed("count", "1", "count", "\u001b[31m"));
        assertThrows(IllegalArgumentException.class,
                () -> missingField("count", "count", "Draft", "x".repeat(401)));
        var original = baseline().evidence();
        assertThrows(IllegalArgumentException.class, () -> new RecordedSummary(original.runId(),
                original.transferId(), null, original.source(), original.capturedAt(), original.definitionVersion(),
                "x".repeat(161), original.fields()));
    }

    @Test
    void draftFreezesObservationsReferencesHypothesesAndLimitations() {
        var fixture = baseline();
        var references = new ArrayList<>(List.of(ref("duration_ms", "1000", "ms")));
        var observation = new ExplanationDraft.Observation("Supplied synthetic duration", references);
        var observations = new ArrayList<>(List.of(observation));
        var hypotheses = new ArrayList<>(List.of("Possible cause requires independent evidence."));
        var limitations = new ArrayList<>(List.of("Synthetic test data."));
        var draft = new ExplanationDraft(fixture.evidence().runId(), fixture.evidence().transferId(),
                observations, hypotheses, limitations);
        references.clear();
        observations.clear();
        hypotheses.clear();
        limitations.clear();

        assertEquals(1, draft.observations().size());
        assertEquals(1, draft.observations().get(0).references().size());
        assertEquals(1, draft.hypotheses().size());
        assertEquals(1, draft.limitations().size());
        assertThrows(UnsupportedOperationException.class, () -> draft.observations().clear());
        assertThrows(UnsupportedOperationException.class, () -> draft.observations().get(0).references().clear());
        assertThrows(UnsupportedOperationException.class, () -> draft.hypotheses().clear());
        assertThrows(UnsupportedOperationException.class, () -> draft.limitations().clear());
    }

    @Test
    void observationsRequireReferencesAndBoundedText() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExplanationDraft.Observation("Unsupported assertion", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ExplanationDraft.Observation("Many citations",
                java.util.Collections.nCopies(9, ref("duration_ms", "1000", "ms"))));
        assertThrows(IllegalArgumentException.class, () -> new ExplanationDraft.Observation("x".repeat(601),
                List.of(ref("duration_ms", "1000", "ms"))));
    }

    @Test
    void draftRequiresLimitationsAndBoundsEverySection() {
        var fixture = baseline();
        var original = fixture.draft();
        assertThrows(IllegalArgumentException.class, () -> new ExplanationDraft(original.runId(), original.transferId(),
                original.observations(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ExplanationDraft(original.runId(), original.transferId(),
                java.util.Collections.nCopies(9, original.observations().get(0)), List.of(), original.limitations()));
        assertThrows(IllegalArgumentException.class, () -> new ExplanationDraft(original.runId(), original.transferId(),
                original.observations(), java.util.Collections.nCopies(5, "Hypothesis"), original.limitations()));
        assertThrows(IllegalArgumentException.class, () -> new ExplanationDraft(original.runId(), original.transferId(),
                original.observations(), List.of(), java.util.Collections.nCopies(9, "Limitation")));
    }

    @Test
    void requestsRequireTerminalStateAndBoundedQuestionWithoutChangingEvidence() {
        var fixture = baseline();
        assertThrows(IllegalArgumentException.class, () -> new ExplanationRequest(UUID.randomUUID(), "Explain",
                fixture.evidence(), TransferState.RUNNING, fixture.integrity()));
        assertThrows(IllegalArgumentException.class, () -> new ExplanationRequest(UUID.randomUUID(), " ",
                fixture.evidence(), fixture.state(), fixture.integrity()));
        assertThrows(IllegalArgumentException.class, () -> new ExplanationRequest(UUID.randomUUID(), "x".repeat(2001),
                fixture.evidence(), fixture.state(), fixture.integrity()));
        var request = new ExplanationRequest(UUID.randomUUID(), "x".repeat(2000), fixture.evidence(),
                fixture.state(), fixture.integrity());
        assertSame(fixture.evidence(), request.evidence());
    }

    @Test
    void stubExhaustionIsExplicitAndRequestHistoryIsImmutable() {
        var fixture = baseline();
        var scripted = new ArrayList<>(List.of(fixture.draft()));
        var client = new StubExplanationClient(scripted);
        scripted.clear();
        var request = new ExplanationRequest(UUID.randomUUID(), "Explain", fixture.evidence(),
                fixture.state(), fixture.integrity());

        assertSame(fixture.draft(), client.explain(request));
        var firstHistory = client.requests();
        assertThrows(IllegalStateException.class, () -> client.explain(request));
        assertEquals(2, client.requests().size());
        assertEquals(1, firstHistory.size());
        assertThrows(UnsupportedOperationException.class, () -> firstHistory.clear());
    }

    private static RecordedSummary copy(RecordedSummary original, EvidenceSource source, String version,
                                         List<RecordedSummary.Field> fields) {
        return new RecordedSummary(original.runId(), original.transferId(), original.protocolTransferId(), source,
                original.capturedAt(), version, original.label(), fields);
    }
}
