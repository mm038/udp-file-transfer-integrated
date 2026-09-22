package nettransfer.transfer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import nettransfer.metrics.EventLogger;
import nettransfer.metrics.EventType;
import nettransfer.metrics.EndpointMetricsRecord;
import nettransfer.metrics.MetricsExporter;
import nettransfer.metrics.TransferContext;
import nettransfer.net.UdpChannel;
import nettransfer.protocol.ControlMessage;
import nettransfer.protocol.MessageType;
import nettransfer.protocol.PacketDecoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class EventLoggingIntegrationTest {
    @Test
    void realTransferProducesIndependentFinalizedSenderAndReceiverEvidence(@TempDir Path tempDir)
            throws Exception {
        Path input = tempDir.resolve("input.bin");
        Path output = tempDir.resolve("output.bin");
        Files.write(input, new byte[]{11, 22, 33});
        Path logs = tempDir.resolve("logs");
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try (UdpChannel receiverChannel = new UdpChannel(0);
             UdpChannel senderChannel = new UdpChannel()) {
            ReceiverEngine receiver = new ReceiverEngine(receiverChannel, 1_000, 1_000, 80,
                    TransferContext.builder(TransferContext.Endpoint.RECEIVER)
                            .runId("receiver-real-run").build());
            SenderEngine sender = new SenderEngine(senderChannel, InetAddress.getLoopbackAddress(),
                    receiverChannel.getLocalPort(), 2, 1, 100, 2,
                    200, 2, 200, 2,
                    TransferContext.builder(TransferContext.Endpoint.SENDER)
                            .runId("sender-real-run").build());
            EventLogger receiverLog = receiver.enableEventLogging(logs);
            EventLogger senderLog = sender.enableEventLogging(logs);

            Future<TransferResult> receiverResult = executor.submit(
                    () -> receiver.receiveFile(output.toString()));
            TransferResult senderResult = sender.sendFile(input.toString());
            TransferResult completedReceiver = receiverResult.get();

            assertAll(
                    () -> assertTrue(senderResult.isSuccess()),
                    () -> assertTrue(completedReceiver.isSuccess()),
                    () -> assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(output)),
                    () -> assertEquals(sender.getProtocolTransferId(), receiver.getProtocolTransferId()),
                    () -> assertNotEquals(senderLog.getRunDirectory(), receiverLog.getRunDirectory()),
                    () -> assertTrue(senderLog.isFinalized()),
                    () -> assertTrue(receiverLog.isFinalized()),
                    () -> assertTrue(Files.isRegularFile(senderLog.getEndpointMetricsFile())),
                    () -> assertTrue(Files.isRegularFile(receiverLog.getEndpointMetricsFile())));

            EndpointMetricsRecord senderEndpoint = MetricsExporter.readEndpointRecord(
                    senderLog.getEndpointMetricsFile());
            EndpointMetricsRecord receiverEndpoint = MetricsExporter.readEndpointRecord(
                    receiverLog.getEndpointMetricsFile());
            assertAll(
                    () -> assertEquals("sender-real-run", senderEndpoint.runId()),
                    () -> assertEquals("receiver-real-run", receiverEndpoint.runId()),
                    () -> assertEquals(sender.getProtocolTransferId().toString(),
                            senderEndpoint.protocolTransferId()),
                    () -> assertEquals(senderEndpoint.protocolTransferId(),
                            receiverEndpoint.protocolTransferId()),
                    () -> assertTrue(senderEndpoint.endpointEmission().accountingComplete()),
                    () -> assertTrue(receiverEndpoint.endpointEmission().accountingComplete()),
                    () -> assertEquals(3L,
                            receiverEndpoint.metrics().getPayloadBytesDelivered()),
                    () -> assertTrue(receiverEndpoint.metrics().getIntegrityVerified()));

            Set<String> senderTypes = eventTypes(senderLog.getEventsFile());
            Set<String> receiverTypes = eventTypes(receiverLog.getEventsFile());
            assertAll(
                    () -> assertTrue(senderTypes.containsAll(Set.of(
                            EventType.START_ATTEMPT.name(), EventType.START_EMITTED.name(),
                            EventType.DATA_SEND_ATTEMPT.name(), EventType.DATA_EMITTED.name(),
                            EventType.DATA_ACK_RECEIVED.name(), EventType.DATA_ACK_ACCEPTED.name(),
                            EventType.FINISH_ATTEMPT.name(), EventType.FINISH_ACK_ACCEPTED.name(),
                            EventType.TRANSFER_SUCCEEDED.name()))),
                    () -> assertTrue(receiverTypes.containsAll(Set.of(
                            EventType.RECEIVER_WAITING.name(), EventType.START_RECEIVED.name(),
                            EventType.START_ACCEPTED.name(), EventType.DATA_RECEIVED.name(),
                            EventType.PAYLOAD_WRITTEN.name(), EventType.DATA_ACK_EMITTED.name(),
                            EventType.INTEGRITY_VERIFIED.name(), EventType.FINISH_ACK_EMITTED.name(),
                            EventType.COMPLETION_RECOVERY_ENDED.name(),
                            EventType.RECEIVER_SUCCEEDED.name()))));

            String protocolId = sender.getProtocolTransferId().toString();
            List<JsonObject> receiverEventRecords = events(receiverLog.getEventsFile());
            JsonObject receiverStart = receiverEventRecords.stream()
                    .filter(event -> EventType.START_RECEIVED.name()
                            .equals(event.get("event_type").getAsString()))
                    .findFirst().orElseThrow();
            JsonObject receiverStartAck = receiverEventRecords.stream()
                    .filter(event -> EventType.START_ACK_EMITTED.name()
                            .equals(event.get("event_type").getAsString()))
                    .findFirst().orElseThrow();
            assertAll(
                    () -> assertTrue(Files.readString(senderLog.getEventsFile()).contains(protocolId)),
                    () -> assertTrue(Files.readString(receiverLog.getEventsFile()).contains(protocolId)),
                    () -> assertTrue(receiverStart.get("protocol_transfer_id").isJsonNull()),
                    () -> assertEquals(protocolId,
                            receiverStartAck.get("protocol_transfer_id").getAsString()),
                    () -> assertFalse(Files.readString(senderLog.getEventsFile()).contains("11,22,33")),
                    () -> assertEquals("FINALIZED_SUCCESS", state(receiverLog).get("recording_state").getAsString()),
                    () -> assertEquals("FINALIZED_SUCCESS", state(senderLog).get("recording_state").getAsString()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void startTimeoutFinalizesFailureAfterPreservingAttempts(@TempDir Path tempDir) throws Exception {
        Path input = Files.writeString(tempDir.resolve("input.txt"), "data");
        int unusedPort;
        try (UdpChannel probe = new UdpChannel(0)) {
            unusedPort = probe.getLocalPort();
        }
        try (UdpChannel channel = new UdpChannel()) {
            SenderEngine sender = new SenderEngine(channel, InetAddress.getLoopbackAddress(),
                    unusedPort, 4, 1, 20, 1, 20, 1, 20, 1,
                    TransferContext.builder(TransferContext.Endpoint.SENDER)
                            .runId("start-timeout-run").build());
            EventLogger logger = sender.enableEventLogging(tempDir.resolve("logs"));
            TransferResult result = sender.sendFile(input.toString());
            List<JsonObject> events = events(logger.getEventsFile());
            EndpointMetricsRecord endpoint = MetricsExporter.readEndpointRecord(
                    logger.getEndpointMetricsFile());

            assertAll(
                    () -> assertFalse(result.isSuccess()),
                    () -> assertEquals(2, count(events, EventType.START_ATTEMPT)),
                    () -> assertEquals(2, count(events, EventType.START_EMITTED)),
                    () -> assertEquals(1, count(events, EventType.TRANSFER_FAILED)),
                    () -> assertTrue(Files.isRegularFile(logger.getEndpointMetricsFile())),
                    () -> assertEquals("FAILED", endpoint.terminalOutcome()),
                    () -> assertTrue(endpoint.evidenceComplete()),
                    () -> assertTrue(endpoint.endpointEmission().accountingComplete()),
                    () -> assertEquals(0L, endpoint.metrics().getPacketsSent()),
                    () -> assertFalse(endpoint.metrics().getTransferSuccess()),
                    () -> assertEquals("FINALIZED_FAILED", state(logger).get("recording_state").getAsString()),
                    () -> assertTrue(state(logger).get("writer_flushed_and_closed").getAsBoolean()));
        }
    }

    @Test
    void dataRetryFailureLogsTimeoutRecoveryAndRealRetransmission(@TempDir Path tempDir)
            throws Exception {
        Path input = Files.writeString(tempDir.resolve("input.txt"), "abc");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (UdpChannel peer = new UdpChannel(0); UdpChannel senderChannel = new UdpChannel()) {
            Future<?> peerTask = executor.submit(() -> {
                UdpChannel.ReceivedDatagram startDatagram = peer.receive();
                ControlMessage start = ControlMessage.fromJson(
                        new String(startDatagram.data(), StandardCharsets.UTF_8));
                byte[] ack = ControlMessage.createStartAck(start.getTransferId(), true, null)
                        .toJson().getBytes(StandardCharsets.UTF_8);
                peer.send(ack, startDatagram.senderAddress(), startDatagram.senderPort());
                assertEquals(MessageType.DATA, PacketDecoder.decode(peer.receive().data()).getType());
                assertEquals(MessageType.DATA, PacketDecoder.decode(peer.receive().data()).getType());
                return null;
            });
            SenderEngine sender = new SenderEngine(senderChannel, InetAddress.getLoopbackAddress(),
                    peer.getLocalPort(), 8, 1, 20, 1, 100, 1, 100, 1,
                    TransferContext.builder(TransferContext.Endpoint.SENDER)
                            .runId("data-timeout-run").build());
            EventLogger logger = sender.enableEventLogging(tempDir.resolve("logs"));
            TransferResult result = sender.sendFile(input.toString());
            peerTask.get();
            List<JsonObject> events = events(logger.getEventsFile());
            EndpointMetricsRecord endpoint = MetricsExporter.readEndpointRecord(
                    logger.getEndpointMetricsFile());

            assertAll(
                    () -> assertFalse(result.isSuccess()),
                    () -> assertEquals(2, count(events, EventType.DATA_TIMEOUT)),
                    () -> assertEquals(2, count(events, EventType.RECOVERY_ROUND)),
                    () -> assertEquals(1, count(events, EventType.DATA_RETRANSMISSION)),
                    () -> assertEquals(2, count(events, EventType.DATA_EMITTED)),
                    () -> assertEquals(1, count(events, EventType.TRANSFER_FAILED)),
                    () -> assertEquals("FAILED", endpoint.terminalOutcome()),
                    () -> assertTrue(endpoint.evidenceComplete()),
                    () -> assertEquals(2L, endpoint.metrics().getPacketsSent()),
                    () -> assertEquals(1L, endpoint.metrics().getRetransmissions()),
                    () -> assertEquals(2L, endpoint.metrics().getPacketsTimedOut()),
                    () -> assertFalse(endpoint.metrics().getTransferSuccess()),
                    () -> assertEquals("FINALIZED_FAILED", state(logger).get("recording_state").getAsString()));
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<JsonObject> events(Path path) throws Exception {
        try (var lines = Files.lines(path)) {
            return lines.map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
        }
    }

    private static Set<String> eventTypes(Path path) throws Exception {
        return events(path).stream().map(event -> event.get("event_type").getAsString())
                .collect(Collectors.toSet());
    }

    private static long count(List<JsonObject> events, EventType type) {
        return events.stream().filter(event -> type.name().equals(event.get("event_type").getAsString())).count();
    }

    private static JsonObject state(EventLogger logger) throws Exception {
        return JsonParser.parseString(Files.readString(logger.getRunStateFile())).getAsJsonObject();
    }
}
