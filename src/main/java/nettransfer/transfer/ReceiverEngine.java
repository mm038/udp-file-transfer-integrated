package nettransfer.transfer;

import com.google.gson.JsonParseException;
import nettransfer.integrity.FileHashUtil;
import nettransfer.metrics.TransferConfiguration;
import nettransfer.metrics.TransferContext;
import nettransfer.metrics.EventLogger;
import nettransfer.metrics.EventType;
import nettransfer.metrics.MetricsCollector;
import nettransfer.metrics.LiveMetricsProvider;
import nettransfer.metrics.LiveMetricsSnapshot;
import nettransfer.metrics.TransferMetrics;
import nettransfer.metrics.TransferEvent;
import nettransfer.net.UdpChannel;
import nettransfer.protocol.ControlMessage;
import nettransfer.protocol.MessageType;
import nettransfer.protocol.Packet;
import nettransfer.protocol.PacketDecoder;
import nettransfer.protocol.PacketEncoder;
import nettransfer.protocol.PacketValidator;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Drives one complete receiver-side file transfer: accepts a START,
 * receives DATA chunks (validating, deduplicating, and discarding
 * out-of-order arrivals per Stage 9), writes accepted chunks directly to
 * their correct file offset (random access, mirroring FileChunker's
 * write-side equivalent), and performs the final FINISH/FINISH_ACK
 * SHA-256 verification.
 *
 * Distinguishes binary DATA/ACK from JSON control messages by their first
 * byte: MessageType ordinals are 0-6, while JSON always starts with '{'
 * (0x7B / 123) -- no ambiguity, no extra framing needed.
 */
public class ReceiverEngine implements LiveMetricsProvider {

    public static final int DEFAULT_INITIAL_TIMEOUT_MS = 300_000;
    public static final int DEFAULT_INACTIVITY_TIMEOUT_MS = 60_000;
    public static final int DEFAULT_COMPLETION_GRACE_MS =
            SenderEngine.DEFAULT_FINISH_HANDSHAKE_TIMEOUT_MS
                    * (SenderEngine.DEFAULT_FINISH_RETRY_LIMIT + 1) + 250;

    private final UdpChannel channel;
    private final int initialTimeoutMillis;
    private final int inactivityTimeoutMillis;
    private final int completionGraceMillis;
    private volatile TransferContext transferContext;
    private final MetricsCollector metricsCollector;
    private volatile EventLogger eventLogger = EventLogger.disabled();

    public ReceiverEngine(UdpChannel channel) {
        this(channel, DEFAULT_INITIAL_TIMEOUT_MS, DEFAULT_INACTIVITY_TIMEOUT_MS,
                DEFAULT_COMPLETION_GRACE_MS, null);
    }

    /** Integration point for a trusted application-supplied receiver identity context. */
    public ReceiverEngine(UdpChannel channel, TransferContext context) {
        this(channel, DEFAULT_INITIAL_TIMEOUT_MS, DEFAULT_INACTIVITY_TIMEOUT_MS,
                DEFAULT_COMPLETION_GRACE_MS, context);
    }

    public ReceiverEngine(UdpChannel channel, int initialTimeoutMillis, int inactivityTimeoutMillis) {
        this(channel, initialTimeoutMillis, inactivityTimeoutMillis, DEFAULT_COMPLETION_GRACE_MS, null);
    }

    public ReceiverEngine(UdpChannel channel, int initialTimeoutMillis, int inactivityTimeoutMillis,
                          TransferContext context) {
        this(channel, initialTimeoutMillis, inactivityTimeoutMillis, DEFAULT_COMPLETION_GRACE_MS, context);
    }

    public ReceiverEngine(UdpChannel channel, int initialTimeoutMillis, int inactivityTimeoutMillis,
                          int completionGraceMillis, TransferContext context) {
        if (initialTimeoutMillis <= 0 || inactivityTimeoutMillis <= 0 || completionGraceMillis <= 0) {
            throw new IllegalArgumentException("Receiver timeouts must be positive");
        }
        this.channel = channel;
        this.initialTimeoutMillis = initialTimeoutMillis;
        this.inactivityTimeoutMillis = inactivityTimeoutMillis;
        this.completionGraceMillis = completionGraceMillis;
        this.transferContext = initializeContext(context);
        this.metricsCollector = new MetricsCollector(this.transferContext);
    }

    public TransferResult receiveFile(String outputFilePath) throws IOException {
        metricsCollector.observeEndpointAwaitingStart();
        metricsCollector.beginEndpointEmissionAccounting();
        channel.setSuccessfulSendObserver(metricsCollector::observeUdpPayloadEmitted);
        log(EventType.RECEIVER_WAITING, TransferEvent.Direction.LOCAL,
                TransferEvent.Details.builder().eventOutcome("AWAITING_START").build());
        try {
            TransferResult result = receiveFileObserved(outputFilePath);
            channel.setSuccessfulSendObserver(null);
            metricsCollector.observeReceiverTerminalOutcome(
                    result.isSuccess(), terminalReason(result));
            recordTerminal(result.isSuccess(), terminalReason(result));
            return result;
        } catch (IOException | RuntimeException exception) {
            metricsCollector.observeReceiverError();
            channel.setSuccessfulSendObserver(null);
            String reason = describeException(exception);
            metricsCollector.observeReceiverTerminalOutcome(false, reason);
            recordTerminal(false, reason);
            throw exception;
        }
    }

    private TransferResult receiveFileObserved(String outputFilePath) throws IOException {
        TransferContext.Builder awaitingStart = transferContext.toBuilder()
                .protocolTransferId(null)
                .originalFilename(null)
                .fileSizeBytes(null)
                .unavailableReason("protocol_transfer_id", "no valid START observed")
                .unavailableReason("original_filename", "no valid START observed")
                .unavailableReason("file_size_bytes", "no valid START observed");
        if (transferContext.getFileAttribution() == null) {
            awaitingStart.fileAttribution("destination:" + new File(outputFilePath).getName())
                    .clearUnavailableReason("file_attribution");
        }
        transferContext = awaitingStart.build();
        metricsCollector.updateContext(transferContext);
        StartRequest request = awaitStart();
        if (request == null) {
            return TransferResult.failure(TransferResult.FailureReason.RECEIVER_INITIAL_TIMEOUT,
                    "RECEIVER_INITIAL_TIMEOUT: no valid START received");
        }

        UdpChannel.ReceivedDatagram startDatagram = request.datagram();
        ControlMessage start = request.message();
        int totalChunks = computeTotalChunks(start.getFileSize(), start.getChunkSize());
        UUID transferId = UUID.fromString(start.getTransferId());
        TransferConfiguration observedConfiguration = transferContext.getConfiguration().toBuilder()
                .chunkSizeBytes((long) start.getChunkSize())
                .build();
        String observedFilename = start.getFilename();
        boolean hasObservedFilename = observedFilename != null && !observedFilename.isBlank();
        TransferContext.Builder observedContext = transferContext.toBuilder()
                .protocolTransferId(transferId)
                .originalFilename(hasObservedFilename ? observedFilename : null)
                .fileSizeBytes(start.getFileSize())
                .configuration(observedConfiguration)
                .clearUnavailableReason("protocol_transfer_id")
                .clearUnavailableReason("file_size_bytes")
                .clearUnavailableReason("chunk_size_bytes");
        if (hasObservedFilename) {
            observedContext.clearUnavailableReason("original_filename");
        } else {
            observedContext.unavailableReason("original_filename", "START filename was unavailable");
        }
        transferContext = observedContext.build();
        metricsCollector.updateContext(transferContext);
        metricsCollector.observeReceiverStartAccepted(
                startDatagram.senderAddress().getHostAddress(), startDatagram.senderPort());
        log(EventType.START_ACCEPTED, TransferEvent.Direction.LOCAL,
                TransferEvent.Details.builder().messageType(MessageType.START.name())
                        .validationResult("ACCEPTED").eventOutcome("ESTABLISHED").build());
        ReceiverSequenceTracker tracker = new ReceiverSequenceTracker(totalChunks);
        long lastProgressAt = System.nanoTime();

        CompletionOutcome completion = null;
        try (RandomAccessFile outputFile = openOutputFile(outputFilePath)) {
            outputFile.setLength(start.getFileSize());

            while (completion == null) {
                UdpChannel.ReceivedDatagram datagram = receiveBeforeDeadline(
                        lastProgressAt, inactivityTimeoutMillis);
                if (datagram == null) {
                    return TransferResult.failure(TransferResult.FailureReason.RECEIVER_INACTIVITY_TIMEOUT,
                            "RECEIVER_INACTIVITY_TIMEOUT: no new DATA chunk was written");
                }
                byte[] data = datagram.data();
                if (data.length == 0) {
                    continue;
                }

                if (data[0] == '{') {
                    try {
                        completion = handleControlMessage(data, outputFilePath, tracker,
                                datagram, start, startDatagram);
                    } catch (JsonParseException e) {
                        continue;
                    }
                    continue;
                }

                long writtenAt = handleDataPacket(data, start.getChunkSize(), totalChunks, outputFile, tracker,
                        transferId, datagram, startDatagram);
                if (writtenAt != -1) {
                    lastProgressAt = writtenAt;
                }
            }
        }
        metricsCollector.observeReceiverCompletionRecovery();
        recoverDuplicateFinishes(completion, startDatagram);
        return completion.result();
    }

    /** Returns the latest immutable receiver view; sender application IDs are never inferred. */
    public TransferContext getTransferContext() {
        return transferContext;
    }

    public UUID getProtocolTransferId() {
        return transferContext.getProtocolTransferId();
    }

    /** Returns an immutable receiver metrics snapshot; active-transfer snapshots are provisional. */
    public TransferMetrics getMetricsSnapshot() {
        return metricsCollector.snapshot();
    }

    public MetricsCollector.ReceiverObservations getReceiverObservations() {
        return metricsCollector.receiverObservations();
    }

    public MetricsCollector.EndpointEmissionObservations getEndpointEmissionObservations() {
        return metricsCollector.endpointEmissionObservations();
    }

    @Override
    public LiveMetricsSnapshot getLiveMetricsSnapshot() {
        return metricsCollector.liveSnapshot();
    }

    /** Enables one durable endpoint-local JSONL session. Call before receiveFile. */
    public synchronized EventLogger enableEventLogging(Path logsRoot) throws IOException {
        if (metricsCollector.liveSnapshot().lifecycleState()
                != LiveMetricsSnapshot.LifecycleState.NOT_STARTED) {
            throw new IllegalStateException("event logging must be enabled before the transfer starts");
        }
        if (eventLogger.isEnabled()) {
            throw new IllegalStateException("event logging is already enabled");
        }
        eventLogger = EventLogger.open(logsRoot, transferContext);
        return eventLogger;
    }

    public EventLogger getEventLogger() {
        return eventLogger;
    }

    private TransferContext initializeContext(TransferContext supplied) {
        if (supplied != null && supplied.getEndpoint() != TransferContext.Endpoint.RECEIVER) {
            throw new IllegalArgumentException("receiver requires a RECEIVER transfer context");
        }
        if (supplied != null && supplied.getProtocolTransferId() != null) {
            throw new IllegalArgumentException("receiver protocol ID must come from a validated START");
        }

        TransferConfiguration provided = supplied == null ? null : supplied.getConfiguration();
        requireMatching("receiverInitialTimeoutMs",
                provided == null ? null : provided.getReceiverInitialTimeoutMs(), initialTimeoutMillis);
        requireMatching("receiverInactivityTimeoutMs",
                provided == null ? null : provided.getReceiverInactivityTimeoutMs(), inactivityTimeoutMillis);
        requireMatching("receiverCompletionGraceMs",
                provided == null ? null : provided.getReceiverCompletionGraceMs(), completionGraceMillis);

        TransferConfiguration.Builder configuration = provided == null
                ? TransferConfiguration.builder()
                : provided.toBuilder();
        TransferConfiguration effective = configuration
                .receiverInitialTimeoutMs((long) initialTimeoutMillis)
                .receiverInactivityTimeoutMs((long) inactivityTimeoutMillis)
                .receiverCompletionGraceMs((long) completionGraceMillis)
                .build();

        TransferContext.Builder context = supplied == null
                ? TransferContext.builder(TransferContext.Endpoint.RECEIVER)
                : supplied.toBuilder();
        context.configuration(effective)
                .unavailableReason("protocol_transfer_id", "no valid START observed")
                .unavailableReason("original_filename", "no valid START observed")
                .unavailableReason("file_size_bytes", "no valid START observed");
        if (supplied == null || supplied.getFileAttribution() == null) {
            context.unavailableReason("file_attribution", "destination not yet selected");
        }
        if (effective.getWindowBytesRequested() == null) {
            context.unavailableReason(
                    "window_bytes_requested", "not transmitted by the current protocol");
        }
        if (effective.getChunkSizeBytes() == null) {
            context.unavailableReason("chunk_size_bytes", "no valid START observed");
        }
        if (effective.getWindowPackets() == null) {
            context.unavailableReason("window_packets", "not transmitted by the current protocol");
        }
        if (effective.getTimeoutMs() == null) {
            context.unavailableReason("timeout_ms", "sender DATA timeout is not transmitted");
        }
        if (effective.getRetryLimit() == null) {
            context.unavailableReason("retry_limit", "sender DATA retry limit is not transmitted");
        }
        if (effective.getPacketLossRate() == null) {
            context.unavailableReason("packet_loss_rate", "no impairment configuration source");
        }
        if (effective.getDelayMs() == null) {
            context.unavailableReason("delay_ms", "no impairment configuration source");
        }
        if (effective.getScenario() == null) {
            context.unavailableReason("scenario", "no impairment configuration source");
        }
        if (effective.getImpairmentSeed() == null) {
            context.unavailableReason("impairment_seed", "no impairment configuration source");
        }
        if (supplied == null || supplied.getSchemaVersion() == null) {
            context.unavailableReason("schema_version", "schema version not configured");
        }
        if (supplied == null || supplied.getMetricDefinitionVersion() == null) {
            context.unavailableReason(
                    "metric_definition_version", "metric definition version not configured");
        }
        return context.build();
    }

    private static void requireMatching(String field, Long supplied, int actual) {
        if (supplied != null && supplied != actual) {
            throw new IllegalArgumentException(field + " conflicts with the effective engine value");
        }
    }

    private StartRequest awaitStart() throws IOException {
        long waitingStartedAt = System.nanoTime();
        while (true) {
            UdpChannel.ReceivedDatagram datagram = receiveBeforeDeadline(
                    waitingStartedAt, initialTimeoutMillis);
            if (datagram == null) {
                return null;
            }
            ControlMessage start;
            try {
                start = ControlMessage.fromJson(new String(datagram.data(), StandardCharsets.UTF_8));
            } catch (JsonParseException e) {
                continue;
            }
            if (start.getType() != MessageType.START) {
                continue;
            }
            metricsCollector.observeReceiverStartArrival();
            log(EventType.START_RECEIVED, TransferEvent.Direction.INBOUND,
                    TransferEvent.Details.builder().messageType(MessageType.START.name()).build());
            if (start.getTransferId() == null) {
                continue;
            }
            boolean valid = start.getFileSize() >= 0
                    && start.getChunkSize() > 0 && start.getChunkSize() <= Packet.MAX_PAYLOAD_SIZE;
            UUID validatedTransferId = null;
            try {
                validatedTransferId = UUID.fromString(start.getTransferId());
            } catch (IllegalArgumentException e) {
                valid = false;
            }
            if (valid) {
                transferContext = transferContext.withProtocolTransferId(validatedTransferId);
                metricsCollector.updateContext(transferContext);
            }
            ControlMessage acknowledgement = ControlMessage.createStartAck(start.getTransferId(),
                    valid, valid ? null : "Invalid START metadata");
            if (!valid) {
                metricsCollector.observeReceiverStartRejected();
                log(EventType.START_REJECTED, TransferEvent.Direction.LOCAL,
                        TransferEvent.Details.builder().messageType(MessageType.START.name())
                                .validationResult("REJECTED")
                                .failureReason("Invalid START metadata").build());
            }
            byte[] acknowledgementBytes = acknowledgement.toJson().getBytes(StandardCharsets.UTF_8);
            log(EventType.START_ACK_ATTEMPT, TransferEvent.Direction.OUTBOUND,
                    TransferEvent.Details.builder().messageType(MessageType.START_ACK.name())
                            .validationResult(valid ? "ACCEPTED" : "REJECTED").build());
            channel.send(acknowledgementBytes,
                    datagram.senderAddress(), datagram.senderPort());
            log(EventType.START_ACK_EMITTED, TransferEvent.Direction.OUTBOUND,
                    TransferEvent.Details.builder().messageType(MessageType.START_ACK.name())
                            .encodedUdpPayloadBytes((long) acknowledgementBytes.length)
                            .eventOutcome("SOCKET_SEND_SUCCEEDED").build());
            if (valid) {
                return new StartRequest(start, datagram);
            }
        }
    }

    private UdpChannel.ReceivedDatagram receiveBeforeDeadline(long startedAt, int timeoutMillis)
            throws IOException {
        long timeoutNanos = (long) timeoutMillis * 1_000_000L;
        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Receiver interrupted while waiting for UDP data");
            }
            long remaining = timeoutNanos - (System.nanoTime() - startedAt);
            if (remaining <= 0) {
                return null;
            }
            channel.setReceiveTimeoutMillis((int) Math.max(1,
                    Math.min(250, (remaining + 999_999L) / 1_000_000L)));
            try {
                UdpChannel.ReceivedDatagram datagram = channel.receive();
                return System.nanoTime() - startedAt >= timeoutNanos ? null : datagram;
            } catch (SocketTimeoutException e) {
                // Recheck the overall deadline; unrelated traffic never extends it.
            }
        }
    }

    private record StartRequest(ControlMessage message, UdpChannel.ReceivedDatagram datagram) {}

    private CompletionOutcome handleControlMessage(byte[] data, String outputFilePath,
                                                 ReceiverSequenceTracker tracker,
                                                 UdpChannel.ReceivedDatagram datagram,
                                                 ControlMessage start,
                                                 UdpChannel.ReceivedDatagram startDatagram) throws IOException {
        ControlMessage msg = ControlMessage.fromJson(new String(data, StandardCharsets.UTF_8));
        if (!isExpectedPeer(datagram, startDatagram)
                || !start.getTransferId().equals(msg.getTransferId())) {
            return null;
        }
        if (msg.getType() == MessageType.START) {
            // A lost START_ACK may cause a retry. Re-ACK without resetting file or sequence state.
            if (start.getFileSize() == msg.getFileSize()
                    && start.getChunkSize() == msg.getChunkSize()
                    && java.util.Objects.equals(start.getFilename(), msg.getFilename())) {
                ControlMessage ack = ControlMessage.createStartAck(start.getTransferId(), true, null);
                byte[] ackBytes = ack.toJson().getBytes(StandardCharsets.UTF_8);
                log(EventType.START_RECEIVED, TransferEvent.Direction.INBOUND,
                        TransferEvent.Details.builder().messageType(MessageType.START.name())
                                .eventOutcome("DUPLICATE").build());
                log(EventType.START_ACK_ATTEMPT, TransferEvent.Direction.OUTBOUND,
                        TransferEvent.Details.builder().messageType(MessageType.START_ACK.name())
                                .eventOutcome("DUPLICATE_START_RECOVERY").build());
                channel.send(ackBytes,
                        datagram.senderAddress(), datagram.senderPort());
                log(EventType.START_ACK_EMITTED, TransferEvent.Direction.OUTBOUND,
                        TransferEvent.Details.builder().messageType(MessageType.START_ACK.name())
                                .encodedUdpPayloadBytes((long) ackBytes.length)
                                .eventOutcome("SOCKET_SEND_SUCCEEDED").build());
            }
            return null;
        }
        if (msg.getType() != MessageType.FINISH) {
            return null; // ignore unrelated control messages
        }
        metricsCollector.observeReceiverFinishArrival();
        log(EventType.FINISH_RECEIVED, TransferEvent.Direction.INBOUND,
                TransferEvent.Details.builder().messageType(MessageType.FINISH.name())
                        .validationResult("ATTRIBUTABLE").build());

        boolean allReceived = tracker.isTransferComplete();
        boolean verified = false;
        String errorMessage = "Not all chunks received";

        if (allReceived) {
            String recomputedHash = FileHashUtil.sha256Hex(outputFilePath);
            verified = recomputedHash.equals(msg.getSha256Hex());
            metricsCollector.observeReceiverIntegrityVerification(verified);
            log(verified ? EventType.INTEGRITY_VERIFIED : EventType.INTEGRITY_FAILED,
                    TransferEvent.Direction.LOCAL,
                    TransferEvent.Details.builder().integrityVerified(verified)
                            .eventOutcome(verified ? "SHA256_MATCH" : "SHA256_MISMATCH").build());
            errorMessage = verified ? null : "SHA-256 mismatch";
        }

        ControlMessage finishAck = ControlMessage.createFinishAck(msg.getTransferId(), verified, errorMessage);
        metricsCollector.observeReceiverFinishAckAttempt();
        byte[] finishAckBytes = finishAck.toJson().getBytes(StandardCharsets.UTF_8);
        log(EventType.FINISH_ACK_ATTEMPT, TransferEvent.Direction.OUTBOUND,
                TransferEvent.Details.builder().messageType(MessageType.FINISH_ACK.name())
                        .integrityVerified(verified).build());
        channel.send(finishAckBytes,
                datagram.senderAddress(), datagram.senderPort());
        log(EventType.FINISH_ACK_EMITTED, TransferEvent.Direction.OUTBOUND,
                TransferEvent.Details.builder().messageType(MessageType.FINISH_ACK.name())
                        .encodedUdpPayloadBytes((long) finishAckBytes.length)
                        .integrityVerified(verified)
                        .eventOutcome("SOCKET_SEND_SUCCEEDED").build());

        TransferResult result = verified ? TransferResult.success(-1)
                : TransferResult.failure(errorMessage);
        return new CompletionOutcome(result, msg, finishAck);
    }

    private void recoverDuplicateFinishes(CompletionOutcome completion,
                                          UdpChannel.ReceivedDatagram startDatagram) throws IOException {
        long startedAt = System.nanoTime();
        while (true) {
            UdpChannel.ReceivedDatagram datagram = receiveBeforeDeadline(startedAt, completionGraceMillis);
            if (datagram == null) {
                metricsCollector.observeReceiverCompletionGraceExpired();
                log(EventType.COMPLETION_RECOVERY_ENDED, TransferEvent.Direction.LOCAL,
                        TransferEvent.Details.builder().eventOutcome("GRACE_EXPIRED").build());
                return;
            }
            if (!isExpectedPeer(datagram, startDatagram)) {
                continue;
            }
            ControlMessage message;
            try {
                message = ControlMessage.fromJson(
                        new String(datagram.data(), StandardCharsets.UTF_8));
            } catch (JsonParseException | IllegalStateException e) {
                continue;
            }
            ControlMessage original = completion.finishRequest();
            if (message.getType() == MessageType.FINISH
                    && original.getTransferId().equals(message.getTransferId())
                    && java.util.Objects.equals(original.getSha256Hex(), message.getSha256Hex())) {
                metricsCollector.observeReceiverDuplicateFinishArrival();
                metricsCollector.observeReceiverCachedFinishAckResendAttempt();
                log(EventType.DUPLICATE_FINISH_RECEIVED, TransferEvent.Direction.INBOUND,
                        TransferEvent.Details.builder().messageType(MessageType.FINISH.name())
                                .eventOutcome("DUPLICATE").build());
                byte[] acknowledgementBytes = completion.finishAcknowledgement().toJson()
                        .getBytes(StandardCharsets.UTF_8);
                log(EventType.FINISH_ACK_ATTEMPT, TransferEvent.Direction.OUTBOUND,
                        TransferEvent.Details.builder().messageType(MessageType.FINISH_ACK.name())
                                .eventOutcome("CACHED_RESEND").build());
                channel.send(acknowledgementBytes,
                        datagram.senderAddress(), datagram.senderPort());
                log(EventType.FINISH_ACK_EMITTED, TransferEvent.Direction.OUTBOUND,
                        TransferEvent.Details.builder().messageType(MessageType.FINISH_ACK.name())
                                .encodedUdpPayloadBytes((long) acknowledgementBytes.length)
                                .eventOutcome("CACHED_RESEND_SUCCEEDED").build());
            }
        }
    }

    private record CompletionOutcome(TransferResult result, ControlMessage finishRequest,
                                     ControlMessage finishAcknowledgement) {}

    private long handleDataPacket(byte[] data, int chunkSize, int totalChunks, RandomAccessFile outputFile,
                                   ReceiverSequenceTracker tracker, UUID transferId,
                                   UdpChannel.ReceivedDatagram datagram,
                                   UdpChannel.ReceivedDatagram startDatagram) throws IOException {
        if (!isExpectedPeer(datagram, startDatagram)) {
            return -1;
        }
        Packet packet;
        try {
            packet = PacketDecoder.decode(data);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
        if (packet.getType() != MessageType.DATA || !transferId.equals(packet.getTransferId())) {
            return -1;
        }
        if (packet.getSeqNum() < 0 || packet.getSeqNum() >= totalChunks
                || !PacketValidator.isValid(packet)) {
            metricsCollector.observeReceiverDataValidationFailure();
            log(EventType.DATA_INVALID, TransferEvent.Direction.LOCAL,
                    TransferEvent.Details.builder().messageType(MessageType.DATA.name())
                            .sequenceNumber(packet.getSeqNum()).validationResult("REJECTED").build());
            return -1;
        }

        metricsCollector.observeReceiverDataArrival();
        log(EventType.DATA_RECEIVED, TransferEvent.Direction.INBOUND,
                TransferEvent.Details.builder().messageType(MessageType.DATA.name())
                        .sequenceNumber(packet.getSeqNum())
                        .payloadBytes((long) packet.getPayload().length)
                        .encodedUdpPayloadBytes((long) data.length).build());
        log(EventType.DATA_VALIDATED, TransferEvent.Direction.LOCAL,
                TransferEvent.Details.builder().messageType(MessageType.DATA.name())
                        .sequenceNumber(packet.getSeqNum()).validationResult("ACCEPTED").build());
        ReceiverSequenceTracker.DataReceiveOutcome outcome = tracker.onDataReceived(packet.getSeqNum());
        long writtenAt = -1;

        if (outcome == ReceiverSequenceTracker.DataReceiveOutcome.ACCEPTED) {
            metricsCollector.observeReceiverDataAccepted();
            log(EventType.DATA_ACCEPTED, TransferEvent.Direction.LOCAL,
                    TransferEvent.Details.builder().messageType(MessageType.DATA.name())
                            .sequenceNumber(packet.getSeqNum())
                            .sequenceOutcome(outcome.name()).build());
            outputFile.seek((long) packet.getSeqNum() * chunkSize);
            outputFile.write(packet.getPayload());
            metricsCollector.observeReceiverPayloadWritten(packet.getPayload().length);
            log(EventType.PAYLOAD_WRITTEN, TransferEvent.Direction.LOCAL,
                    TransferEvent.Details.builder().messageType(MessageType.DATA.name())
                            .sequenceNumber(packet.getSeqNum())
                            .payloadBytes((long) packet.getPayload().length)
                            .eventOutcome("WRITE_SUCCEEDED").build());
            writtenAt = System.nanoTime();
        } else if (outcome == ReceiverSequenceTracker.DataReceiveOutcome.DUPLICATE) {
            metricsCollector.observeReceiverDataDuplicate();
            log(EventType.DATA_DUPLICATE, TransferEvent.Direction.LOCAL,
                    TransferEvent.Details.builder().messageType(MessageType.DATA.name())
                            .sequenceNumber(packet.getSeqNum())
                            .sequenceOutcome(outcome.name()).build());
        } else {
            metricsCollector.observeReceiverDataOutOfOrderDiscarded();
            log(EventType.DATA_OUT_OF_ORDER_DISCARDED, TransferEvent.Direction.LOCAL,
                    TransferEvent.Details.builder().messageType(MessageType.DATA.name())
                            .sequenceNumber(packet.getSeqNum())
                            .sequenceOutcome(outcome.name()).build());
        }

        if (outcome != ReceiverSequenceTracker.DataReceiveOutcome.OUT_OF_ORDER_DISCARDED) {
            Packet ack = Packet.createAck(transferId, tracker.getCumulativeAckSeqNum());
            metricsCollector.observeReceiverDataAckAttempt();
            byte[] encodedAck = PacketEncoder.encode(ack);
            log(EventType.DATA_ACK_ATTEMPT, TransferEvent.Direction.OUTBOUND,
                    TransferEvent.Details.builder().messageType(MessageType.ACK.name())
                            .ackNumber(ack.getSeqNum()).build());
            channel.send(encodedAck, datagram.senderAddress(), datagram.senderPort());
            log(EventType.DATA_ACK_EMITTED, TransferEvent.Direction.OUTBOUND,
                    TransferEvent.Details.builder().messageType(MessageType.ACK.name())
                            .ackNumber(ack.getSeqNum())
                            .encodedUdpPayloadBytes((long) encodedAck.length)
                            .eventOutcome("SOCKET_SEND_SUCCEEDED").build());
        }
        return writtenAt;
    }

    private static boolean isExpectedPeer(UdpChannel.ReceivedDatagram datagram,
                                          UdpChannel.ReceivedDatagram startDatagram) {
        InetAddress address = datagram.senderAddress();
        return address.equals(startDatagram.senderAddress())
                && datagram.senderPort() == startDatagram.senderPort();
    }

    /** Small test seam for deterministic file-open/write failures. */
    protected RandomAccessFile openOutputFile(String outputFilePath) throws IOException {
        return new RandomAccessFile(outputFilePath, "rw");
    }

    private static int computeTotalChunks(long fileSize, int chunkSize) {
        return (int) Math.max(1, (fileSize + chunkSize - 1) / chunkSize);
    }

    private void recordTerminal(boolean success, String reason) {
        log(success ? EventType.RECEIVER_SUCCEEDED : EventType.RECEIVER_FAILED,
                TransferEvent.Direction.LOCAL,
                TransferEvent.Details.builder()
                        .eventOutcome(success ? "SUCCESS" : "FAILED")
                        .failureReason(success ? null : reason).build());
        eventLogger.finalizeSession(metricsCollector.liveSnapshot(),
                success, success ? null : reason);
    }

    private void log(EventType type, TransferEvent.Direction direction,
                     TransferEvent.Details details) {
        eventLogger.record(transferContext, type, direction, details);
    }

    private static String terminalReason(TransferResult result) {
        return result.getFailureReason() == null
                ? result.getMessage()
                : result.getFailureReason().name();
    }

    private static String describeException(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getClass().getSimpleName() + ": " + message;
    }
}
