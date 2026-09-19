package nettransfer.protocol;

import com.google.gson.JsonSyntaxException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ControlMessageTest {

    @Test
    void startMessageRoundTripsCorrectly() {
        ControlMessage original = ControlMessage.createStart("data.bin", 204800L, 1024);

        String json = original.toJson();
        ControlMessage decoded = ControlMessage.fromJson(json);

        assertEquals(MessageType.START, decoded.getType());
        assertEquals("data.bin", decoded.getFilename());
        assertEquals(204800L, decoded.getFileSize());
        assertEquals(1024, decoded.getChunkSize());
        assertEquals(original.getTransferId(), decoded.getTransferId());
    }

    @Test
    void acceptedStartAckRoundTrips() {
        ControlMessage ack = ControlMessage.createStartAck("abc-123", true, null);
        ControlMessage decoded = ControlMessage.fromJson(ack.toJson());

        assertEquals(MessageType.START_ACK, decoded.getType());
        assertTrue(decoded.isAccepted());
        assertEquals("abc-123", decoded.getTransferId());
    }

    @Test
    void rejectedStartAckCarriesReason() {
        ControlMessage ack = ControlMessage.createStartAck("abc-123", false, "chunk size too large");
        ControlMessage decoded = ControlMessage.fromJson(ack.toJson());

        assertFalse(decoded.isAccepted());
        assertEquals("chunk size too large", decoded.getErrorMessage());
    }

    @Test
    void malformedJsonThrowsException() {
        assertThrows(JsonSyntaxException.class, () -> ControlMessage.fromJson("{not valid json"));
    }

    @Test
    void twoStartMessagesGetDifferentTransferIds() {
        ControlMessage a = ControlMessage.createStart("a.bin", 100, 512);
        ControlMessage b = ControlMessage.createStart("b.bin", 200, 512);

        assertNotEquals(a.getTransferId(), b.getTransferId());
    }

    @Test
    void createFinishCarriesSha256Hash() {
        ControlMessage finish = ControlMessage.createFinish("t-123", "abc123hash");
        assertEquals(MessageType.FINISH, finish.getType());
        assertEquals("t-123", finish.getTransferId());
        assertEquals("abc123hash", finish.getSha256Hex());
    }

    @Test
    void finishRoundTripsThroughJson() {
        ControlMessage finish = ControlMessage.createFinish("t-456", "deadbeef");
        ControlMessage decoded = ControlMessage.fromJson(finish.toJson());
        assertEquals(MessageType.FINISH, decoded.getType());
        assertEquals("deadbeef", decoded.getSha256Hex());
    }

    @Test
    void createFinishAckVerifiedTrueHasNoErrorMessage() {
        ControlMessage ack = ControlMessage.createFinishAck("t-789", true, null);
        assertEquals(MessageType.FINISH_ACK, ack.getType());
        assertTrue(ack.isVerified());
        assertNull(ack.getErrorMessage());
    }

    @Test
    void createFinishAckVerifiedFalseCarriesErrorMessage() {
        ControlMessage ack = ControlMessage.createFinishAck("t-999", false, "SHA-256 mismatch");
        assertFalse(ack.isVerified());
        assertEquals("SHA-256 mismatch", ack.getErrorMessage());
    }
}