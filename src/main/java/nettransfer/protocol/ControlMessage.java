package nettransfer.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.UUID;

/**
 * Represents one JSON-encoded control message: START, START_ACK,
 * FINISH, FINISH_ACK, or ERROR. One flat class handles all five types
 * (see design discussion) rather than five separate classes, since
 * that would need extra Gson machinery (a TypeAdapter) to figure out
 * which class to decode into before it's even read the message.
 *
 * Not every field is used by every type -- e.g. a START_ACK ignores
 * filename/fileSize/chunkSize. That's an accepted trade-off for
 * simplicity at this message volume (a handful of these per transfer).
 */
public class ControlMessage {

    // One shared Gson instance, reused for all encode/decode calls.
    // Creating a new Gson() is cheap but pointless to repeat every call.
    private static final Gson GSON = new Gson();

    private MessageType type;
    private String transferId;

    // START-only fields
    private String filename;
    private long fileSize;
    private int chunkSize;

    // START_ACK-only field
    private boolean accepted;

    // FINISH-only field: whole-file SHA-256, computed by the sender before
    // any DATA is sent, carried here so the receiver can compare once it
    // has reassembled the file (Stage 10).
    private String sha256Hex;

    // FINISH_ACK-only field: did the receiver's recomputed hash match?
    private boolean verified;

    // Used by START_ACK (when rejected), FINISH_ACK (when not verified), and ERROR
    private String errorMessage;

    /**
     * No-argument constructor required by Gson: when decoding JSON back
     * into an object, Gson creates a blank instance first, then fills in
     * the fields by reflection. Without this, fromJson() would fail.
     */
    public ControlMessage() {
    }

    // Private constructor -- external code must use the factory methods
    // below, never "new ControlMessage(...)" directly. This guarantees
    // every message has a valid type and transferId set correctly for
    // that type, rather than callers having to remember which fields
    // matter for which type.
    private ControlMessage(MessageType type, String transferId) {
        this.type = type;
        this.transferId = transferId;
    }

    /** Sender uses this to propose a new transfer. Generates a fresh UUID. */
    public static ControlMessage createStart(String filename, long fileSize, int chunkSize) {
        ControlMessage msg = new ControlMessage(MessageType.START, UUID.randomUUID().toString());
        msg.filename = filename;
        msg.fileSize = fileSize;
        msg.chunkSize = chunkSize;
        return msg;
    }

    /** Receiver uses this to accept or reject a proposed transfer. */
    public static ControlMessage createStartAck(String transferId, boolean accepted, String errorMessage) {
        ControlMessage msg = new ControlMessage(MessageType.START_ACK, transferId);
        msg.accepted = accepted;
        msg.errorMessage = errorMessage;
        return msg;
    }

    /** Sender uses this once every chunk has been sent and acknowledged, carrying the whole-file hash. */
    public static ControlMessage createFinish(String transferId, String sha256Hex) {
        ControlMessage msg = new ControlMessage(MessageType.FINISH, transferId);
        msg.sha256Hex = sha256Hex;
        return msg;
    }

    /** Receiver uses this to report whether its recomputed hash matched the sender's. */
    public static ControlMessage createFinishAck(String transferId, boolean verified, String errorMessage) {
        ControlMessage msg = new ControlMessage(MessageType.FINISH_ACK, transferId);
        msg.verified = verified;
        msg.errorMessage = errorMessage;
        return msg;
    }

    /** Either side uses this to report a protocol-level problem. */
    public static ControlMessage createError(String transferId, String errorMessage) {
        ControlMessage msg = new ControlMessage(MessageType.ERROR, transferId);
        msg.errorMessage = errorMessage;
        return msg;
    }

    /** Encodes this message as a JSON string, ready to turn into bytes and send. */
    public String toJson() {
        return GSON.toJson(this);
    }

    /**
     * Decodes a JSON string back into a ControlMessage.
     * @throws JsonSyntaxException if the input isn't valid JSON at all.
     */
    public static ControlMessage fromJson(String json) {
        ControlMessage msg = GSON.fromJson(json, ControlMessage.class);
        if (msg == null) {
            throw new JsonSyntaxException("Decoded to null (input was empty or just \"null\")");
        }
        return msg;
    }

    // Getters only -- no setters. Once built via a factory method, a
    // ControlMessage doesn't change. This is deliberate immutability.
    public MessageType getType() { return type; }
    public String getTransferId() { return transferId; }
    public String getFilename() { return filename; }
    public long getFileSize() { return fileSize; }
    public int getChunkSize() { return chunkSize; }
    public boolean isAccepted() { return accepted; }
    public String getSha256Hex() { return sha256Hex; }
    public boolean isVerified() { return verified; }
    public String getErrorMessage() { return errorMessage; }

    @Override
    public String toString() {
        return toJson();
    }
}