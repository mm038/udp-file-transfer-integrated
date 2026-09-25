package nettransfer.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Structural datagram checks are separate from CRC validity and negotiated transfer chunk sizes. */
class PacketFramingTest {
    private static final UUID TRANSFER_ID = UUID.fromString("be452482-010a-433a-b90a-67e011cfad46");

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 17, Packet.MAX_PAYLOAD_SIZE})
    void acceptsExactEmptyPartialAndMaximumDataFrames(int payloadLength) throws Exception {
        byte[] payload = new byte[payloadLength];
        Arrays.fill(payload, (byte) 0x5a);
        byte[] encoded = PacketEncoder.encode(Packet.createData(TRANSFER_ID, 3, payload));

        Packet decoded = PacketDecoder.decode(encoded);

        assertEquals(MessageType.DATA, decoded.getType());
        assertEquals(TRANSFER_ID, decoded.getTransferId());
        assertEquals(3, decoded.getSeqNum());
        assertArrayEquals(payload, decoded.getPayload());
        assertTrue(PacketValidator.isValid(decoded));
    }

    @Test
    void acceptsHeaderOnlyAckIncludingMinusOneCumulativeSequence() throws Exception {
        byte[] encoded = PacketEncoder.encode(Packet.createAck(TRANSFER_ID, -1));
        Packet decoded = PacketDecoder.decode(encoded);
        assertEquals(Packet.HEADER_SIZE, encoded.length);
        assertEquals(MessageType.ACK, decoded.getType());
        assertEquals(-1, decoded.getSeqNum());
        assertEquals(0, decoded.getPayload().length);
        assertTrue(PacketValidator.isValid(decoded));
    }

    @Test
    void rejectsTrailingBytesAfterAnOtherwiseCrcValidDataFrame() throws Exception {
        byte[] valid = PacketEncoder.encode(Packet.createData(TRANSFER_ID, 0, new byte[]{1, 2}));
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IOException.class, () -> PacketDecoder.decode(trailing));
    }

    @Test
    void rejectsTrailingBytesAfterAnOtherwiseCrcValidAckFrame() throws Exception {
        byte[] valid = PacketEncoder.encode(Packet.createAck(TRANSFER_ID, 0));
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IOException.class, () -> PacketDecoder.decode(trailing));
    }

    @Test
    void rejectsTruncatedDeclaredPayload() throws Exception {
        byte[] valid = PacketEncoder.encode(Packet.createData(TRANSFER_ID, 0, new byte[]{1, 2}));
        byte[] truncated = Arrays.copyOf(valid, valid.length - 1);
        assertThrows(IOException.class, () -> PacketDecoder.decode(truncated));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 16, 22, 26})
    void rejectsIncompleteHeaderWithIOException(int length) {
        assertThrows(IOException.class, () -> PacketDecoder.decode(new byte[length]));
    }

    @ParameterizedTest
    @ValueSource(ints = {7, 127, 255})
    void rejectsUnknownBinaryTypeWithIOExceptionRatherThanOrdinalException(int ordinal) {
        byte[] frame = new byte[Packet.HEADER_SIZE];
        frame[0] = (byte) ordinal;
        assertThrows(IOException.class, () -> PacketDecoder.decode(frame));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4, 5, 6})
    void rejectsControlMessageTypesInBinaryPacketFrames(int ordinal) {
        byte[] frame = frame(MessageType.values()[ordinal], new byte[0]);
        assertThrows(IOException.class, () -> PacketDecoder.decode(frame));
    }

    @Test
    void rejectsOverMaximumDataEvenWhenLengthAndCrcAgree() {
        byte[] frame = frame(MessageType.DATA, new byte[Packet.MAX_PAYLOAD_SIZE + 1]);
        assertEquals(Packet.HEADER_SIZE + Packet.MAX_PAYLOAD_SIZE + 1, frame.length);
        assertThrows(IOException.class, () -> PacketDecoder.decode(frame));
    }

    @Test
    void rejectsAckPayloadEvenWhenLengthAndCrcAgree() {
        byte[] frame = frame(MessageType.ACK, new byte[]{42});
        assertThrows(IOException.class, () -> PacketDecoder.decode(frame));
    }

    @Test
    void crcMismatchRemainsTheValidatorsResponsibility() throws Exception {
        byte[] frame = PacketEncoder.encode(Packet.createData(TRANSFER_ID, 0, new byte[]{1, 2}));
        frame[frame.length - 1] ^= 1;
        Packet decoded = PacketDecoder.decode(frame);
        assertFalse(PacketValidator.isValid(decoded));
    }

    private static byte[] frame(MessageType type, byte[] payload) {
        return ByteBuffer.allocate(Packet.HEADER_SIZE + payload.length)
                .put((byte) type.ordinal())
                .putLong(TRANSFER_ID.getMostSignificantBits())
                .putLong(TRANSFER_ID.getLeastSignificantBits())
                .putInt(0)
                .putShort((short) payload.length)
                .putInt((int) PacketEncoder.computeCrc32(type, TRANSFER_ID, 0, payload))
                .put(payload)
                .array();
    }
}
