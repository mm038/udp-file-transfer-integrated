package nettransfer.transfer;

/**
 * Tracks the sender's sliding window: which chunk seqNums are currently
 * "in flight" (sent, not yet acknowledged), and whether another chunk may
 * be sent right now.
 *
 * At windowSize = 1 this behaves exactly like stop-and-wait: only one
 * seqNum may be in flight, so the sender must receive its ACK before the
 * next chunk can go out. That is not a special case in this code -- it is
 * simply what the general sliding-window rule produces when windowSize = 1.
 * Increasing windowSize later requires no redesign, only passing a larger
 * value into the constructor.
 *
 * Stage 6 scope: happy path only. This class does not know about timeouts
 * or retransmission (Stage 7/8) or out-of-order/duplicate ACKs (Stage 9) --
 * it assumes ACKs arrive, in order, for exactly the seqNums that were sent.
 */
public class SenderWindow {

    private final int totalChunks;
    private final int windowSize;

    /** Oldest seqNum not yet acknowledged. Everything below this is confirmed delivered. */
    private int base;

    /** Next seqNum that has not yet been sent at all. */
    private int nextSeqNumToSend;

    public SenderWindow(int totalChunks, int windowSize) {
        if (totalChunks <= 0) {
            throw new IllegalArgumentException("totalChunks must be positive: " + totalChunks);
        }
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be positive: " + windowSize);
        }
        this.totalChunks = totalChunks;
        this.windowSize = windowSize;
        this.base = 0;
        this.nextSeqNumToSend = 0;
    }

    /**
     * True if there is a chunk that (a) exists in the file and (b) fits
     * within the current window (fewer than windowSize chunks currently
     * in flight).
     */
    public boolean canSendMore() {
        boolean chunkExists = nextSeqNumToSend < totalChunks;
        boolean roomInWindow = (nextSeqNumToSend - base) < windowSize;
        return chunkExists && roomInWindow;
    }

    /** The seqNum that should be sent next. Call canSendMore() first. */
    public int getNextSeqNumToSend() {
        if (!canSendMore()) {
            throw new IllegalStateException("No room to send: base=" + base
                    + ", nextSeqNumToSend=" + nextSeqNumToSend + ", windowSize=" + windowSize);
        }
        return nextSeqNumToSend;
    }

    /** Call immediately after actually sending getNextSeqNumToSend() over the wire. */
    public void markSent() {
        nextSeqNumToSend++;
    }

    /**
     * Call when a (validated, trusted) ACK arrives.
     * cumulativeAckSeqNum = "receiver has everything through this seqNum" --
     * matches the cumulative-ACK semantics in PROTOCOL.md.
     *
     * A stale/duplicate ACK (for something at or below the current base)
     * is simply ignored here in Stage 6 -- happy path never produces one,
     * and Stage 9 will decide the real handling once duplicates are in scope.
     */
    public void onAckReceived(int cumulativeAckSeqNum) {
        if (cumulativeAckSeqNum >= base) {
            base = cumulativeAckSeqNum + 1;
        }
    }

    /** True once every chunk in the file has been acknowledged. */
    public boolean isComplete() {
        return base >= totalChunks;
    }

    public int getBase() { return base; }
    public int getWindowSize() { return windowSize; }
    public int getTotalChunks() { return totalChunks; }
}