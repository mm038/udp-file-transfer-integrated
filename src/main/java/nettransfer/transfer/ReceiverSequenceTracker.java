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
 * Duplicate DATA (seqNum already received): re-ACK the current cumulative
 * value, don't reprocess. Out-of-order DATA (a gap exists before it):
 * discarded silently, NOT buffered -- the Go-Back-N choice made in
 * Stage 8; buffering out-of-order arrivals is Selective Repeat's job,
 * not implemented here.
 *
 * Stage 10: adds isTransferComplete(), which needs to know the total
 * chunk count (from the START handshake) to answer "is every chunk in,
 * not just some threshold." Kept as an overloaded constructor so earlier
 * unit tests that only cared about sequencing/dup/out-of-order logic
 * (and never needed totalChunks) still compile and pass unchanged.
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

    /** -1 means "not provided" -- isTransferComplete() is unavailable in that case. */
    private final int totalChunks;

    private int highestContiguousSeqReceived = -1;

    /** Use when total chunk count isn't known/needed (matches earlier-stage tests). */
    public ReceiverSequenceTracker() {
        this(-1);
    }

    /** Use in real transfers -- totalChunks comes from the negotiated START handshake. */
    public ReceiverSequenceTracker(int totalChunks) {
        this.totalChunks = totalChunks;
    }

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
        return DataReceiveOutcome.OUT_OF_ORDER_DISCARDED;
    }

    /** The value to put in the next ACK's seqNum field. -1 before anything has arrived. */
    public int getCumulativeAckSeqNum() {
        return highestContiguousSeqReceived;
    }

    public boolean hasReceivedThrough(int seqNum) {
        return highestContiguousSeqReceived >= seqNum;
    }

    /**
     * True once every chunk 0..totalChunks-1 has been received with no
     * gaps. Requires totalChunks to have been provided at construction.
     */
    public boolean isTransferComplete() {
        if (totalChunks < 0) {
            throw new IllegalStateException(
                    "totalChunks was not provided to this tracker; use the ReceiverSequenceTracker(int) constructor");
        }
        return highestContiguousSeqReceived == totalChunks - 1;
    }
}