package nettransfer.transfer;

/**
 * Tracks the receiver's view of "everything received so far, with no
 * gaps" -- exactly the value that goes into a cumulative ACK.
 *
 * highestContiguousSeqReceived starts at -1, meaning "nothing received
 * yet." This -1 is purely an internal sentinel and is never itself sent
 * as an ACK: the first ACK is only sent after chunk 0 arrives, at which
 * point the value becomes 0. PROTOCOL.md explicitly leaves "the
 * bootstrapping value of the first ACK before any data has arrived" out
 * of scope for exactly this reason -- it never needs to go on the wire.
 *
 * Stage 9: now handles duplicate and out-of-order DATA arrivals, per the
 * project's Go-Back-N design (chosen in Stage 8):
 *   - Duplicate (seqNum already received): re-ACK the current cumulative
 *     value. This covers the case where the original DATA was fine but
 *     its ACK was lost or delayed, so the sender retransmitted -- the
 *     receiver already has the data, it just needs to say so again.
 *   - Out-of-order (seqNum arrives ahead of what's expected, i.e. there's
 *     a gap before it): discarded silently, NOT buffered. This is the
 *     Go-Back-N choice -- buffering out-of-order arrivals for later use
 *     is Selective Repeat's job, which this project does not implement.
 *     The sender's timeout/retransmission (Stage 7/8) will resend the
 *     missing chunk(s), after which this same seqNum will arrive again,
 *     in order, and be accepted normally.
 */
public class ReceiverSequenceTracker {

    /** What happened when a DATA packet's seqNum was processed. */
    public enum DataReceiveOutcome {
        /** seqNum was exactly the next expected one; state advanced. */
        ACCEPTED,
        /** seqNum was already received before; state unchanged, re-ACK it. */
        DUPLICATE,
        /** seqNum arrived ahead of a gap; discarded, not buffered, no ACK sent. */
        OUT_OF_ORDER_DISCARDED
    }

    private int highestContiguousSeqReceived = -1;

    /**
     * Call when a DATA chunk with this seqNum has arrived and passed CRC
     * validation. Returns what happened so the caller (a future
     * ReceiverEngine) knows whether to send an ACK and what to log.
     */
    public DataReceiveOutcome onDataReceived(int seqNum) {
        if (seqNum == highestContiguousSeqReceived + 1) {
            highestContiguousSeqReceived = seqNum;
            return DataReceiveOutcome.ACCEPTED;
        }
        if (seqNum <= highestContiguousSeqReceived) {
            return DataReceiveOutcome.DUPLICATE;
        }
        // seqNum > highestContiguousSeqReceived + 1: a gap exists before it
        return DataReceiveOutcome.OUT_OF_ORDER_DISCARDED;
    }

    /** The value to put in the next ACK's seqNum field. -1 before anything has arrived. */
    public int getCumulativeAckSeqNum() {
        return highestContiguousSeqReceived;
    }

    public boolean hasReceivedThrough(int seqNum) {
        return highestContiguousSeqReceived >= seqNum;
    }
}