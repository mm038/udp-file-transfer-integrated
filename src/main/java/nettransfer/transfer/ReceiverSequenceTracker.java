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
 * Stage 6 scope: happy path only. This assumes chunks arrive in order
 * with no loss or duplication -- out-of-order and duplicate handling is
 * Stage 9.
 */
public class ReceiverSequenceTracker {

    private int highestContiguousSeqReceived = -1;

    /** Call when a DATA chunk with this seqNum has arrived and passed CRC validation. */
    public void onDataReceived(int seqNum) {
        if (seqNum == highestContiguousSeqReceived + 1) {
            highestContiguousSeqReceived = seqNum;
        }
        // A seqNum that isn't the next expected one (arrived early, or is a
        // duplicate) is not handled yet -- Stage 9's job. Happy-path tests
        // never trigger this branch.
    }

    /** The value to put in the next ACK's seqNum field. -1 before anything has arrived. */
    public int getCumulativeAckSeqNum() {
        return highestContiguousSeqReceived;
    }

    public boolean hasReceivedThrough(int seqNum) {
        return highestContiguousSeqReceived >= seqNum;
    }
}