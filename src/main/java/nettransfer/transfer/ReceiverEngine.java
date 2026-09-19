package nettransfer.transfer;

import nettransfer.integrity.FileHashUtil;
import nettransfer.net.UdpChannel;
import nettransfer.protocol.ControlMessage;
import nettransfer.protocol.MessageType;
import nettransfer.protocol.Packet;
import nettransfer.protocol.PacketDecoder;
import nettransfer.protocol.PacketEncoder;
import nettransfer.protocol.PacketValidator;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
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
public class ReceiverEngine {

    private final UdpChannel channel;

    public ReceiverEngine(UdpChannel channel) {
        this.channel = channel;
    }

    public TransferResult receiveFile(String outputFilePath) throws IOException {
        UdpChannel.ReceivedDatagram startDatagram = channel.receive();
        ControlMessage start = ControlMessage.fromJson(
                new String(startDatagram.data(), StandardCharsets.UTF_8));

        if (start.getType() != MessageType.START) {
            return TransferResult.failure("Expected START, got " + start.getType());
        }

        boolean chunkSizeOk = start.getChunkSize() > 0 && start.getChunkSize() <= Packet.MAX_PAYLOAD_SIZE;
        ControlMessage startAck = chunkSizeOk
                ? ControlMessage.createStartAck(start.getTransferId(), true, null)
                : ControlMessage.createStartAck(start.getTransferId(), false,
                        "chunkSize must be between 1 and " + Packet.MAX_PAYLOAD_SIZE);

        channel.send(startAck.toJson().getBytes(StandardCharsets.UTF_8),
                startDatagram.senderAddress(), startDatagram.senderPort());

        if (!chunkSizeOk) {
            return TransferResult.failure("Rejected START: " + startAck.getErrorMessage());
        }

        int totalChunks = computeTotalChunks(start.getFileSize(), start.getChunkSize());
        UUID transferId = UUID.fromString(start.getTransferId());
        ReceiverSequenceTracker tracker = new ReceiverSequenceTracker(totalChunks);

        try (RandomAccessFile outputFile = new RandomAccessFile(outputFilePath, "rw")) {
            outputFile.setLength(start.getFileSize());

            while (true) {
                UdpChannel.ReceivedDatagram datagram = channel.receive();
                byte[] data = datagram.data();
                if (data.length == 0) {
                    continue;
                }

                if (data[0] == '{') {
                    TransferResult result = handleControlMessage(
                            data, outputFilePath, tracker, datagram);
                    if (result != null) {
                        return result;
                    }
                    continue;
                }

                handleDataPacket(data, start.getChunkSize(), outputFile, tracker,
                        transferId, datagram);
            }
        }
    }

    private TransferResult handleControlMessage(byte[] data, String outputFilePath,
                                                 ReceiverSequenceTracker tracker,
                                                 UdpChannel.ReceivedDatagram datagram) throws IOException {
        ControlMessage msg = ControlMessage.fromJson(new String(data, StandardCharsets.UTF_8));
        if (msg.getType() != MessageType.FINISH) {
            return null; // ignore anything else (e.g. a stray duplicate START) -- out of scope here
        }

        boolean allReceived = tracker.isTransferComplete();
        boolean verified = false;
        String errorMessage = "Not all chunks received";

        if (allReceived) {
            String recomputedHash = FileHashUtil.sha256Hex(outputFilePath);
            verified = recomputedHash.equals(msg.getSha256Hex());
            errorMessage = verified ? null : "SHA-256 mismatch";
        }

        ControlMessage finishAck = ControlMessage.createFinishAck(msg.getTransferId(), verified, errorMessage);
        channel.send(finishAck.toJson().getBytes(StandardCharsets.UTF_8),
                datagram.senderAddress(), datagram.senderPort());

        return verified ? TransferResult.success(-1)
                : TransferResult.failure(errorMessage);
    }

    private void handleDataPacket(byte[] data, int chunkSize, RandomAccessFile outputFile,
                                   ReceiverSequenceTracker tracker, UUID transferId,
                                   UdpChannel.ReceivedDatagram datagram) throws IOException {
        Packet packet = PacketDecoder.decode(data);
        if (packet.getType() != MessageType.DATA || !PacketValidator.isValid(packet)) {
            return; // wrong type or corrupted -- discard silently, per PROTOCOL.md
        }

        ReceiverSequenceTracker.DataReceiveOutcome outcome = tracker.onDataReceived(packet.getSeqNum());

        if (outcome == ReceiverSequenceTracker.DataReceiveOutcome.ACCEPTED) {
            outputFile.seek((long) packet.getSeqNum() * chunkSize);
            outputFile.write(packet.getPayload());
        }

        if (outcome != ReceiverSequenceTracker.DataReceiveOutcome.OUT_OF_ORDER_DISCARDED) {
            Packet ack = Packet.createAck(transferId, tracker.getCumulativeAckSeqNum());
            channel.send(PacketEncoder.encode(ack), datagram.senderAddress(), datagram.senderPort());
        }
    }

    private static int computeTotalChunks(long fileSize, int chunkSize) {
        return (int) Math.max(1, (fileSize + chunkSize - 1) / chunkSize);
    }
}