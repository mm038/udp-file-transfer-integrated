package nettransfer.transfer;

import nettransfer.integrity.FileHashUtil;
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
public class SenderEngine {

    private final UdpChannel channel;
    private final InetAddress destAddress;
    private final int destPort;
    private final int chunkSize;
    private final int windowSize;
    private final int timeoutMillis;
    private final int retryLimit;

    public SenderEngine(UdpChannel channel, InetAddress destAddress, int destPort,
                         int chunkSize, int windowSize, int timeoutMillis, int retryLimit) {
        this.channel = channel;
        this.destAddress = destAddress;
        this.destPort = destPort;
        this.chunkSize = chunkSize;
        this.windowSize = windowSize;
        this.timeoutMillis = timeoutMillis;
        this.retryLimit = retryLimit;
    }

    public TransferResult sendFile(String filePath) throws IOException {
        try (FileChunker chunker = new FileChunker(filePath, chunkSize)) {

            ControlMessage start = ControlMessage.createStart(
                    new File(filePath).getName(), chunker.getFileSize(), chunkSize);
            channel.send(start.toJson().getBytes(StandardCharsets.UTF_8), destAddress, destPort);

            UdpChannel.ReceivedDatagram ackDatagram = channel.receive();
            ControlMessage startAck = ControlMessage.fromJson(
                    new String(ackDatagram.data(), StandardCharsets.UTF_8));

            if (startAck.getType() != MessageType.START_ACK || !startAck.isAccepted()) {
                return TransferResult.failure("START rejected: " + startAck.getErrorMessage());
            }

            UUID transferId = UUID.fromString(start.getTransferId());
            SenderWindow window = new SenderWindow(chunker.getTotalChunks(), windowSize);
            PacketTimeoutTracker timeoutTracker = new PacketTimeoutTracker(timeoutMillis);
            RetransmissionController retransmission = new RetransmissionController(timeoutTracker, retryLimit);

            channel.setReceiveTimeoutMillis(timeoutMillis);

            while (!window.isComplete()) {
                while (window.canSendMore()) {
                    int seqNum = window.getNextSeqNumToSend();
                    sendChunk(chunker, seqNum, transferId);
                    window.markSent();
                    timeoutTracker.recordSent(seqNum);
                }

                try {
                    UdpChannel.ReceivedDatagram datagram = channel.receive();
                    Packet ackPacket = PacketDecoder.decode(datagram.data());
                    if (ackPacket.getType() == MessageType.ACK && PacketValidator.isValid(ackPacket)) {
                        int oldBase = window.getBase();
                        int ackedThrough = ackPacket.getSeqNum();
                        window.onAckReceived(ackedThrough);
                        for (int seq = oldBase; seq <= ackedThrough; seq++) {
                            timeoutTracker.recordAcked(seq);
                        }
                        if (window.getBase() > oldBase) {
                            retransmission.notifyProgress();
                        }
                    }
                    // Invalid-CRC ACK: ignored, same as a lost ACK, per PROTOCOL.md.
                } catch (SocketTimeoutException e) {
                    // No ACK within the timeout window -- fall through to check per-packet timeouts.
                }

                List<Integer> toResend = retransmission.checkAndRetransmit();
                if (retransmission.hasFailed()) {
                    return TransferResult.failure(
                            "Retry limit (" + retryLimit + ") exceeded -- transfer failed");
                }
                for (int seq : toResend) {
                    resendChunk(chunker, seq, transferId);
                }
            }

            return sendFinishAndAwaitVerification(filePath, start.getTransferId());
        }
    }

    private void sendChunk(FileChunker chunker, int seqNum, UUID transferId) throws IOException {
        byte[] payload = chunker.readChunk(seqNum);
        Packet dataPacket = Packet.createData(transferId, seqNum, payload);
        channel.send(PacketEncoder.encode(dataPacket), destAddress, destPort);
    }

    private void resendChunk(FileChunker chunker, int seqNum, UUID transferId) throws IOException {
        sendChunk(chunker, seqNum, transferId);
    }

    private TransferResult sendFinishAndAwaitVerification(String filePath, String transferId) throws IOException {
        String hash = FileHashUtil.sha256Hex(filePath);
        ControlMessage finish = ControlMessage.createFinish(transferId, hash);
        channel.send(finish.toJson().getBytes(StandardCharsets.UTF_8), destAddress, destPort);

        UdpChannel.ReceivedDatagram reply = channel.receive();
        ControlMessage finishAck = ControlMessage.fromJson(new String(reply.data(), StandardCharsets.UTF_8));

        if (finishAck.getType() == MessageType.FINISH_ACK && finishAck.isVerified()) {
            return TransferResult.success(-1); // caller can re-derive count if needed
        }
        return TransferResult.failure("FINISH_ACK verification failed: " + finishAck.getErrorMessage());
    }
}