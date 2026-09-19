package nettransfer.protocol;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Encodes a Packet into the exact binary layout documented in PROTOCOL.md:
 * type(1) + transferId(16) + seqNum(4) + payloadLen(2) + crc32(4) + payload.
 */
public class PacketEncoder {

    public static byte[] encode(Packet packet) throws IOException {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(byteStream);

        out.writeByte(packet.getType().ordinal());
        out.writeLong(packet.getTransferId().getMostSignificantBits());
        out.writeLong(packet.getTransferId().getLeastSignificantBits());
        out.writeInt(packet.getSeqNum());
        out.writeShort(packet.getPayload().length);
        out.writeInt((int) packet.getCrc32());
        out.write(packet.getPayload());

        return byteStream.toByteArray();
    }

    public static long computeCrc32(MessageType type, UUID transferId, int seqNum, byte[] payload) {
        ByteBuffer header = ByteBuffer.allocate(1 + 16 + 4 + 2);
        header.put((byte) type.ordinal());
        header.putLong(transferId.getMostSignificantBits());
        header.putLong(transferId.getLeastSignificantBits());
        header.putInt(seqNum);
        header.putShort((short) payload.length);

        CRC32 crc = new CRC32();
        crc.update(header.array());
        crc.update(payload);
        return crc.getValue();
    }
}