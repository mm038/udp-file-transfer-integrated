package nettransfer.metrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class EventLoggerTest {
    @Test
    void writesIndependentJsonLinesWithStableIdentityEscapingAndNulls(@TempDir Path tempDir)
            throws Exception {
        TransferContext context = TransferContext.builder(TransferContext.Endpoint.SENDER)
                .experimentId("unsafe/experiment")
                .runId("caller/run-id")
                .applicationTransferId("application-1")
                .build();
        AtomicLong clock = new AtomicLong(50L);
        EventLogger logger = EventLogger.open(tempDir.resolve("logs"), context,
                () -> clock.getAndAdd(10L));

        logger.record(context, EventType.START_ATTEMPT, TransferEvent.Direction.OUTBOUND,
                TransferEvent.Details.builder().messageType("START").attemptNumber(1).build());
        logger.record(context, EventType.TRANSFER_FAILED, TransferEvent.Direction.LOCAL,
                TransferEvent.Details.builder().failureReason("quote \" and newline\nvalue").build());
        logger.finalizeSession(context, false, "expected failure");

        List<String> lines = Files.readAllLines(logger.getEventsFile());
        assertEquals(2, lines.size());
        JsonObject first = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        JsonObject second = JsonParser.parseString(lines.get(1)).getAsJsonObject();
        assertAll(
                () -> assertEquals("1", first.get("schema_version").getAsString()),
                () -> assertEquals("caller/run-id", first.get("run_id").getAsString()),
                () -> assertEquals(0, first.get("event_sequence").getAsLong()),
                () -> assertEquals(1, second.get("event_sequence").getAsLong()),
                () -> assertEquals("caller/run-id:1", second.get("event_id").getAsString()),
                () -> assertEquals(50L, first.get("monotonic_time_nanos").getAsLong()),
                () -> assertEquals("SENDER", first.get("endpoint").getAsString()),
                () -> assertTrue(first.get("protocol_transfer_id").isJsonNull()),
                () -> assertDoesNotThrow(() -> Instant.parse(first.get("timestamp_utc").getAsString())),
                () -> assertEquals("quote \" and newline\nvalue",
                        second.get("failure_reason").getAsString()),
                () -> assertTrue(logger.getRunDirectory().startsWith(tempDir.resolve("logs"))),
                () -> assertFalse(logger.getRunDirectory().toString().contains("caller/run-id")),
                () -> assertTrue(logger.isFinalized()),
                () -> assertNull(logger.getLoggingFailure()));

        JsonObject state = JsonParser.parseString(Files.readString(logger.getRunStateFile()))
                .getAsJsonObject();
        assertAll(
                () -> assertEquals("FINALIZED_FAILED", state.get("recording_state").getAsString()),
                () -> assertTrue(state.get("writer_flushed_and_closed").getAsBoolean()),
                () -> assertEquals(2, state.get("event_count").getAsLong()));
    }

    @Test
    void collisionNeverOverwritesExistingRun(@TempDir Path tempDir) throws Exception {
        TransferContext context = TransferContext.builder(TransferContext.Endpoint.RECEIVER)
                .runId("same-run").build();
        EventLogger first = EventLogger.open(tempDir, context);
        first.close();

        assertThrows(IOException.class, () -> EventLogger.open(tempDir, context));
        JsonObject state = JsonParser.parseString(Files.readString(first.getRunStateFile()))
                .getAsJsonObject();
        assertEquals("INCOMPLETE", state.get("recording_state").getAsString());
        assertTrue(state.get("local_success").isJsonNull());
    }

    @Test
    void largeSequencePreservesOrder(@TempDir Path tempDir) throws Exception {
        TransferContext context = TransferContext.builder(TransferContext.Endpoint.SENDER)
                .runId("large-run").build();
        EventLogger logger = EventLogger.open(tempDir, context);
        for (int i = 0; i < 1_000; i++) {
            logger.record(context, EventType.DATA_SEND_ATTEMPT, TransferEvent.Direction.OUTBOUND,
                    TransferEvent.Details.builder().sequenceNumber(i).payloadBytes(1L).build());
        }
        logger.finalizeSession(context, true, null);

        List<String> lines = Files.readAllLines(logger.getEventsFile());
        assertEquals(1_000, lines.size());
        for (int i = 0; i < lines.size(); i++) {
            JsonObject event = JsonParser.parseString(lines.get(i)).getAsJsonObject();
            assertEquals(i, event.get("event_sequence").getAsLong());
            assertEquals(i, event.get("sequence_number").getAsInt());
            assertFalse(event.has("payload"));
        }
    }

    @Test
    void invalidLogsRootIsReportedAtStartup(@TempDir Path tempDir) throws Exception {
        Path regularFile = Files.writeString(tempDir.resolve("not-a-directory"), "occupied");
        TransferContext context = TransferContext.builder(TransferContext.Endpoint.SENDER).build();
        assertThrows(IOException.class, () -> EventLogger.open(regularFile, context));
    }
}
