package nettransfer.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PacketEncoderDecoderTest {

    @Test
    void dataPacketRoundTrips() throws IOException {
        UUID transferId = UUID.randomUUID();
        byte[] payload = "hello world".getBytes();
        Packet original = Packet.createData(transferId, 7, payload);

        byte[] encoded = PacketEncoder.encode(original);
        Packet decoded = PacketDecoder.decode(encoded);

        assertEquals(MessageType.DATA, decoded.getType());
        assertEquals(transferId, decoded.getTransferId());
        assertEquals(7, decoded.getSeqNum());
        assertArrayEquals(payload, decoded.getPayload());
        assertEquals(original.getCrc32(), decoded.getCrc32());
    }

    @Test
    void ackPacketRoundTrips() throws IOException {
        UUID transferId = UUID.randomUUID();
        Packet original = Packet.createAck(transferId, 42);

        byte[] encoded = PacketEncoder.encode(original);
        Packet decoded = PacketDecoder.decode(encoded);

        assertEquals(MessageType.ACK, decoded.getType());
        assertEquals(transferId, decoded.getTransferId());
        assertEquals(42, decoded.getSeqNum());
        assertEquals(0, decoded.getPayload().length);
    }

    @Test
    void ackPacketIsExactly27Bytes() throws IOException {
        Packet ack = Packet.createAck(UUID.randomUUID(), 0);
        byte[] encoded = PacketEncoder.encode(ack);
        assertEquals(Packet.HEADER_SIZE, encoded.length);
    }

    @Test
    void maxPayloadSizeAccepted() {
        byte[] maxPayload = new byte[Packet.MAX_PAYLOAD_SIZE];
        assertDoesNotThrow(() -> Packet.createData(UUID.randomUUID(), 0, maxPayload));
    }

    @Test
    void overMaxPayloadSizeRejected() {
        byte[] tooBig = new byte[Packet.MAX_PAYLOAD_SIZE + 1];
        assertThrows(IllegalArgumentException.class,
                () -> Packet.createData(UUID.randomUUID(), 0, tooBig));
    }

    @Test
    void encodedDataPacketLengthMatchesHeaderPlusPayload() throws IOException {
        byte[] payload = new byte[500];
        Packet data = Packet.createData(UUID.randomUUID(), 3, payload);
        byte[] encoded = PacketEncoder.encode(data);
        assertEquals(Packet.HEADER_SIZE + payload.length, encoded.length);
    }

    @Test
    void corruptedByteFlipsChangeDecodedCrcButDecodeStillSucceeds() throws IOException {
        // Decoder does NOT verify CRC (that's Stage 5) — it should decode
        // whatever bytes arrived, correct or not, and let the caller check.
        UUID transferId = UUID.randomUUID();
        Packet original = Packet.createData(transferId, 1, "test".getBytes());
        byte[] encoded = PacketEncoder.encode(original);

        // flip a bit in the payload
        encoded[encoded.length - 1] ^= 0xFF;

        Packet decoded = PacketDecoder.decode(encoded);
        long recomputed = PacketEncoder.computeCrc32(
                decoded.getType(), decoded.getTransferId(), decoded.getSeqNum(), decoded.getPayload());

        assertNotEquals(decoded.getCrc32(), recomputed, "corrupted payload should not match stored CRC");
    }
}