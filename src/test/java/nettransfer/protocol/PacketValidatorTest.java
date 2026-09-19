package nettransfer.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PacketValidatorTest {

    @Test
    void freshDataPacketIsValid() {
        Packet packet = Packet.createData(UUID.randomUUID(), 3, "hello".getBytes());
        assertTrue(PacketValidator.isValid(packet));
    }

    @Test
    void freshAckPacketIsValid() {
        Packet packet = Packet.createAck(UUID.randomUUID(), 12);
        assertTrue(PacketValidator.isValid(packet));
    }

    @Test
    void corruptedPayloadIsInvalid() throws IOException {
        Packet original = Packet.createData(UUID.randomUUID(), 1, "test data".getBytes());
        byte[] encoded = PacketEncoder.encode(original);

        // flip a bit inside the payload region (after the 27-byte header)
        encoded[encoded.length - 1] ^= 0xFF;

        Packet corrupted = PacketDecoder.decode(encoded);
        assertFalse(PacketValidator.isValid(corrupted));
    }

    @Test
    void corruptedSeqNumIsInvalid() throws IOException {
        Packet original = Packet.createData(UUID.randomUUID(), 5, "payload".getBytes());
        byte[] encoded = PacketEncoder.encode(original);

        // seqNum occupies bytes 17-20 (after 1-byte type + 16-byte transferId)
        encoded[17] ^= 0xFF;

        Packet corrupted = PacketDecoder.decode(encoded);
        assertFalse(PacketValidator.isValid(corrupted));
    }

    @Test
    void corruptedAckSeqNumIsInvalid() throws IOException {
        // Confirms CRC coverage extends to ACK packets too, per PROTOCOL.md
        Packet original = Packet.createAck(UUID.randomUUID(), 8);
        byte[] encoded = PacketEncoder.encode(original);

        encoded[17] ^= 0xFF; // corrupt the cumulative seqNum field

        Packet corrupted = PacketDecoder.decode(encoded);
        assertFalse(PacketValidator.isValid(corrupted));
    }

    @Test
    void corruptedCrcFieldItselfIsInvalid() throws IOException {
        // If the CRC field itself gets corrupted in transit, it now disagrees
        // with the (unchanged, correct) recomputed CRC -- still correctly caught.
        Packet original = Packet.createData(UUID.randomUUID(), 2, "abc".getBytes());
        byte[] encoded = PacketEncoder.encode(original);

        // crc32 occupies bytes 23-26 (after type[1] + transferId[16] + seqNum[4] + payloadLen[2])
        encoded[23] ^= 0xFF;

        Packet corrupted = PacketDecoder.decode(encoded);
        assertFalse(PacketValidator.isValid(corrupted));
    }
}