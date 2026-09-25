package nettransfer.metrics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import nettransfer.net.ImpairmentSettings;
import nettransfer.net.UdpChannel;
import nettransfer.transfer.ReceiverEngine;
import nettransfer.transfer.SenderEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real local traffic, then deliberately altered test copies to exercise evidence rejection. */
@Timeout(10)
class ImpairmentEvidenceTest {
    private static final ImpairmentSettings BASELINE = new ImpairmentSettings(true, 0, 0, 37, "baseline");

    @Test
    void receiverDropObservationDistinguishesUnavailableZeroAndPositive() {
        TransferConfiguration configuration = TransferConfiguration.builder()
                .impairmentMechanism(ImpairmentSettings.MECHANISM).packetLossRate(2.0)
                .delayMs(0.0).scenario("loss").impairmentSeed(37L).build();
        MetricsCollector receiver = new MetricsCollector(TransferContext.builder(TransferContext.Endpoint.RECEIVER)
                .configuration(configuration).build());
        assertNull(receiver.snapshot().getPacketsDropped());
        assertTrue(receiver.snapshot().getUnavailableReasons().containsKey("packets_dropped"));
        receiver.beginImpairmentObservation();
        assertEquals(0L, receiver.snapshot().getPacketsDropped());
        assertFalse(receiver.snapshot().getUnavailableReasons().containsKey("packets_dropped"));
        receiver.observeImpairmentDrop();
        assertEquals(1L, receiver.snapshot().getPacketsDropped());

        MetricsCollector sender = new MetricsCollector(TransferContext.builder(TransferContext.Endpoint.SENDER)
                .configuration(configuration).build());
        sender.beginImpairmentObservation();
        assertNull(sender.snapshot().getPacketsDropped());
        assertTrue(sender.snapshot().getUnavailableReasons().get("packets_dropped").contains("receiver"));
        assertThrows(IllegalStateException.class, sender::observeImpairmentDrop);
    }

    @Test
    void rejectsDifferentEnabledProfilesAndEnabledDisabledPair(@TempDir Path root) throws Exception {
        Fixture different = transfer(root.resolve("different"), BASELINE,
                new ImpairmentSettings(true, 0, 0, 38, "baseline"));
        assertEquals("IMPAIRMENT_PROFILE_MISMATCH", assertThrows(MetricsExporter.EvidenceException.class,
                () -> MetricsExporter.reconcile(different.sender(), different.receiver())).getCode());
        Fixture disabled = transfer(root.resolve("disabled"), BASELINE, ImpairmentSettings.disabled());
        assertEquals("IMPAIRMENT_PROFILE_MISMATCH", assertThrows(MetricsExporter.EvidenceException.class,
                () -> MetricsExporter.reconcile(disabled.sender(), disabled.receiver())).getCode());
    }

    @Test
    void rejectsDropCounterWithoutCorrespondingDecisions(@TempDir Path root) throws Exception {
        Fixture fixture = transfer(root, BASELINE, BASELINE);
        Path endpoint = fixture.receiver().resolve("endpoint-receiver.json");
        JsonObject json = JsonParser.parseString(Files.readString(endpoint)).getAsJsonObject();
        json.getAsJsonObject("metrics").addProperty("packets_dropped", 1);
        Files.writeString(endpoint, json.toString());
        assertEquals("EVENT_METRIC_MISMATCH", assertThrows(MetricsExporter.EvidenceException.class,
                () -> MetricsExporter.reconcile(fixture.sender(), fixture.receiver())).getCode());
        assertFalse(Files.exists(fixture.sender().getParent().resolve("reconciled")));
    }

    @Test
    void rejectsMissingScopeStartEvenWhenEventCountsAreRepaired(@TempDir Path root) throws Exception {
        Fixture fixture = transfer(root, BASELINE, BASELINE);
        removeEvent(fixture.receiver(), EventType.IMPAIRMENT_STARTED);
        assertEquals("INVALID_IMPAIRMENT_EVENT", assertThrows(MetricsExporter.EvidenceException.class,
                () -> MetricsExporter.reconcile(fixture.sender(), fixture.receiver())).getCode());
    }

    @Test
    void rejectsMissingScopeFinishAndRepeatedDecisionIndex(@TempDir Path root) throws Exception {
        Fixture unfinished = transfer(root.resolve("unfinished"), BASELINE, BASELINE);
        removeEvent(unfinished.receiver(), EventType.IMPAIRMENT_FINISHED);
        assertEquals("INVALID_IMPAIRMENT_EVENT", assertThrows(MetricsExporter.EvidenceException.class,
                () -> MetricsExporter.reconcile(unfinished.sender(), unfinished.receiver())).getCode());

        Fixture repeated = transfer(root.resolve("repeated"), BASELINE, BASELINE);
        Path eventPath = eventPath(repeated.receiver());
        var events = new ArrayList<String>();
        for (String line : Files.readAllLines(eventPath)) {
            JsonObject event = JsonParser.parseString(line).getAsJsonObject();
            if (event.get("event_type").getAsString().equals(EventType.IMPAIRMENT_DELIVERED.name())) {
                event.addProperty("impairment_decision_index", 1);
            }
            events.add(event.toString());
        }
        Files.write(eventPath, events);
        assertEquals("INVALID_IMPAIRMENT_EVENT", assertThrows(MetricsExporter.EvidenceException.class,
                () -> MetricsExporter.reconcile(repeated.sender(), repeated.receiver())).getCode());
    }

    @Test
    void savedLegacyEvidenceWithoutNewNullableFieldsStillReconciles(@TempDir Path root) throws Exception {
        Fixture fixture = transfer(root, ImpairmentSettings.disabled(), ImpairmentSettings.disabled());
        for (Path directory : new Path[]{fixture.sender(), fixture.receiver()}) {
            Path endpoint = directory.resolve(directory.equals(fixture.sender())
                    ? "endpoint-sender.json" : "endpoint-receiver.json");
            JsonObject object = JsonParser.parseString(Files.readString(endpoint)).getAsJsonObject();
            object.getAsJsonObject("configuration").remove("impairment_mechanism");
            object.getAsJsonObject("metrics").remove("impairment_mechanism");
            Files.writeString(endpoint, object.toString());
            var events = new ArrayList<String>();
            for (String line : Files.readAllLines(eventPath(directory))) {
                JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                event.remove("impairment_decision_index");
                event.remove("impairment_delay_ms");
                events.add(event.toString());
            }
            Files.write(eventPath(directory), events);
        }
        var result = MetricsExporter.reconcile(fixture.sender(), fixture.receiver());
        assertNull(result.summary().metrics().getPacketsDropped());
        assertNull(result.summary().metrics().getImpairmentMechanism());
        assertEquals("COMPLETE", MetricsExporter.readValidatedReconciled(result.outputDirectory())
                .summary().evidenceCompleteness());
    }

    @ParameterizedTest
    @ValueSource(strings = {"action", "hundred-percent", "seed-replay", "zero-delay-failure"})
    void rejectsDecisionsThatCannotComeFromConfiguredSimulator(String defect, @TempDir Path root) throws Exception {
        Fixture fixture = transfer(root, BASELINE, BASELINE);
        if (defect.equals("hundred-percent") || defect.equals("seed-replay")) {
            Path endpoint = fixture.receiver().resolve("endpoint-receiver.json");
            JsonObject object = JsonParser.parseString(Files.readString(endpoint)).getAsJsonObject();
            double loss = defect.equals("hundred-percent") ? 100.0
                    : (new Random(BASELINE.seed()).nextDouble() + 1.0) * 50.0;
            object.getAsJsonObject("configuration").addProperty("packet_loss_rate", loss);
            object.getAsJsonObject("metrics").addProperty("packet_loss_rate", loss);
            Files.writeString(endpoint, object.toString());
        } else {
            Path path = eventPath(fixture.receiver());
            var events = new ArrayList<String>();
            for (String line : Files.readAllLines(path)) {
                JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                if (event.get("event_type").getAsString().equals(EventType.IMPAIRMENT_DELIVERED.name())) {
                    if (defect.equals("action")) {
                        event.addProperty("event_outcome", "DROP");
                    } else {
                        event.addProperty("event_type", EventType.IMPAIRMENT_FAILED.name());
                        event.addProperty("event_outcome", "QUEUE_OVERFLOW");
                    }
                }
                events.add(event.toString());
            }
            Files.write(path, events);
        }
        assertEquals("INVALID_IMPAIRMENT_EVENT", assertThrows(MetricsExporter.EvidenceException.class,
                () -> MetricsExporter.reconcile(fixture.sender(), fixture.receiver())).getCode());
    }

    private static Path eventPath(Path directory) throws Exception {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".jsonl")).findFirst().orElseThrow();
        }
    }

    private static void removeEvent(Path directory, EventType removed) throws Exception {
        Path path = eventPath(directory);
        var events = new ArrayList<String>();
        for (String line : Files.readAllLines(path)) {
            JsonObject event = JsonParser.parseString(line).getAsJsonObject();
            if (event.get("event_type").getAsString().equals(removed.name())) continue;
            event.addProperty("event_sequence", events.size());
            events.add(event.toString());
        }
        Files.write(path, events);
        Path statePath = directory.resolve("run-state.json");
        JsonObject state = JsonParser.parseString(Files.readString(statePath)).getAsJsonObject();
        state.addProperty("event_count", events.size());
        Files.writeString(statePath, state.toString());
    }

    private static Fixture transfer(Path root, ImpairmentSettings senderSettings,
                                    ImpairmentSettings receiverSettings) throws Exception {
        Files.createDirectories(root);
        Path input = Files.write(root.resolve("input.bin"), new byte[]{1, 2, 3});
        Path output = root.resolve("output.bin");
        var executor = Executors.newSingleThreadExecutor();
        try (UdpChannel receiverChannel = new UdpChannel(0, receiverSettings);
             UdpChannel senderChannel = new UdpChannel(senderSettings)) {
            ReceiverEngine receiver = new ReceiverEngine(receiverChannel, 1_000, 1_000, 50,
                    TransferContext.builder(TransferContext.Endpoint.RECEIVER).runId("receiver").build());
            SenderEngine sender = new SenderEngine(senderChannel, InetAddress.getLoopbackAddress(),
                    receiverChannel.getLocalPort(), 2, 1, 200, 2, 200, 2, 200, 2,
                    TransferContext.builder(TransferContext.Endpoint.SENDER).runId("sender").build());
            var receiverLogger = receiver.enableEventLogging(root.resolve("logs"));
            var senderLogger = sender.enableEventLogging(root.resolve("logs"));
            var receive = executor.submit(() -> receiver.receiveFile(output.toString()));
            assertTrue(sender.sendFile(input.toString()).isSuccess());
            assertTrue(receive.get(3, TimeUnit.SECONDS).isSuccess());
            return new Fixture(senderLogger.getRunDirectory(), receiverLogger.getRunDirectory());
        } finally {
            executor.shutdownNow();
        }
    }

    private record Fixture(Path sender, Path receiver) {}
}
