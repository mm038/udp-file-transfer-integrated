package nettransfer.metrics;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TransferMetricsTest {
    @Test
    void representsEveryMetricAndSupportingMetadata() {
        Instant captured = Instant.parse("2026-09-21T08:00:00Z");
        Instant finalized = Instant.parse("2026-09-21T08:00:02Z");

        TransferMetrics metrics = TransferMetrics.builder()
                .experimentId("experiment-1")
                .fileSizeBytes(1_000L)
                .payloadBytesDelivered(900L)
                .transferTimeSec(2.0)
                .throughputMbps(0.0036)
                .transferSuccess(false)
                .integrityVerified(false)
                .failureReason("retry limit reached")
                .packetsSent(12L)
                .packetsReceived(9L)
                .packetsDropped(2L)
                .retransmissions(2L)
                .acksReceived(8L)
                .packetsAcked(8L)
                .packetsTimedOut(2L)
                .packetsDuplicated(1L)
                .retransmissionRatio(2.0 / 12.0)
                .udpPayloadBytesEmitted(1_200L)
                .protocolOverheadBytes(300L)
                .protocolOverheadRatio(0.25)
                .rttSampleCount(8L)
                .rttMeanMs(4.5)
                .rttP95Ms(7.0)
                .chunkSizeBytes(100L)
                .windowBytesRequested(400L)
                .windowPackets(4L)
                .timeoutMs(500L)
                .retryLimit(5L)
                .packetLossRate(10.0)
                .delayMs(3.5)
                .scenario("synthetic-loss")
                .impairmentSeed(42L)
                .schemaVersion("1")
                .metricDefinitionVersion("stage-11-b1")
                .runId("run-1")
                .applicationTransferId("application-transfer-1")
                .protocolTransferId("protocol-transfer-7")
                .evidenceSource(TransferMetrics.EvidenceSource.SYNTHETIC)
                .endpointAttribution("sender-and-receiver")
                .fileAttribution("fixture.bin")
                .captureTimestamp(captured)
                .finalizationTimestamp(finalized)
                .integrityEvidenceSource("receiver-sha-256")
                .unavailableReason("receiver_terminal_status", "not observed")
                .build();

        assertAll(
                () -> assertEquals("experiment-1", metrics.getExperimentId()),
                () -> assertEquals(1_000L, metrics.getFileSizeBytes()),
                () -> assertEquals(900L, metrics.getPayloadBytesDelivered()),
                () -> assertEquals(2.0, metrics.getTransferTimeSec()),
                () -> assertEquals(0.0036, metrics.getThroughputMbps()),
                () -> assertFalse(metrics.getTransferSuccess()),
                () -> assertFalse(metrics.getIntegrityVerified()),
                () -> assertEquals("retry limit reached", metrics.getFailureReason()),
                () -> assertEquals(12L, metrics.getPacketsSent()),
                () -> assertEquals(9L, metrics.getPacketsReceived()),
                () -> assertEquals(2L, metrics.getPacketsDropped()),
                () -> assertEquals(2L, metrics.getRetransmissions()),
                () -> assertEquals(8L, metrics.getAcksReceived()),
                () -> assertEquals(8L, metrics.getPacketsAcked()),
                () -> assertEquals(2L, metrics.getPacketsTimedOut()),
                () -> assertEquals(1L, metrics.getPacketsDuplicated()),
                () -> assertEquals(2.0 / 12.0, metrics.getRetransmissionRatio()),
                () -> assertEquals(1_200L, metrics.getUdpPayloadBytesEmitted()),
                () -> assertEquals(300L, metrics.getProtocolOverheadBytes()),
                () -> assertEquals(0.25, metrics.getProtocolOverheadRatio()),
                () -> assertEquals(8L, metrics.getRttSampleCount()),
                () -> assertEquals(4.5, metrics.getRttMeanMs()),
                () -> assertEquals(7.0, metrics.getRttP95Ms()),
                () -> assertEquals(100L, metrics.getChunkSizeBytes()),
                () -> assertEquals(400L, metrics.getWindowBytesRequested()),
                () -> assertEquals(4L, metrics.getWindowPackets()),
                () -> assertEquals(500L, metrics.getTimeoutMs()),
                () -> assertEquals(5L, metrics.getRetryLimit()),
                () -> assertEquals(10.0, metrics.getPacketLossRate()),
                () -> assertEquals(3.5, metrics.getDelayMs()),
                () -> assertEquals("synthetic-loss", metrics.getScenario()),
                () -> assertEquals(42L, metrics.getImpairmentSeed()),
                () -> assertEquals("1", metrics.getSchemaVersion()),
                () -> assertEquals("stage-11-b1", metrics.getMetricDefinitionVersion()),
                () -> assertEquals("run-1", metrics.getRunId()),
                () -> assertEquals("application-transfer-1", metrics.getApplicationTransferId()),
                () -> assertEquals("protocol-transfer-7", metrics.getProtocolTransferId()),
                () -> assertEquals(TransferMetrics.EvidenceSource.SYNTHETIC, metrics.getEvidenceSource()),
                () -> assertEquals("sender-and-receiver", metrics.getEndpointAttribution()),
                () -> assertEquals("fixture.bin", metrics.getFileAttribution()),
                () -> assertEquals(captured, metrics.getCaptureTimestamp()),
                () -> assertEquals(finalized, metrics.getFinalizationTimestamp()),
                () -> assertEquals("receiver-sha-256", metrics.getIntegrityEvidenceSource()),
                () -> assertEquals(
                        "not observed", metrics.getUnavailableReasons().get("receiver_terminal_status")));
    }

    @Test
    void supportsSuccessfulFailedPartialUnknownAndEmptyFileOutcomes() {
        TransferMetrics successful = TransferMetrics.builder()
                .transferSuccess(true)
                .integrityVerified(true)
                .payloadBytesDelivered(10L)
                .build();
        TransferMetrics failedPartial = TransferMetrics.builder()
                .transferSuccess(false)
                .integrityVerified(null)
                .payloadBytesDelivered(5L)
                .failureReason("timeout")
                .build();
        TransferMetrics unknownSenderOutcome = TransferMetrics.builder()
                .transferSuccess(null)
                .integrityVerified(true)
                .unavailableReason("transfer_success", "sender terminal decision unavailable")
                .build();
        TransferMetrics emptyFile = TransferMetrics.builder()
                .fileSizeBytes(0L)
                .payloadBytesDelivered(0L)
                .throughputMbps(0.0)
                .build();

        assertAll(
                () -> assertTrue(successful.getTransferSuccess()),
                () -> assertTrue(successful.getIntegrityVerified()),
                () -> assertFalse(failedPartial.getTransferSuccess()),
                () -> assertNull(failedPartial.getIntegrityVerified()),
                () -> assertEquals(5L, failedPartial.getPayloadBytesDelivered()),
                () -> assertNull(unknownSenderOutcome.getTransferSuccess()),
                () -> assertTrue(unknownSenderOutcome.getIntegrityVerified()),
                () -> assertEquals(0L, emptyFile.getFileSizeBytes()),
                () -> assertEquals(0L, emptyFile.getPayloadBytesDelivered()),
                () -> assertEquals(0.0, emptyFile.getThroughputMbps()));
    }

    @Test
    void keepsEveryTransferIdentifierDistinct() {
        TransferMetrics metrics = TransferMetrics.builder()
                .experimentId("experiment")
                .runId("run")
                .applicationTransferId("application-transfer")
                .protocolTransferId("protocol-transfer")
                .build();

        assertAll(
                () -> assertEquals("experiment", metrics.getExperimentId()),
                () -> assertEquals("run", metrics.getRunId()),
                () -> assertEquals("application-transfer", metrics.getApplicationTransferId()),
                () -> assertEquals("protocol-transfer", metrics.getProtocolTransferId()));
    }

    @Test
    void leavesUnavailableMeasurementsNullAndReasonsImmutable() {
        Map<String, String> reasons = new java.util.LinkedHashMap<>();
        reasons.put("rtt_mean_ms", "RTT sampling unavailable");
        TransferMetrics metrics = TransferMetrics.builder().unavailableReasons(reasons).build();
        reasons.put("throughput_mbps", "late mutation");

        assertAll(
                () -> assertNull(metrics.getRttSampleCount()),
                () -> assertNull(metrics.getRttMeanMs()),
                () -> assertNull(metrics.getRttP95Ms()),
                () -> assertEquals(1, metrics.getUnavailableReasons().size()),
                () -> assertThrows(
                        UnsupportedOperationException.class,
                        () -> metrics.getUnavailableReasons().put("x", "y")));
    }

    @Test
    void rejectsInvalidMetricValues() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TransferMetrics.builder().packetsSent(-1L).build()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TransferMetrics.builder().throughputMbps(Double.NaN).build()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TransferMetrics.builder().retransmissionRatio(1.01).build()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TransferMetrics.builder().packetLossRate(101.0).build()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TransferMetrics.builder().unavailableReason("", "missing").build()));
    }
}
