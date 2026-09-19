package nettransfer.protocol;

/**
 * Checks whether a decoded Packet's contents match its own CRC-32 field.
 *
 * PacketDecoder deliberately does NOT check this itself (see its javadoc) —
 * decoding and integrity-checking are separate responsibilities. This class
 * is the "integrity-checking" half: it recomputes the CRC from exactly the
 * fields that arrived (type, transferId, seqNum, payloadLen, payload) and
 * compares it against the crc32 field the packet claims to have.
 *
 * A mismatch means the packet was corrupted in transit (bit flip, etc.).
 * Per PROTOCOL.md, a corrupted packet — DATA or ACK — is discarded and
 * treated the same as if it had never arrived at all (the sender's
 * existing timeout/retransmit logic handles the rest; no separate
 * "corrupted" code path is needed at the protocol level). Metrics logging
 * is the one place DATA-corruption and ACK-corruption are told apart
 * (CORRUPT_ACK vs a plain corrupted-DATA event) — that's Stage 11's job,
 * not this class's.
 */
public class PacketValidator {

    /** Returns true if packet's stored CRC matches what its contents actually hash to. */
    public static boolean isValid(Packet packet) {
        long recomputed = PacketEncoder.computeCrc32(
                packet.getType(),
                packet.getTransferId(),
                packet.getSeqNum(),
                packet.getPayload());
        return recomputed == packet.getCrc32();
    }
}