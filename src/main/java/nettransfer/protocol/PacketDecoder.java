package nettransfer.protocol;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Decodes an exactly framed binary DATA or ACK datagram into a Packet.
 * Structural checks run before payload allocation. CRC validation remains
 * PacketValidator's responsibility, and sequence semantics belong to the engines.
 */
public class PacketDecoder {

    public static Packet decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < Packet.HEADER_SIZE) {
            throw new IOException("Binary packet is shorter than its required header");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));

        int typeOrdinal = in.readUnsignedByte();
        if (typeOrdinal != MessageType.DATA.ordinal() && typeOrdinal != MessageType.ACK.ordinal()) {
            throw new IOException("Binary packet type must be DATA or ACK");
        }
        MessageType type = MessageType.values()[typeOrdinal];

        long mostSigBits = in.readLong();
        long leastSigBits = in.readLong();
        UUID transferId = new UUID(mostSigBits, leastSigBits);

        int seqNum = in.readInt();
        int payloadLen = in.readUnsignedShort();
        long crc32 = in.readInt() & 0xFFFFFFFFL;

        if (payloadLen > Packet.MAX_PAYLOAD_SIZE) {
            throw new IOException("Binary payload exceeds the maximum packet payload size");
        }
        if (bytes.length != Packet.HEADER_SIZE + payloadLen) {
            throw new IOException("Datagram length does not match the declared binary payload length");
        }
        if (type == MessageType.ACK && payloadLen != 0) {
            throw new IOException("ACK packets must not contain a payload");
        }

        byte[] payload = new byte[payloadLen];
        in.readFully(payload);

        return new Packet(type, transferId, seqNum, payload, crc32);
    }
}
