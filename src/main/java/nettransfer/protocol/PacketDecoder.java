package nettransfer.protocol;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Decodes raw bytes back into a Packet. Deliberately does NOT verify the
 * CRC here — just reads whatever bits were on the wire, corrupted or not.
 * Actual CRC validation is Stage 5's job.
 */
public class PacketDecoder {

    public static Packet decode(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));

        int typeOrdinal = in.readUnsignedByte();
        MessageType type = MessageType.values()[typeOrdinal];

        long mostSigBits = in.readLong();
        long leastSigBits = in.readLong();
        UUID transferId = new UUID(mostSigBits, leastSigBits);

        int seqNum = in.readInt();
        int payloadLen = in.readUnsignedShort();
        long crc32 = in.readInt() & 0xFFFFFFFFL;

        byte[] payload = new byte[payloadLen];
        in.readFully(payload);

        return new Packet(type, transferId, seqNum, payload, crc32);
    }
}