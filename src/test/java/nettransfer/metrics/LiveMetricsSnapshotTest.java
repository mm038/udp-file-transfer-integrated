package nettransfer.metrics;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class LiveMetricsSnapshotTest {
    @Test
    void senderSnapshotIsAtomicImmutableAndTracksLifecycleTimingAndExactAckBytes() {
        AtomicLong clock = new AtomicLong(100L);
        MetricsCollector collector = new MetricsCollector(
                TransferContext.builder(TransferContext.Endpoint.SENDER)
                        .runId("live-sender")
                        .configuration(TransferConfiguration.builder().chunkSizeBytes(2L).build())
                        .build(), clock::get);

        LiveMetricsSnapshot initial = collector.liveSnapshot();
        assertAll(
                () -> assertEquals(LiveMetricsSnapshot.LifecycleState.NOT_STARTED,
                        initial.lifecycleState()),
                () -> assertTrue(initial.isProvisional()),
                () -> assertNull(initial.metrics().getPacketsSent()),
                () -> assertNull(initial.endpointEmission().localUdpPayloadBytesEmitted()),
                () -> assertEquals(initial.capturedAt(), initial.metrics().getCaptureTimestamp()),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> initial.metrics().getUnavailableReasons().put("x", "y")));

        collector.observeEndpointAwaitingStart();
        collector.beginEndpointEmissionAccounting();
        collector.observeStartAttempt();
        collector.observeUdpPayloadEmitted(10);
        clock.set(200L);
        LiveMetricsSnapshot awaitingStart = collector.liveSnapshot();
        assertAll(
                () -> assertEquals(LiveMetricsSnapshot.LifecycleState.AWAITING_START,
                        awaitingStart.lifecycleState()),
                () -> assertEquals(0L, awaitingStart.metrics().getPacketsSent()),
                () -> assertEquals(10L, awaitingStart.endpointEmission().localUdpPayloadBytesEmitted()),
                () -> assertFalse(awaitingStart.endpointEmission().accountingComplete()),
                () -> assertEquals(0.0000001, awaitingStart.liveElapsedTimeSec()));

        collector.observeStartAcknowledged();
        collector.observeDataSendAttempt(false);
        collector.observeOriginalDataEmission(0, 2);
        clock.set(1_000_200L);
        collector.observeDataAckArrival();
        collector.observeDataAckProgress(0, 1);
        collector.observePacketsAcknowledged(1);
        LiveMetricsSnapshot transferring = collector.liveSnapshot();
        assertAll(
                () -> assertEquals(LiveMetricsSnapshot.LifecycleState.TRANSFERRING,
                        transferring.lifecycleState()),
                () -> assertEquals(2L, transferring.senderAcknowledgedPayloadBytes()),
                () -> assertNotNull(transferring.senderAcknowledgedRateMbps()),
                () -> assertNull(transferring.metrics().getPayloadBytesDelivered()),
                () -> assertEquals(1L, transferring.metrics().getRttSampleCount()),
                () -> assertNotNull(transferring.senderObservations()),
                () -> assertNull(transferring.receiverObservations()));

        collector.observeDataSendAttempt(true);
        collector.observeDataAckArrival();
        collector.observeDataAckProgress(1, 1);
        LiveMetricsSnapshot afterDuplicateAck = collector.liveSnapshot();
        assertAll(
                () -> assertEquals(2L, afterDuplicateAck.senderAcknowledgedPayloadBytes()),
                () -> assertEquals(1L, afterDuplicateAck.metrics().getPacketsAcked()),
                () -> assertEquals(1L, afterDuplicateAck.metrics().getRetransmissions()),
                () -> assertEquals(2L, afterDuplicateAck.metrics().getAcksReceived()));

        collector.observeSenderAwaitingFinish();
        assertEquals(LiveMetricsSnapshot.LifecycleState.AWAITING_FINISH,
                collector.liveSnapshot().lifecycleState());
        collector.observeTerminalOutcome(true, null);
        clock.set(9_000_000L);
        LiveMetricsSnapshot terminal = collector.liveSnapshot();
        assertAll(
                () -> assertEquals(LiveMetricsSnapshot.LifecycleState.SUCCEEDED,
                        terminal.lifecycleState()),
                () -> assertTrue(terminal.isTerminal()),
                () -> assertFalse(terminal.isProvisional()),
                () -> assertTrue(terminal.metrics().getTransferSuccess()),
                () -> assertTrue(terminal.endpointEmission().accountingComplete()),
                () -> assertNotNull(terminal.metrics().getFinalizationTimestamp()),
                () -> assertEquals(terminal.metrics().getTransferTimeSec(), terminal.liveElapsedTimeSec()));
    }

    @Test
    void receiverLifecycleKeepsLocalCompletionSeparateFromSenderConfirmation() {
        MetricsCollector collector = new MetricsCollector(
                TransferContext.builder(TransferContext.Endpoint.RECEIVER).runId("live-receiver").build());
        collector.observeEndpointAwaitingStart();
        collector.beginEndpointEmissionAccounting();
        collector.observeReceiverStartArrival();
        collector.observeReceiverStartAccepted("127.0.0.1", 9000);
        assertEquals(LiveMetricsSnapshot.LifecycleState.TRANSFERRING,
                collector.liveSnapshot().lifecycleState());

        collector.observeReceiverFinishArrival();
        assertEquals(LiveMetricsSnapshot.LifecycleState.VERIFYING,
                collector.liveSnapshot().lifecycleState());
        collector.observeReceiverIntegrityVerification(true);
        collector.observeReceiverCompletionRecovery();
        LiveMetricsSnapshot recovery = collector.liveSnapshot();
        assertAll(
                () -> assertEquals(LiveMetricsSnapshot.LifecycleState.COMPLETION_RECOVERY,
                        recovery.lifecycleState()),
                () -> assertTrue(recovery.isProvisional()),
                () -> assertTrue(recovery.metrics().getIntegrityVerified()),
                () -> assertNull(recovery.metrics().getTransferSuccess()),
                () -> assertNull(recovery.liveElapsedTimeSec()),
                () -> assertNull(recovery.senderObservations()),
                () -> assertNotNull(recovery.receiverObservations()));

        collector.observeReceiverTerminalOutcome(true, null);
        LiveMetricsSnapshot terminal = collector.liveSnapshot();
        assertAll(
                () -> assertEquals(LiveMetricsSnapshot.LifecycleState.SUCCEEDED,
                        terminal.lifecycleState()),
                () -> assertTrue(terminal.receiverObservations().localSuccess()),
                () -> assertNull(terminal.metrics().getTransferSuccess()),
                () -> assertTrue(terminal.endpointEmission().accountingComplete()));
    }

    @Test
    void separateCollectorsRetainIndependentSnapshotsAndTerminalFailure() {
        MetricsCollector first = new MetricsCollector(
                TransferContext.builder(TransferContext.Endpoint.SENDER).runId("first").build());
        MetricsCollector second = new MetricsCollector(
                TransferContext.builder(TransferContext.Endpoint.SENDER).runId("second").build());
        first.observeEndpointAwaitingStart();
        first.observeStartAttempt();
        first.observeTerminalOutcome(false, "failure");

        assertAll(
                () -> assertEquals(LiveMetricsSnapshot.LifecycleState.FAILED,
                        first.liveSnapshot().lifecycleState()),
                () -> assertEquals("failure", first.liveSnapshot().metrics().getFailureReason()),
                () -> assertEquals(LiveMetricsSnapshot.LifecycleState.NOT_STARTED,
                        second.liveSnapshot().lifecycleState()),
                () -> assertNotEquals(first.liveSnapshot().context().getRunId(),
                        second.liveSnapshot().context().getRunId()));
    }

    @Test
    void emptyPayloadAcknowledgmentIsObservedAsExactZeroBytes() {
        MetricsCollector collector = new MetricsCollector(
                TransferContext.builder(TransferContext.Endpoint.SENDER).build());
        collector.observeEndpointAwaitingStart();
        collector.observeStartAttempt();
        collector.observeStartAcknowledged();
        collector.observeOriginalDataEmission(0, 0);
        collector.observeDataAckProgress(0, 1);

        assertEquals(0L, collector.liveSnapshot().senderAcknowledgedPayloadBytes());
    }
}
