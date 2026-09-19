package nettransfer.protocol;

import java.util.UUID;

/**
 * A DATA or ACK message, in the binary wire format documented in
 * PROTOCOL.md. Immutable data holder — build one via createData/createAck
 * when sending, or via PacketDecoder when reconstructing one that arrived
 * over the network.
 */
public final class Packet {

    public static final int HEADER_SIZE = 27; // 1 (type) + 16 (transferId) + 4 (seqNum) + 2 (payloadLen) + 4 (crc32)
    public static final int MAX_PAYLOAD_SIZE = 1024;

    private final MessageType type;
    private final UUID transferId;
    private final int seqNum;
    private final byte[] payload;
    private final long crc32; // stored as a long so we can hold the full unsigned 32-bit range

    Packet(MessageType type, UUID transferId, int seqNum, byte[] payload, long crc32) {
        this.type = type;
        this.transferId = transferId;
        this.seqNum = seqNum;
        this.payload = payload;
        this.crc32 = crc32;
    }

    public static Packet createData(UUID transferId, int seqNum, byte[] payload) {
        if (payload.length > MAX_PAYLOAD_SIZE) {
            throw new IllegalArgumentException(
                    "Payload exceeds MAX_PAYLOAD_SIZE (" + MAX_PAYLOAD_SIZE + "): " + payload.length + " bytes");
        }
        long crc = PacketEncoder.computeCrc32(MessageType.DATA, transferId, seqNum, payload);
        return new Packet(MessageType.DATA, transferId, seqNum, payload, crc);
    }

    /**
     * cumulativeSeqNum = highest contiguous chunk index received so far.
     * Under windowSize=1 this collapses to "the seqNum of the single chunk
     * just acknowledged" — same field, no special-casing needed.
     */
    public static Packet createAck(UUID transferId, int cumulativeSeqNum) {
        byte[] empty = new byte[0];
        long crc = PacketEncoder.computeCrc32(MessageType.ACK, transferId, cumulativeSeqNum, empty);
        return new Packet(MessageType.ACK, transferId, cumulativeSeqNum, empty, crc);
    }

    public MessageType getType() { return type; }
    public UUID getTransferId() { return transferId; }
    public int getSeqNum() { return seqNum; }
    public byte[] getPayload() { return payload; }
    public long getCrc32() { return crc32; }
}