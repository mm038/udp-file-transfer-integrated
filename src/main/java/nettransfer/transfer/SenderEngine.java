package nettransfer.transfer;

import com.google.gson.JsonParseException;
import nettransfer.integrity.FileHashUtil;
import nettransfer.metrics.MetricsCollector;
import nettransfer.metrics.EventLogger;
import nettransfer.metrics.EventType;
import nettransfer.metrics.LiveMetricsProvider;
import nettransfer.metrics.LiveMetricsSnapshot;
import nettransfer.metrics.TransferEvent;
import nettransfer.metrics.TransferConfiguration;
import nettransfer.metrics.TransferContext;
import nettransfer.metrics.TransferMetrics;
import nettransfer.net.UdpChannel;
import nettransfer.protocol.ControlMessage;
import nettransfer.protocol.MessageType;
import nettransfer.protocol.Packet;
import nettransfer.protocol.PacketDecoder;
import nettransfer.protocol.PacketEncoder;
import nettransfer.protocol.PacketValidator;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Drives one complete sender-side file transfer: START handshake, chunked
 * DATA sending under sliding-window flow control, timeout-driven Go-Back-N
 * retransmission, and a final FINISH/FINISH_ACK integrity handshake.
 *
 * Composes Stages 1-10 -- introduces no new protocol logic itself, only
 * the order in which existing, individually-tested classes are called.
 *
 * Known Stage 10.5 simplification: the socket receive-timeout used while
 * waiting for ACKs is set equal to the packet retransmission timeout
 * (PacketTimeoutTracker's timeoutMillis). This means, with windowSize > 1,
 * the sender's ACK-polling granularity is only as fine as the timeout
 * itself, rather than a separately-tuned shorter poll interval. Acceptable
 * for this project's scope; noted here as a documented limitation rather
 * than silently glossed over.
 */
public class SenderEngine implements LiveMetricsProvider {

    public static final int DEFAULT_START_HANDSHAKE_TIMEOUT_MS = 1000;
    public static final int DEFAULT_START_RETRY_LIMIT = 5;
    public static final int DEFAULT_FINISH_HANDSHAKE_TIMEOUT_MS = 1_000;
    public static final int DEFAULT_FINISH_RETRY_LIMIT = 5;

    private final UdpChannel channel;
    private final InetAddress destAddress;
    private final int destPort;
    private final int chunkSize;
    private final int windowSize;
    private final int timeoutMillis;
    private final int retryLimit;
    private final int startHandshakeTimeoutMillis;
    private final int startRetryLimit;
    private final int finishHandshakeTimeoutMillis;
    private final int finishRetryLimit;
    private volatile TransferContext transferContext;
    private volatile MetricsCollector metricsCollector;
    private volatile EventLogger eventLogger = EventLogger.disabled();
    private TransferImpairmentLifecycle impairmentLifecycle;

    public SenderEngine(UdpChannel channel, InetAddress destAddress, int destPort,
                         int chunkSize, int windowSize, int timeoutMillis, int retryLimit) {
        this(channel, destAddress, destPort, chunkSize, windowSize, timeoutMillis, retryLimit,
                DEFAULT_START_HANDSHAKE_TIMEOUT_MS, DEFAULT_START_RETRY_LIMIT,
                DEFAULT_FINISH_HANDSHAKE_TIMEOUT_MS, DEFAULT_FINISH_RETRY_LIMIT, null);
    }

    /** Integration point for a trusted application-supplied identity context. */
    public SenderEngine(UdpChannel channel, InetAddress destAddress, int destPort,
                        int chunkSize, int windowSize, int timeoutMillis, int retryLimit,
                        TransferContext context) {
        this(channel, destAddress, destPort, chunkSize, windowSize, timeoutMillis, retryLimit,
                DEFAULT_START_HANDSHAKE_TIMEOUT_MS, DEFAULT_START_RETRY_LIMIT,
                DEFAULT_FINISH_HANDSHAKE_TIMEOUT_MS, DEFAULT_FINISH_RETRY_LIMIT, context);
    }

    public SenderEngine(UdpChannel channel, InetAddress destAddress, int destPort,
                        int chunkSize, int windowSize, int timeoutMillis, int retryLimit,
                        int startHandshakeTimeoutMillis, int startRetryLimit) {
        this(channel, destAddress, destPort, chunkSize, windowSize, timeoutMillis, retryLimit,
                startHandshakeTimeoutMillis, startRetryLimit,
                DEFAULT_FINISH_HANDSHAKE_TIMEOUT_MS, DEFAULT_FINISH_RETRY_LIMIT, null);
    }

    /** Integration point for trusted IDs plus all effective sender settings. */
    public SenderEngine(UdpChannel channel, InetAddress destAddress, int destPort,
                        int chunkSize, int windowSize, int timeoutMillis, int retryLimit,
                        int startHandshakeTimeoutMillis, int startRetryLimit,
                        TransferContext context) {
        this(channel, destAddress, destPort, chunkSize, windowSize, timeoutMillis, retryLimit,
                startHandshakeTimeoutMillis, startRetryLimit,
                DEFAULT_FINISH_HANDSHAKE_TIMEOUT_MS, DEFAULT_FINISH_RETRY_LIMIT, context);
    }

    /** Integration point for trusted IDs plus all effective sender settings. */
    public SenderEngine(UdpChannel channel, InetAddress destAddress, int destPort,
                        int chunkSize, int windowSize, int timeoutMillis, int retryLimit,
                        int startHandshakeTimeoutMillis, int startRetryLimit,
                        int finishHandshakeTimeoutMillis, int finishRetryLimit,
                        TransferContext context) {
        if (startHandshakeTimeoutMillis <= 0 || startRetryLimit < 0
                || finishHandshakeTimeoutMillis <= 0 || finishRetryLimit < 0) {
            throw new IllegalArgumentException("Handshake timeouts must be positive and retry limits nonnegative");
        }
        this.channel = channel;
        this.destAddress = destAddress;
        this.destPort = destPort;
        this.chunkSize = chunkSize;
        this.windowSize = windowSize;
        this.timeoutMillis = timeoutMillis;
        this.retryLimit = retryLimit;
        this.startHandshakeTimeoutMillis = startHandshakeTimeoutMillis;
        this.startRetryLimit = startRetryLimit;
        this.finishHandshakeTimeoutMillis = finishHandshakeTimeoutMillis;
        this.finishRetryLimit = finishRetryLimit;
        this.transferContext = initializeContext(context);
        this.metricsCollector = new MetricsCollector(this.transferContext);
    }

    public TransferResult sendFile(String filePath) throws IOException {
        impairmentLifecycle = null;
        transferContext = transferContext.toBuilder()
                .protocolTransferId(null)
                .originalFilename(null)
                .fileSizeBytes(null)
                .unavailableReason("protocol_transfer_id", "not yet established")
                .unavailableReason("original_filename", "input file not yet opened")
                .unavailableReason("file_size_bytes", "input file not yet opened")
                .build();
        metricsCollector = new MetricsCollector(transferContext);
        metricsCollector.observeEndpointAwaitingStart();
        metricsCollector.beginEndpointEmissionAccounting();
        channel.setSuccessfulSendObserver(metricsCollector::observeUdpPayloadEmitted);
        try (FileChunker chunker = new FileChunker(filePath, chunkSize)) {
            String originalFilename = new File(filePath).getName();
            TransferContext.Builder observedFile = transferContext.toBuilder()
                    .originalFilename(originalFilename)
                    .fileSizeBytes(chunker.getFileSize())
                    .clearUnavailableReason("original_filename")
                    .clearUnavailableReason("file_size_bytes");
            if (transferContext.getFileAttribution() == null) {
                observedFile.fileAttribution("source:" + originalFilename)
                        .clearUnavailableReason("file_attribution");
            }
            transferContext = observedFile.build();
            metricsCollector.updateContext(transferContext);
            ControlMessage start = ControlMessage.createStart(
                    originalFilename, chunker.getFileSize(), chunkSize);
            transferContext = transferContext.withProtocolTransferId(
                    UUID.fromString(start.getTransferId()));
            metricsCollector.updateContext(transferContext);
            impairmentLifecycle = TransferImpairmentLifecycle.begin(channel, transferContext,
                    metricsCollector, eventLogger, destAddress, destPort);
            TransferResult handshakeFailure = awaitStartAcknowledgement(start);
            if (handshakeFailure != null) {
                return terminal(handshakeFailure);
            }

            UUID transferId = UUID.fromString(start.getTransferId());
            SenderWindow window = new SenderWindow(chunker.getTotalChunks(), windowSize);
            PacketTimeoutTracker timeoutTracker = new PacketTimeoutTracker(timeoutMillis);
            RetransmissionController retransmission = new RetransmissionController(timeoutTracker, retryLimit);

            channel.setReceiveTimeoutMillis(timeoutMillis);

            while (!window.isComplete()) {
                while (window.canSendMore()) {
                    int seqNum = window.getNextSeqNumToSend();
                    sendChunk(chunker, seqNum, transferId, false);
                    window.markSent();
                    timeoutTracker.recordSent(seqNum);
                }

                try {
                    UdpChannel.ReceivedDatagram datagram = channel.receive();
                    Packet ackPacket = decodePacketOrNull(datagram.data());
                    if (ackPacket != null) {
                        boolean attributableAck = isAttributableAck(
                                ackPacket, datagram, transferId, destAddress, destPort);
                        if (attributableAck) {
                            metricsCollector.observeDataAckArrival();
                            log(EventType.DATA_ACK_RECEIVED, TransferEvent.Direction.INBOUND,
                                    TransferEvent.Details.builder()
                                            .messageType(MessageType.ACK.name())
                                            .ackNumber(ackPacket.getSeqNum())
                                            .validationResult("ATTRIBUTABLE").build());
                        }
                        boolean acceptableAck = isAcceptableAck(
                                ackPacket, datagram, transferId, destAddress, destPort, window);
                        if (acceptableAck) {
                            int oldBase = window.getBase();
                            int ackedThrough = ackPacket.getSeqNum();
                            window.onAckReceived(ackedThrough);
                            for (int seq = oldBase; seq <= ackedThrough; seq++) {
                                timeoutTracker.recordAcked(seq);
                            }
                            if (window.getBase() > oldBase) {
                                retransmission.notifyProgress();
                            }
                            metricsCollector.observeDataAckProgress(oldBase, window.getBase());
                            metricsCollector.observePacketsAcknowledged(
                                    Math.max(0, window.getBase() - oldBase));
                            long newlyAcknowledged = Math.max(0, window.getBase() - oldBase);
                            log(EventType.DATA_ACK_ACCEPTED, TransferEvent.Direction.LOCAL,
                                    TransferEvent.Details.builder()
                                            .messageType(MessageType.ACK.name())
                                            .ackNumber(ackPacket.getSeqNum())
                                            .validationResult("ACCEPTED")
                                            .eventOutcome(newlyAcknowledged == 0
                                                    ? "DUPLICATE_OR_STALE" : "WINDOW_ADVANCED")
                                            .newlyAcknowledgedPackets(newlyAcknowledged).build());
                        } else if (attributableAck) {
                            log(EventType.DATA_ACK_REJECTED, TransferEvent.Direction.LOCAL,
                                    TransferEvent.Details.builder()
                                            .messageType(MessageType.ACK.name())
                                            .ackNumber(ackPacket.getSeqNum())
                                            .validationResult("REJECTED").build());
                        }
                    }
                } catch (SocketTimeoutException e) {
                    // No ACK within the timeout window -- fall through to check per-packet timeouts.
                }

                int recoveryRoundsBeforeCheck = retransmission.getRetransmissionRoundCount();
                List<Integer> toResend = retransmission.checkAndRetransmit();
                if (retransmission.getRetransmissionRoundCount() > recoveryRoundsBeforeCheck) {
                    metricsCollector.observeDataTimeout();
                    metricsCollector.observeRecoveryDecision();
                    log(EventType.DATA_TIMEOUT, TransferEvent.Direction.LOCAL,
                            TransferEvent.Details.builder()
                                    .sequenceNumber(window.getBase())
                                    .eventOutcome("DEADLINE_EXPIRED").build());
                    log(EventType.RECOVERY_ROUND, TransferEvent.Direction.LOCAL,
                            TransferEvent.Details.builder()
                                    .sequenceNumber(window.getBase())
                                    .attemptNumber(retransmission.getRetransmissionRoundCount())
                                    .eventOutcome(retransmission.hasFailed()
                                            ? "RETRY_LIMIT_EXCEEDED" : "GO_BACK_N").build());
                }
                if (retransmission.hasFailed()) {
                    return terminal(TransferResult.failure(
                            "Retry limit (" + retryLimit + ") exceeded -- transfer failed"));
                }
                for (int seq : toResend) {
                    resendChunk(chunker, seq, transferId);
                }
            }

            metricsCollector.observeSenderAwaitingFinish();
            return terminal(sendFinishAndAwaitVerification(filePath, start.getTransferId()));
        } catch (IOException | RuntimeException exception) {
            finishImpairment();
            metricsCollector.observeError();
            channel.setSuccessfulSendObserver(null);
            String reason = describeException(exception);
            metricsCollector.observeTerminalOutcome(false, reason);
            recordTerminal(false, reason);
            throw exception;
        }
    }

    /** Returns the latest immutable sender view; the protocol ID is null before START is created. */
    public TransferContext getTransferContext() {
        return transferContext;
    }

    public UUID getProtocolTransferId() {
        return transferContext.getProtocolTransferId();
    }

    /** Returns a current immutable sender metrics snapshot; running snapshots are provisional. */
    public TransferMetrics getMetricsSnapshot() {
        return metricsCollector.snapshot();
    }

    public MetricsCollector.SenderObservations getSenderObservations() {
        return metricsCollector.senderObservations();
    }

    public MetricsCollector.EndpointEmissionObservations getEndpointEmissionObservations() {
        return metricsCollector.endpointEmissionObservations();
    }

    @Override
    public LiveMetricsSnapshot getLiveMetricsSnapshot() {
        return metricsCollector.liveSnapshot();
    }

    /** Enables one durable endpoint-local JSONL session. Call before sendFile. */
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
        if (supplied != null && supplied.getEndpoint() != TransferContext.Endpoint.SENDER) {
            throw new IllegalArgumentException("sender requires a SENDER transfer context");
        }
        if (supplied != null && supplied.getProtocolTransferId() != null) {
            throw new IllegalArgumentException("sender protocol ID must come from the START message");
        }

        TransferConfiguration provided = supplied == null ? null : supplied.getConfiguration();
        requireMatching("chunkSizeBytes", provided == null ? null : provided.getChunkSizeBytes(), chunkSize);
        requireMatching("windowPackets", provided == null ? null : provided.getWindowPackets(), windowSize);
        requireMatching("timeoutMs", provided == null ? null : provided.getTimeoutMs(), timeoutMillis);
        requireMatching("retryLimit", provided == null ? null : provided.getRetryLimit(), retryLimit);
        requireMatching("startHandshakeTimeoutMs",
                provided == null ? null : provided.getStartHandshakeTimeoutMs(), startHandshakeTimeoutMillis);
        requireMatching("startRetryLimit",
                provided == null ? null : provided.getStartRetryLimit(), startRetryLimit);
        requireMatching("finishHandshakeTimeoutMs",
                provided == null ? null : provided.getFinishHandshakeTimeoutMs(), finishHandshakeTimeoutMillis);
        requireMatching("finishRetryLimit",
                provided == null ? null : provided.getFinishRetryLimit(), finishRetryLimit);

        TransferConfiguration.Builder configuration = provided == null
                ? TransferConfiguration.builder()
                : provided.toBuilder();
        configuration.chunkSizeBytes((long) chunkSize)
                .windowPackets((long) windowSize)
                .timeoutMs((long) timeoutMillis)
                .retryLimit((long) retryLimit)
                .startHandshakeTimeoutMs((long) startHandshakeTimeoutMillis)
                .startRetryLimit((long) startRetryLimit)
                .finishHandshakeTimeoutMs((long) finishHandshakeTimeoutMillis)
                .finishRetryLimit((long) finishRetryLimit);

        TransferContext.Builder context = supplied == null
                ? TransferContext.builder(TransferContext.Endpoint.SENDER)
                : supplied.toBuilder();
        TransferConfiguration effective = TransferImpairmentLifecycle.effectiveConfiguration(
                configuration.build(), channel.getImpairmentSettings());
        context.configuration(effective)
                .unavailableReason("protocol_transfer_id", "not yet established")
                .unavailableReason("file_size_bytes", "input file not yet opened")
                .unavailableReason("original_filename", "input file not yet opened");
        if (supplied == null || supplied.getFileAttribution() == null) {
            context.unavailableReason("file_attribution", "input file not yet selected");
        }
        if (effective.getWindowBytesRequested() == null) {
            context.unavailableReason(
                    "window_bytes_requested", "engine was configured directly in DATA packets");
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

    private TransferResult awaitStartAcknowledgement(ControlMessage start) throws IOException {
        byte[] startBytes = start.toJson().getBytes(StandardCharsets.UTF_8);
        long timeoutNanos = (long) startHandshakeTimeoutMillis * 1_000_000L;
        for (int attempt = 0; attempt <= startRetryLimit; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                return TransferResult.failure("START handshake interrupted");
            }
            metricsCollector.observeStartAttempt();
            log(EventType.START_ATTEMPT, TransferEvent.Direction.OUTBOUND,
                    TransferEvent.Details.builder().messageType(MessageType.START.name())
                            .attemptNumber(attempt + 1).build());
            channel.send(startBytes, destAddress, destPort);
            log(EventType.START_EMITTED, TransferEvent.Direction.OUTBOUND,
                    TransferEvent.Details.builder().messageType(MessageType.START.name())
                            .attemptNumber(attempt + 1)
                            .encodedUdpPayloadBytes((long) startBytes.length)
                            .eventOutcome("SOCKET_SEND_SUCCEEDED").build());
            long startedAt = System.nanoTime();
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    return TransferResult.failure("START handshake interrupted");
                }
                long remaining = timeoutNanos - (System.nanoTime() - startedAt);
                if (remaining <= 0) {
                    break;
                }
                channel.setReceiveTimeoutMillis((int) Math.max(1,
                        Math.min(250, (remaining + 999_999L) / 1_000_000L)));
                UdpChannel.ReceivedDatagram datagram;
                try {
                    datagram = channel.receive();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (System.nanoTime() - startedAt >= timeoutNanos) {
                    break;
                }
                if (!destAddress.equals(datagram.senderAddress()) || destPort != datagram.senderPort()) {
                    continue;
                }
                ControlMessage acknowledgement;
                try {
                    acknowledgement = ControlMessage.fromJson(
                            new String(datagram.data(), StandardCharsets.UTF_8));
                } catch (JsonParseException | IllegalStateException e) {
                    continue;
                }
                if (acknowledgement.getType() != MessageType.START_ACK
                        || !start.getTransferId().equals(acknowledgement.getTransferId())) {
                    continue;
                }
                log(EventType.START_ACK_RECEIVED, TransferEvent.Direction.INBOUND,
                        TransferEvent.Details.builder().messageType(MessageType.START_ACK.name())
                                .validationResult("ATTRIBUTABLE").build());
                if (!acknowledgement.isAccepted()) {
                    log(EventType.START_REJECTED, TransferEvent.Direction.LOCAL,
                            TransferEvent.Details.builder().messageType(MessageType.START_ACK.name())
                                    .eventOutcome("REJECTED")
                                    .failureReason(acknowledgement.getErrorMessage()).build());
                    return TransferResult.failure("START rejected: " + acknowledgement.getErrorMessage());
                }
                metricsCollector.observeStartAcknowledged();
                log(EventType.START_ACCEPTED, TransferEvent.Direction.LOCAL,
                        TransferEvent.Details.builder().messageType(MessageType.START_ACK.name())
                                .validationResult("ACCEPTED").eventOutcome("ESTABLISHED").build());
                return null;
            }
        }
        return TransferResult.failure(TransferResult.FailureReason.START_HANDSHAKE_TIMEOUT,
                "START_HANDSHAKE_TIMEOUT: no matching START_ACK after " + (startRetryLimit + 1) + " attempts");
    }

    private void sendChunk(FileChunker chunker, int seqNum, UUID transferId,
                           boolean retransmission) throws IOException {
        byte[] payload = chunker.readChunk(seqNum);
        Packet dataPacket = Packet.createData(transferId, seqNum, payload);
        byte[] encoded = PacketEncoder.encode(dataPacket);
        metricsCollector.observeDataSendAttempt(retransmission);
        if (retransmission) {
            log(EventType.DATA_RETRANSMISSION, TransferEvent.Direction.LOCAL,
                    TransferEvent.Details.builder().messageType(MessageType.DATA.name())
                            .sequenceNumber(seqNum).payloadBytes((long) payload.length)
                            .retransmission(true).build());
        }
        log(EventType.DATA_SEND_ATTEMPT, TransferEvent.Direction.OUTBOUND,
                TransferEvent.Details.builder().messageType(MessageType.DATA.name())
                        .sequenceNumber(seqNum).payloadBytes((long) payload.length)
                        .encodedUdpPayloadBytes((long) encoded.length)
                        .retransmission(retransmission).build());
        channel.send(encoded, destAddress, destPort);
        log(EventType.DATA_EMITTED, TransferEvent.Direction.OUTBOUND,
                TransferEvent.Details.builder().messageType(MessageType.DATA.name())
                        .sequenceNumber(seqNum).payloadBytes((long) payload.length)
                        .encodedUdpPayloadBytes((long) encoded.length)
                        .retransmission(retransmission)
                        .eventOutcome("SOCKET_SEND_SUCCEEDED").build());
        if (retransmission) {
            metricsCollector.observeRetransmittedDataEmission(seqNum);
        } else {
            metricsCollector.observeOriginalDataEmission(seqNum, payload.length);
        }
    }

    private void resendChunk(FileChunker chunker, int seqNum, UUID transferId) throws IOException {
        sendChunk(chunker, seqNum, transferId, true);
    }

    private TransferResult sendFinishAndAwaitVerification(String filePath, String transferId) throws IOException {
        String hash = FileHashUtil.sha256Hex(filePath);
        ControlMessage finish = ControlMessage.createFinish(transferId, hash);
        byte[] finishBytes = finish.toJson().getBytes(StandardCharsets.UTF_8);
        long timeoutNanos = (long) finishHandshakeTimeoutMillis * 1_000_000L;

        for (int attempt = 0; attempt <= finishRetryLimit; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                return TransferResult.failure("FINISH handshake interrupted");
            }
            metricsCollector.observeFinishAttempt();
            log(EventType.FINISH_ATTEMPT, TransferEvent.Direction.OUTBOUND,
                    TransferEvent.Details.builder().messageType(MessageType.FINISH.name())
                            .attemptNumber(attempt + 1).build());
            channel.send(finishBytes, destAddress, destPort);
            log(EventType.FINISH_EMITTED, TransferEvent.Direction.OUTBOUND,
                    TransferEvent.Details.builder().messageType(MessageType.FINISH.name())
                            .attemptNumber(attempt + 1)
                            .encodedUdpPayloadBytes((long) finishBytes.length)
                            .eventOutcome("SOCKET_SEND_SUCCEEDED").build());
            long startedAt = System.nanoTime();
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    return TransferResult.failure("FINISH handshake interrupted");
                }
                long remaining = timeoutNanos - (System.nanoTime() - startedAt);
                if (remaining <= 0) {
                    break;
                }
                channel.setReceiveTimeoutMillis((int) Math.max(1,
                        Math.min(250, (remaining + 999_999L) / 1_000_000L)));
                UdpChannel.ReceivedDatagram datagram;
                try {
                    datagram = channel.receive();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (System.nanoTime() - startedAt >= timeoutNanos) {
                    break;
                }
                ControlMessage finishAck;
                try {
                    finishAck = ControlMessage.fromJson(
                            new String(datagram.data(), StandardCharsets.UTF_8));
                } catch (JsonParseException | IllegalStateException e) {
                    continue;
                }
                if (!isAttributableFinishAck(
                        finishAck, datagram, transferId, destAddress, destPort)) {
                    continue;
                }
                log(EventType.FINISH_ACK_RECEIVED, TransferEvent.Direction.INBOUND,
                        TransferEvent.Details.builder().messageType(MessageType.FINISH_ACK.name())
                                .validationResult("ATTRIBUTABLE").build());
                if (finishAck.isVerified()) {
                    metricsCollector.observeFinishAcknowledged();
                    log(EventType.FINISH_ACK_ACCEPTED, TransferEvent.Direction.LOCAL,
                            TransferEvent.Details.builder().messageType(MessageType.FINISH_ACK.name())
                                    .validationResult("ACCEPTED").integrityVerified(true).build());
                    return TransferResult.success(-1);
                }
                String error = finishAck.getErrorMessage();
                log(EventType.FINISH_ACK_REJECTED, TransferEvent.Direction.LOCAL,
                        TransferEvent.Details.builder().messageType(MessageType.FINISH_ACK.name())
                                .validationResult("REJECTED").integrityVerified(false)
                                .failureReason(error).build());
                return TransferResult.failure(error == null || error.isBlank()
                        ? "FINISH_ACK verification failed"
                        : "FINISH_ACK verification failed: " + error);
            }
        }
        return TransferResult.failure(TransferResult.FailureReason.FINISH_HANDSHAKE_TIMEOUT,
                "FINISH_HANDSHAKE_TIMEOUT: no matching FINISH_ACK after "
                        + (finishRetryLimit + 1) + " attempts");
    }

    private TransferResult terminal(TransferResult result) {
        finishImpairment();
        String reason = result.getFailureReason() == null
                ? result.getMessage()
                : result.getFailureReason().name();
        channel.setSuccessfulSendObserver(null);
        metricsCollector.observeTerminalOutcome(result.isSuccess(), reason);
        recordTerminal(result.isSuccess(), reason);
        return result;
    }

    private void recordTerminal(boolean success, String reason) {
        log(success ? EventType.TRANSFER_SUCCEEDED : EventType.TRANSFER_FAILED,
                TransferEvent.Direction.LOCAL,
                TransferEvent.Details.builder()
                        .eventOutcome(success ? "SUCCESS" : "FAILED")
                        .failureReason(success ? null : reason).build());
        eventLogger.finalizeSession(metricsCollector.liveSnapshot(),
                success, success ? null : reason);
    }

    private void finishImpairment() {
        if (impairmentLifecycle != null) {
            impairmentLifecycle.finish();
        }
    }

    private void log(EventType type, TransferEvent.Direction direction,
                     TransferEvent.Details details) {
        eventLogger.record(transferContext, type, direction, details);
    }

    private static String describeException(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getClass().getSimpleName() + ": " + message;
    }

    static Packet decodePacketOrNull(byte[] data) {
        try {
            return PacketDecoder.decode(data);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    static boolean isAttributableAck(
            Packet packet,
            UdpChannel.ReceivedDatagram datagram,
            UUID expectedTransferId,
            InetAddress expectedAddress,
            int expectedPort) {
        return packet.getType() == MessageType.ACK
                && expectedTransferId.equals(packet.getTransferId())
                && expectedAddress.equals(datagram.senderAddress())
                && expectedPort == datagram.senderPort();
    }

    static boolean isAcceptableAck(
            Packet packet,
            UdpChannel.ReceivedDatagram datagram,
            UUID expectedTransferId,
            InetAddress expectedAddress,
            int expectedPort,
            SenderWindow window) {
        return isAttributableAck(packet, datagram, expectedTransferId, expectedAddress, expectedPort)
                && datagram.data().length == Packet.HEADER_SIZE
                && packet.getPayload().length == 0
                && PacketValidator.isValid(packet)
                && window.isValidCumulativeAck(packet.getSeqNum());
    }

    static boolean isAttributableFinishAck(
            ControlMessage message,
            UdpChannel.ReceivedDatagram datagram,
            String expectedTransferId,
            InetAddress expectedAddress,
            int expectedPort) {
        return message.getType() == MessageType.FINISH_ACK
                && expectedTransferId.equals(message.getTransferId())
                && message.getVerified() != null
                && expectedAddress.equals(datagram.senderAddress())
                && expectedPort == datagram.senderPort();
    }
}
