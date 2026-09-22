package nettransfer.metrics;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferContextTest {
    @Test
    void preservesTrustedApplicationIdentifiersAndKeepsTheirMeaningsDistinct() {
        TransferContext context = TransferContext.builder(TransferContext.Endpoint.SENDER)
                .experimentId("loss-scenario-a")
                .runId("application-run-17")
                .applicationTransferId("request-42")
                .build();

        assertAll(
                () -> assertEquals("loss-scenario-a", context.getExperimentId()),
                () -> assertEquals("application-run-17", context.getRunId()),
                () -> assertEquals("request-42", context.getApplicationTransferId()),
                () -> assertNull(context.getProtocolTransferId()));
    }

    @Test
    void generatesDistinctRunIdsWhenCallerDoesNotSupplyOne() {
        TransferContext first = TransferContext.builder(TransferContext.Endpoint.SENDER).build();
        TransferContext second = TransferContext.builder(TransferContext.Endpoint.SENDER).build();

        assertAll(
                () -> assertNotEquals(first.getRunId(), second.getRunId()),
                () -> assertNull(first.getExperimentId()),
                () -> assertNull(first.getApplicationTransferId()),
                () -> assertEquals(TransferMetrics.EvidenceSource.REAL, first.getEvidenceSource()));
    }

    @Test
    void representsEffectiveConfigurationAndOptionalImpairmentSettings() {
        TransferConfiguration configuration = TransferConfiguration.builder()
                .chunkSizeBytes(1_024L)
                .windowBytesRequested(4_096L)
                .windowPackets(4L)
                .timeoutMs(275L)
                .retryLimit(7L)
                .packetLossRate(0.0)
                .delayMs(12.5)
                .scenario("configured-baseline")
                .impairmentSeed(99L)
                .startHandshakeTimeoutMs(900L)
                .startRetryLimit(3L)
                .finishHandshakeTimeoutMs(1_100L)
                .finishRetryLimit(4L)
                .receiverCompletionGraceMs(6_850L)
                .build();
        TransferContext context = TransferContext.builder(TransferContext.Endpoint.SENDER)
                .fileSizeBytes(0L)
                .originalFilename("empty.bin")
                .fileAttribution("storage/outgoing/empty.bin")
                .configuration(configuration)
                .build();

        TransferMetrics metrics = context.newMetricsBuilder().build();
        assertAll(
                () -> assertEquals(0L, metrics.getFileSizeBytes()),
                () -> assertEquals(1_024L, metrics.getChunkSizeBytes()),
                () -> assertEquals(4_096L, metrics.getWindowBytesRequested()),
                () -> assertEquals(4L, metrics.getWindowPackets()),
                () -> assertEquals(275L, metrics.getTimeoutMs()),
                () -> assertEquals(7L, metrics.getRetryLimit()),
                () -> assertEquals(0.0, metrics.getPacketLossRate()),
                () -> assertEquals(12.5, metrics.getDelayMs()),
                () -> assertEquals("configured-baseline", metrics.getScenario()),
                () -> assertEquals(99L, metrics.getImpairmentSeed()),
                () -> assertEquals(1_100L, configuration.getFinishHandshakeTimeoutMs()),
                () -> assertEquals(4L, configuration.getFinishRetryLimit()),
                () -> assertEquals(6_850L, configuration.getReceiverCompletionGraceMs()),
                () -> assertEquals("SENDER", metrics.getEndpointAttribution()),
                () -> assertEquals("storage/outgoing/empty.bin", metrics.getFileAttribution()),
                () -> assertNull(metrics.getPacketsSent()),
                () -> assertNull(metrics.getPayloadBytesDelivered()));
    }

    @Test
    void keepsUnsupportedConfigurationUnavailableWithReasons() {
        TransferContext context = TransferContext.builder(TransferContext.Endpoint.RECEIVER)
                .configuration(TransferConfiguration.builder().chunkSizeBytes(512L).build())
                .unavailableReason("window_bytes_requested", "not transmitted")
                .unavailableReason("packet_loss_rate", "no impairment configuration source")
                .build();

        TransferMetrics metrics = context.newMetricsBuilder().build();
        assertAll(
                () -> assertNull(metrics.getWindowBytesRequested()),
                () -> assertNull(metrics.getPacketLossRate()),
                () -> assertEquals(
                        "not transmitted",
                        metrics.getUnavailableReasons().get("window_bytes_requested")));
    }

    @Test
    void attachesOnlyOneConsistentObservedProtocolUuid() {
        UUID observed = UUID.fromString("62b73070-d648-4de8-9255-cf306539e8c5");
        TransferContext context = TransferContext.builder(TransferContext.Endpoint.SENDER)
                .unavailableReason("protocol_transfer_id", "not yet established")
                .build()
                .withProtocolTransferId(observed);

        assertAll(
                () -> assertEquals(observed, context.getProtocolTransferId()),
                () -> assertNull(context.getUnavailableReasons().get("protocol_transfer_id")),
                () -> assertThrows(
                        IllegalStateException.class,
                        () -> context.withProtocolTransferId(UUID.randomUUID())));
    }

    @Test
    void rejectsInvalidIdentityAndConfigurationInputs() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TransferContext.builder(TransferContext.Endpoint.SENDER)
                                .runId(" ")
                                .build()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TransferContext.builder(null).build()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TransferConfiguration.builder().timeoutMs(0L).build()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TransferConfiguration.builder().finishHandshakeTimeoutMs(0L).build()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TransferConfiguration.builder().finishRetryLimit(-1L).build()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> TransferConfiguration.builder().packetLossRate(101.0).build()));
    }
}
