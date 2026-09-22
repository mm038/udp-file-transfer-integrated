package nettransfer.metrics;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MetricsCollectorTest {
    @Test
    void snapshotStartsUnknownAndDoesNotFabricateReceiverEvidence() {
        AtomicLong clock = new AtomicLong(10);
        MetricsCollector collector = collector(clock);

        TransferMetrics snapshot = collector.snapshot();
        assertAll(
                () -> assertEquals("run-" + System.identityHashCode(clock), snapshot.getRunId()),
                () -> assertEquals(1_024L, snapshot.getChunkSizeBytes()),
                () -> assertEquals(3L, snapshot.getWindowPackets()),
                () -> assertEquals(200L, snapshot.getTimeoutMs()),
                () -> assertNull(snapshot.getPacketsSent()),
                () -> assertNull(snapshot.getRetransmissions()),
                () -> assertNull(snapshot.getAcksReceived()),
                () -> assertNull(snapshot.getPacketsAcked()),
                () -> assertNull(snapshot.getPacketsTimedOut()),
                () -> assertNull(snapshot.getTransferTimeSec()),
                () -> assertNull(snapshot.getTransferSuccess()),
                () -> assertNull(snapshot.getPayloadBytesDelivered()),
                () -> assertNull(snapshot.getPacketsReceived()),
                () -> assertNull(snapshot.getPacketsDropped()),
                () -> assertNull(snapshot.getIntegrityVerified()),
                () -> assertNull(snapshot.getProtocolOverheadBytes()));
    }

    @Test
    void countsAttemptsResendsCumulativeAckProgressAndOneTimeoutTrigger() {
        AtomicLong clock = new AtomicLong(1_000);
        MetricsCollector collector = collector(clock);
        collector.observeStartAttempt();
        collector.observeStartAcknowledged();
        for (int i = 0; i < 3; i++) {
            collector.observeDataSendAttempt(false);
        }
        for (int i = 0; i < 5; i++) {
            collector.observeDataSendAttempt(true);
        }
        collector.observeDataAckArrival();
        collector.observePacketsAcknowledged(3);
        collector.observeDataAckArrival();
        collector.observePacketsAcknowledged(0);
        collector.observeDataTimeout();
        collector.observeRecoveryDecision();
        clock.set(2_000_001_000L);
        collector.observeTerminalOutcome(true, null);

        TransferMetrics snapshot = collector.snapshot();
        assertAll(
                () -> assertEquals(8L, snapshot.getPacketsSent()),
                () -> assertEquals(5L, snapshot.getRetransmissions()),
                () -> assertEquals(5.0 / 8.0, snapshot.getRetransmissionRatio()),
                () -> assertEquals(2L, snapshot.getAcksReceived()),
                () -> assertEquals(3L, snapshot.getPacketsAcked()),
                () -> assertEquals(1L, snapshot.getPacketsTimedOut()),
                () -> assertEquals(2.0, snapshot.getTransferTimeSec()),
                () -> assertTrue(snapshot.getTransferSuccess()),
                () -> assertNull(snapshot.getFailureReason()),
                () -> assertEquals(1L, collector.senderObservations().recoveryRounds()));
    }

    @Test
    void startRetriesDoNotResetDurationAndFinishActivityIsInsideIt() {
        AtomicLong clock = new AtomicLong(100L);
        MetricsCollector collector = collector(clock);
        collector.observeStartAttempt();
        clock.set(500L);
        collector.observeStartAttempt();
        collector.observeStartAcknowledged();
        clock.set(800L);
        collector.observeFinishAttempt();
        clock.set(1_100L);
        collector.observeFinishAcknowledged();
        collector.observeTerminalOutcome(true, null);

        assertAll(
                () -> assertEquals(0.000001, collector.snapshot().getTransferTimeSec()),
                () -> assertEquals(2L, collector.senderObservations().startAttempts()),
                () -> assertTrue(collector.senderObservations().startAcknowledged()),
                () -> assertEquals(1L, collector.senderObservations().finishAttempts()),
                () -> assertTrue(collector.senderObservations().finishAcknowledged()));
    }

    @Test
    void terminalFailureRetainsPartialMeasurementsAndExactReason() {
        AtomicLong clock = new AtomicLong(20L);
        MetricsCollector collector = collector(clock);
        collector.observeStartAttempt();
        collector.observeDataSendAttempt(false);
        collector.observeDataAckArrival();
        collector.observePacketsAcknowledged(1);
        collector.observeDataSendAttempt(false);
        clock.set(120L);
        collector.observeTerminalOutcome(false, "Retry limit (5) exceeded -- transfer failed");

        TransferMetrics snapshot = collector.snapshot();
        assertAll(
                () -> assertFalse(snapshot.getTransferSuccess()),
                () -> assertEquals(
                        "Retry limit (5) exceeded -- transfer failed", snapshot.getFailureReason()),
                () -> assertEquals(2L, snapshot.getPacketsSent()),
                () -> assertEquals(1L, snapshot.getPacketsAcked()),
                () -> assertEquals(0.0000001, snapshot.getTransferTimeSec()));
    }

    @Test
    void failureBeforeFirstStartLeavesDurationAndCountersUnavailable() {
        MetricsCollector collector = collector(new AtomicLong(50L));
        collector.observeError();
        collector.observeTerminalOutcome(false, "IOException: missing file");

        TransferMetrics snapshot = collector.snapshot();
        assertAll(
                () -> assertFalse(snapshot.getTransferSuccess()),
                () -> assertEquals("IOException: missing file", snapshot.getFailureReason()),
                () -> assertNull(snapshot.getTransferTimeSec()),
                () -> assertNull(snapshot.getPacketsSent()),
                () -> assertEquals(1L, collector.senderObservations().errorsObserved()));
    }

    @Test
    void emptyDataPhaseHasObservedZerosAndUnavailableRatio() {
        AtomicLong clock = new AtomicLong(5L);
        MetricsCollector collector = collector(clock);
        collector.observeStartAttempt();
        collector.observeStartAcknowledged();
        collector.observeFinishAttempt();
        collector.observeFinishAcknowledged();
        clock.set(15L);
        collector.observeTerminalOutcome(true, null);

        TransferMetrics snapshot = collector.snapshot();
        assertAll(
                () -> assertEquals(0L, snapshot.getPacketsSent()),
                () -> assertEquals(0L, snapshot.getRetransmissions()),
                () -> assertEquals(0L, snapshot.getAcksReceived()),
                () -> assertEquals(0L, snapshot.getPacketsAcked()),
                () -> assertEquals(0L, snapshot.getPacketsTimedOut()),
                () -> assertNull(snapshot.getRetransmissionRatio()));
    }

    @Test
    void collectorsAreIsolatedAndSnapshotsCannotMutateState() {
        MetricsCollector first = collector(new AtomicLong(1L));
        MetricsCollector second = collector(new AtomicLong(1L));
        first.observeStartAttempt();
        first.observeDataSendAttempt(false);

        TransferMetrics firstSnapshot = first.snapshot();
        assertAll(
                () -> assertEquals(1L, firstSnapshot.getPacketsSent()),
                () -> assertNull(second.snapshot().getPacketsSent()),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> firstSnapshot.getUnavailableReasons().put("x", "y")));
    }

    @Test
    void receiverSnapshotActivatesZerosAndKeepsLocalOutcomeSeparate() {
        TransferContext context = TransferContext.builder(TransferContext.Endpoint.RECEIVER)
                .runId("receiver-run")
                .build();
        MetricsCollector collector = new MetricsCollector(context, new AtomicLong(1L)::get);

        TransferMetrics beforeStart = collector.snapshot();
        assertAll(
                () -> assertNull(beforeStart.getPacketsReceived()),
                () -> assertNull(beforeStart.getPacketsDuplicated()),
                () -> assertNull(beforeStart.getPayloadBytesDelivered()),
                () -> assertNull(beforeStart.getTransferSuccess()));

        collector.observeReceiverStartArrival();
        collector.observeReceiverStartAccepted("127.0.0.1", 9000);
        collector.observeReceiverDataArrival();
        collector.observeReceiverDataAccepted();
        collector.observeReceiverPayloadWritten(3);
        collector.observeReceiverDataArrival();
        collector.observeReceiverDataDuplicate();
        collector.observeReceiverIntegrityVerification(false);
        collector.observeReceiverFinishAckAttempt();
        collector.observeReceiverTerminalOutcome(false, "SHA-256 mismatch");

        TransferMetrics snapshot = collector.snapshot();
        MetricsCollector.ReceiverObservations observations = collector.receiverObservations();
        assertAll(
                () -> assertEquals(2L, snapshot.getPacketsReceived()),
                () -> assertEquals(1L, snapshot.getPacketsDuplicated()),
                () -> assertEquals(3L, snapshot.getPayloadBytesDelivered()),
                () -> assertFalse(snapshot.getIntegrityVerified()),
                () -> assertEquals("RECEIVER", snapshot.getIntegrityEvidenceSource()),
                () -> assertNull(snapshot.getTransferSuccess()),
                () -> assertNull(snapshot.getTransferTimeSec()),
                () -> assertNull(snapshot.getPacketsSent()),
                () -> assertFalse(observations.localSuccess()),
                () -> assertEquals("SHA-256 mismatch", observations.terminalReason()),
                () -> assertEquals("127.0.0.1", observations.establishedPeerAddress()));
    }

    @Test
    void receiverCollectorsDoNotShareCounters() {
        MetricsCollector first = new MetricsCollector(
                TransferContext.builder(TransferContext.Endpoint.RECEIVER).runId("receiver-one").build());
        MetricsCollector second = new MetricsCollector(
                TransferContext.builder(TransferContext.Endpoint.RECEIVER).runId("receiver-two").build());
        first.observeReceiverStartAccepted("127.0.0.1", 9000);
        first.observeReceiverDataArrival();

        assertAll(
                () -> assertEquals(1L, first.snapshot().getPacketsReceived()),
                () -> assertNull(second.snapshot().getPacketsReceived()),
                () -> assertNotEquals(first.snapshot().getRunId(), second.snapshot().getRunId()));
    }

    @Test
    void recordsOnlyUnambiguousNeverRetransmittedRttSamples() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        MetricsCollector collector = collector(clock);
        assertNull(collector.snapshot().getRttSampleCount());

        collector.observeOriginalDataEmission(0);
        clock.set(3_000_000L);
        collector.observeDataAckProgress(0, 1);
        collector.observeDataAckProgress(1, 1); // duplicate ACK progress

        clock.set(4_000_000L);
        collector.observeOriginalDataEmission(1);
        collector.observeRetransmittedDataEmission(1);
        clock.set(9_000_000L);
        collector.observeDataAckProgress(1, 2);

        clock.set(10_000_000L);
        collector.observeOriginalDataEmission(2);
        collector.observeOriginalDataEmission(3);
        clock.set(15_000_000L);
        collector.observeDataAckProgress(2, 4); // ambiguous cumulative progress

        TransferMetrics snapshot = collector.snapshot();
        assertAll(
                () -> assertEquals(1L, snapshot.getRttSampleCount()),
                () -> assertEquals(2.0, snapshot.getRttMeanMs()),
                () -> assertEquals(2.0, snapshot.getRttP95Ms()));
    }

    @Test
    void activeRttSamplingWithOnlyExcludedSamplesReportsObservedZero() {
        AtomicLong clock = new AtomicLong(1_000L);
        MetricsCollector collector = collector(clock);
        collector.observeOriginalDataEmission(0);
        collector.observeRetransmittedDataEmission(0);
        clock.set(2_000L);
        collector.observeDataAckProgress(0, 1);

        assertAll(
                () -> assertEquals(0L, collector.snapshot().getRttSampleCount()),
                () -> assertNull(collector.snapshot().getRttMeanMs()),
                () -> assertNull(collector.snapshot().getRttP95Ms()));
    }

    @Test
    void endpointEmissionEvidenceIsLocalOverflowSafeAndExplicitlyCompleted() {
        MetricsCollector collector = collector(new AtomicLong(1L));
        assertNull(collector.endpointEmissionObservations().localUdpPayloadBytesEmitted());
        collector.beginEndpointEmissionAccounting();
        collector.observeUdpPayloadEmitted(10);
        collector.observeUdpPayloadEmitted(5);

        assertAll(
                () -> assertEquals(15L,
                        collector.endpointEmissionObservations().localUdpPayloadBytesEmitted()),
                () -> assertFalse(collector.endpointEmissionObservations().accountingComplete()),
                () -> assertNull(collector.snapshot().getUdpPayloadBytesEmitted()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> collector.observeUdpPayloadEmitted(-1)));

        collector.completeEndpointEmissionAccounting();
        assertTrue(collector.endpointEmissionObservations().accountingComplete());
        assertThrows(IllegalStateException.class, () -> collector.observeUdpPayloadEmitted(1));
    }

    private static MetricsCollector collector(AtomicLong clock) {
        TransferContext context = TransferContext.builder(TransferContext.Endpoint.SENDER)
                .runId("run-" + System.identityHashCode(clock))
                .configuration(TransferConfiguration.builder()
                        .chunkSizeBytes(1_024L)
                        .windowPackets(3L)
                        .timeoutMs(200L)
                        .retryLimit(5L)
                        .build())
                .build();
        return new MetricsCollector(context, clock::get);
    }
}
