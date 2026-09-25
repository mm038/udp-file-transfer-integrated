package nettransfer.transfer;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import nettransfer.integrity.FileHashUtil;
import nettransfer.metrics.TransferContext;
import nettransfer.net.ImpairmentSettings;
import nettransfer.net.UdpChannel;
import nettransfer.protocol.ControlMessage;
import nettransfer.protocol.MessageType;
import nettransfer.protocol.Packet;
import nettransfer.protocol.PacketDecoder;
import nettransfer.protocol.PacketEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Raw local UDP injections against the production receiver; never contacts an LLM. */
@Timeout(20)
class ReceiverDataFramingTest {
    @TempDir Path root;

    @BeforeEach
    void retainRequestedEvidence(TestInfo test) throws Exception {
        String requested = System.getProperty("nettransfer.testEvidenceRoot");
        if (requested != null) {
            Path base = Files.createDirectories(Path.of(requested).resolve("receiver-framing-tests"));
            root = Files.createTempDirectory(base, test.getTestMethod().orElseThrow().getName() + "-");
        }
    }

    @Test
    void rejectsShortLongOrdinaryFinalAndDuplicateChunksThenAcceptsCorrections() throws Exception {
        try (Session run = new Session(root, new byte[]{1, 2, 3, 4, 5}, 3)) {
            run.invalid("ordinary-short", run.data(0, new byte[]{1, 2}));
            run.invalid("ordinary-long", run.data(0, new byte[]{1, 2, 3, 9}));
            run.valid(0, new byte[]{1, 2, 3});
            run.invalid("duplicate-wrong-length", run.data(0, new byte[]{1, 2}));
            run.invalid("final-short", run.data(1, new byte[]{4}));
            run.invalid("final-long", run.data(1, new byte[]{4, 5, 9}));
            run.valid(1, new byte[]{4, 5});
            run.complete(2, 5);
        }
    }

    @Test
    void rejectsTrailingTruncatedOversizedDataAndDoesNotAttributeOtherPeersOrUuids() throws Exception {
        try (Session run = new Session(root, new byte[]{11, 12, 13}, 3)) {
            byte[] valid = run.data(0, new byte[]{11, 12, 13});
            run.invalid("trailing-byte", Arrays.copyOf(valid, valid.length + 1));
            run.invalid("truncated-payload", Arrays.copyOf(valid, valid.length - 1));
            byte[] oversizedPayload = new byte[Packet.MAX_PAYLOAD_SIZE + 1];
            Arrays.fill(oversizedPayload, (byte) 7);
            run.invalid("oversized-crc-valid", rawData(run.id, 0, oversizedPayload));

            run.ignored("wrong-peer-malformed", Arrays.copyOf(valid, valid.length + 1), true);
            byte[] otherTransfer = rawData(UUID.randomUUID(), 0, new byte[]{11, 12, 13});
            run.ignored("wrong-uuid-malformed", Arrays.copyOf(otherTransfer, otherTransfer.length + 1), false);
            run.ignored("header-too-short-to-attribute", Arrays.copyOf(valid, Packet.HEADER_SIZE - 1), false);
            run.valid(0, new byte[]{11, 12, 13});
            run.complete(1, 3);
        }
    }

    @Test
    void emptyFileRequiresOneZeroLengthDataChunkAndRejectsPositivePayload() throws Exception {
        try (Session run = new Session(root, new byte[0], 3)) {
            run.invalid("empty-file-positive-payload", run.data(0, new byte[]{77}));
            run.valid(0, new byte[0]);
            run.invalid("empty-file-invalid-duplicate", run.data(0, new byte[]{88}));
            run.complete(1, 2);
        }
    }

    @Test
    void exactMultipleFinalChunkRequiresFullNegotiatedChunkSize() throws Exception {
        try (Session run = new Session(root, new byte[]{21, 22, 23, 24, 25, 26}, 3)) {
            run.valid(0, new byte[]{21, 22, 23});
            run.invalid("exact-multiple-final-short", run.data(1, new byte[]{24, 25}));
            run.invalid("exact-multiple-final-long", run.data(1, new byte[]{24, 25, 26, 27}));
            run.valid(1, new byte[]{24, 25, 26});
            run.complete(2, 2);
        }
    }

    /** Intentionally bypasses the normal encoder's maximum-payload guard for malformed wire fixtures. */
    private static byte[] rawData(UUID id, int sequence, byte[] payload) {
        return ByteBuffer.allocate(Packet.HEADER_SIZE + payload.length)
                .put((byte) MessageType.DATA.ordinal())
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
                .putInt(sequence).putShort((short) payload.length)
                .putInt((int) PacketEncoder.computeCrc32(MessageType.DATA, id, sequence, payload))
                .put(payload).array();
    }

    /** Next receive entry proves the engine finished handling the preceding returned datagram. */
    private static final class ObservedChannel extends UdpChannel {
        private final AtomicLong returned = new AtomicLong();
        private final AtomicLong processed = new AtomicLong();

        private ObservedChannel() throws Exception {
            super(0, ImpairmentSettings.disabled());
        }

        @Override
        public ReceivedDatagram receive() throws IOException {
            processed.set(returned.get());
            ReceivedDatagram datagram = super.receive();
            returned.incrementAndGet();
            return datagram;
        }
    }

    private static final class Session implements AutoCloseable {
        private final Path directory;
        private final Path input;
        private final Path output;
        private final ObservedChannel receiverChannel;
        private final UdpChannel sender;
        private final UdpChannel wrongPeer;
        private final ReceiverEngine receiver;
        private final ExecutorService executor;
        private final Future<TransferResult> future;
        private final UUID id;
        private final Path logDirectory;
        private final JsonArray checks = new JsonArray();
        private TransferResult result;
        private boolean passed;
        private String workerFailure;
        private int datagramNumber;

        private Session(Path directory, byte[] expected, int chunkSize) throws Exception {
            this.directory = directory;
            Files.createDirectories(directory.resolve("datagrams"));
            input = Files.write(directory.resolve("source.bin"), expected);
            output = directory.resolve("received.bin");
            receiverChannel = new ObservedChannel();
            sender = new UdpChannel(ImpairmentSettings.disabled());
            wrongPeer = new UdpChannel(ImpairmentSettings.disabled());
            receiver = new ReceiverEngine(receiverChannel, 5_000, 5_000, 60,
                    TransferContext.builder(TransferContext.Endpoint.RECEIVER)
                            .runId("receiver-framing").build());
            logDirectory = receiver.enableEventLogging(directory.resolve("logs")).getRunDirectory();
            executor = Executors.newSingleThreadExecutor();
            future = executor.submit(() -> receiver.receiveFile(output.toString()));
            ControlMessage start = ControlMessage.createStart("source.bin", expected.length, chunkSize);
            id = UUID.fromString(start.getTransferId());
            try {
                sender.setReceiveTimeoutMillis(2_000);
                send(sender, "start", start.toJson().getBytes(StandardCharsets.UTF_8));
                ControlMessage acknowledgement = ControlMessage.fromJson(new String(sender.receive().data(), StandardCharsets.UTF_8));
                assertEquals(MessageType.START_ACK, acknowledgement.getType());
                assertTrue(acknowledgement.isAccepted());
                awaitProcessed(1);
            } catch (Exception | AssertionError failure) {
                close();
                throw failure;
            }
        }

        private byte[] data(int sequence, byte[] payload) throws Exception {
            return PacketEncoder.encode(Packet.createData(id, sequence, payload));
        }

        private void invalid(String label, byte[] bytes) throws Exception {
            unchanged(label, bytes, false, true);
        }

        private void ignored(String label, byte[] bytes, boolean useWrongPeer) throws Exception {
            unchanged(label, bytes, useWrongPeer, false);
        }

        private void unchanged(String label, byte[] bytes, boolean useWrongPeer, boolean countInvalid) throws Exception {
            var before = receiver.getMetricsSnapshot();
            var beforeObservations = receiver.getReceiverObservations();
            byte[] beforeContents = Files.readAllBytes(output);
            long expectedProcessed = receiverChannel.returned.get() + 1;
            UdpChannel origin = useWrongPeer ? wrongPeer : sender;
            JsonObject check = new JsonObject();
            check.addProperty("case", label);
            check.addProperty("expected", countInvalid ? "DATA_INVALID_WITHOUT_PROGRESS_OR_ACK" : "IGNORED_WITHOUT_ATTRIBUTION");
            check.addProperty("before_invalid", beforeObservations.dataValidationFailures());
            check.addProperty("datagram", send(origin, label, bytes));
            checks.add(check);
            awaitProcessed(expectedProcessed);
            var after = receiver.getMetricsSnapshot();
            var observations = receiver.getReceiverObservations();
            check.addProperty("after_invalid", observations.dataValidationFailures());
            check.addProperty("after_received", after.getPacketsReceived());
            check.addProperty("after_delivered_bytes", after.getPayloadBytesDelivered());
            check.addProperty("after_accepted", observations.dataAccepted());
            check.addProperty("after_ack_attempts", observations.dataAckAttempts());
            check.addProperty("file_unchanged", Arrays.equals(beforeContents, Files.readAllBytes(output)));
            assertEquals(beforeObservations.dataValidationFailures() + (countInvalid ? 1 : 0),
                    observations.dataValidationFailures(), label + ": invalid observation must reflect the rejected frame");
            assertEquals(before.getPacketsReceived(), after.getPacketsReceived(), label + ": no valid arrival");
            assertEquals(before.getPayloadBytesDelivered(), after.getPayloadBytesDelivered(), label + ": no delivery");
            assertEquals(before.getPacketsDuplicated(), after.getPacketsDuplicated(), label + ": no duplicate acceptance");
            assertEquals(beforeObservations.dataAccepted(), observations.dataAccepted(), label + ": no tracker progress");
            assertEquals(beforeObservations.dataAckAttempts(), observations.dataAckAttempts(), label + ": no ACK attempt");
            assertArrayEquals(beforeContents, Files.readAllBytes(output), label + ": file must remain unchanged");
            origin.setReceiveTimeoutMillis(100);
            assertThrows(SocketTimeoutException.class, origin::receive, label + ": rejected packet must not elicit an ACK");
            check.addProperty("no_ack_observed", true);
            check.addProperty("checks_passed", true);
        }

        private void valid(int sequence, byte[] payload) throws Exception {
            long expectedProcessed = receiverChannel.returned.get() + 1;
            sender.setReceiveTimeoutMillis(2_000);
            send(sender, "valid-sequence-" + sequence, data(sequence, payload));
            Packet ack = PacketDecoder.decode(sender.receive().data());
            assertEquals(MessageType.ACK, ack.getType());
            assertEquals(id, ack.getTransferId());
            assertEquals(sequence, ack.getSeqNum());
            awaitProcessed(expectedProcessed);
        }

        private void complete(long expectedArrivals, long expectedInvalid) throws Exception {
            sender.setReceiveTimeoutMillis(2_000);
            ControlMessage finish = ControlMessage.createFinish(id.toString(), FileHashUtil.sha256Hex(input.toString()));
            send(sender, "finish", finish.toJson().getBytes(StandardCharsets.UTF_8));
            ControlMessage ack = ControlMessage.fromJson(new String(sender.receive().data(), StandardCharsets.UTF_8));
            assertEquals(MessageType.FINISH_ACK, ack.getType());
            assertTrue(ack.isVerified());
            result = future.get(2, TimeUnit.SECONDS);
            assertTrue(result.isSuccess());
            var metrics = receiver.getMetricsSnapshot();
            var observations = receiver.getReceiverObservations();
            assertEquals(expectedArrivals, metrics.getPacketsReceived());
            assertEquals(expectedArrivals, observations.dataAccepted());
            assertEquals(expectedArrivals, observations.dataAckAttempts());
            assertEquals(expectedInvalid, observations.dataValidationFailures());
            assertEquals(0L, metrics.getPacketsDuplicated());
            assertEquals(Files.size(input), metrics.getPayloadBytesDelivered());
            assertTrue(metrics.getIntegrityVerified());
            assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(output));
            assertEquals(FileHashUtil.sha256Hex(input.toString()), FileHashUtil.sha256Hex(output.toString()));
            passed = true;
        }

        private String send(UdpChannel origin, String label, byte[] bytes) throws Exception {
            String relative = "datagrams/" + String.format("%02d", ++datagramNumber) + "-" + label + ".bin";
            Files.write(directory.resolve(relative), bytes);
            origin.send(bytes, InetAddress.getLoopbackAddress(), receiverChannel.getLocalPort());
            return relative;
        }

        private void awaitProcessed(long count) throws Exception {
            await(() -> receiverChannel.processed.get() >= count || future.isDone());
            assertFalse(future.isDone(), "Receiver must remain active while rejecting or accepting DATA");
            assertTrue(receiverChannel.processed.get() >= count, "Receiver did not finish handling the injected datagram");
        }

        private static void await(BooleanSupplier condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(2);
            assertTrue(condition.getAsBoolean(), "Timed out awaiting receiver processing observation");
        }

        @Override
        public void close() throws Exception {
            receiverChannel.close();
            sender.close();
            wrongPeer.close();
            executor.shutdownNow();
            try {
                if (result == null) result = future.get(2, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                workerFailure = failure.getCause().getClass().getSimpleName() + ": " + failure.getCause().getMessage();
            } finally {
                boolean stopped = executor.awaitTermination(2, TimeUnit.SECONDS);
                writeSummary(stopped);
                assertTrue(stopped, "Receiver worker must be joined during teardown");
            }
        }

        private void writeSummary(boolean workerStopped) throws Exception {
            JsonObject summary = new JsonObject();
            summary.addProperty("test_passed", passed);
            summary.addProperty("evidence_type", "REAL_LOOPBACK_RAW_DATA_FRAMING_TEST");
            summary.addProperty("protocol_transfer_id", id.toString());
            summary.addProperty("worker_stopped", workerStopped);
            summary.addProperty("worker_failure", workerFailure);
            summary.addProperty("receiver_success", result == null ? null : result.isSuccess());
            summary.addProperty("source_file", "source.bin");
            summary.addProperty("output_file", "received.bin");
            summary.addProperty("receiver_log_directory", directory.relativize(logDirectory).toString());
            String expectedHash = FileHashUtil.sha256Hex(input.toString());
            String actualHash = Files.exists(output) ? FileHashUtil.sha256Hex(output.toString()) : null;
            summary.addProperty("source_sha256", expectedHash);
            summary.addProperty("output_sha256", actualHash);
            summary.addProperty("hashes_match", expectedHash.equals(actualHash));
            var metrics = receiver.getMetricsSnapshot();
            var observations = receiver.getReceiverObservations();
            summary.addProperty("packets_received", metrics.getPacketsReceived());
            summary.addProperty("payload_bytes_delivered", metrics.getPayloadBytesDelivered());
            summary.addProperty("packets_duplicated", metrics.getPacketsDuplicated());
            summary.addProperty("integrity_verified", metrics.getIntegrityVerified());
            summary.addProperty("data_validation_failures", observations.dataValidationFailures());
            summary.addProperty("data_accepted", observations.dataAccepted());
            summary.addProperty("data_ack_attempts", observations.dataAckAttempts());
            summary.add("checks", checks);
            Files.writeString(directory.resolve("summary.json"), new GsonBuilder().serializeNulls()
                    .setPrettyPrinting().create().toJson(summary) + "\n", StandardCharsets.UTF_8);
        }
    }
}
