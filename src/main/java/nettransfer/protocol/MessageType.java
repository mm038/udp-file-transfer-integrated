package nettransfer.protocol;

/**
 * The seven message types our protocol uses, per the assignment's
 * suggested naming. START/START_ACK/FINISH/FINISH_ACK/ERROR are
 * infrequent "control" messages (JSON-encoded, see ControlMessage).
 * DATA/ACK are high-frequency and will use a compact binary header
 * instead (Stage 4) — but they share this same enum so every part
 * of the protocol refers to message types consistently.
 */
public enum MessageType {
    START,
    START_ACK,
    DATA,
    ACK,
    FINISH,
    FINISH_ACK,
    ERROR
}