package nettransfer.transfer;

import java.util.List;

/**
 * Watches PacketTimeoutTracker for a timed-out packet and, if found,
 * decides what to retransmit and whether the transfer should give up.
 *
 * Strategy: Go-Back-N. When the OLDEST in-flight seqNum (the one blocking
 * window progress) times out, every currently in-flight seqNum is resent
 * -- not just the one that timed out -- and their timers are reset as if
 * freshly sent. This is the natural fit given Stage 9 (out-of-order
 * buffering, needed for the more selective "Selective Repeat" strategy)
 * doesn't exist yet. At windowSize = 1, "every in-flight seqNum" is just
 * the one packet -- identical to plain stop-and-wait retransmission.
 *
 * Retry limit: counts consecutive retransmission rounds with NO progress
 * (no ACK has advanced the window) since the last successful ACK. Call
 * notifyProgress() whenever an ACK actually moves SenderWindow's base
 * forward -- that resets the counter, since a fresh timeout situation has
 * begun. If checkAndRetransmit() is called and the count would exceed
 * retryLimit, the transfer is marked permanently failed instead of
 * retransmitting again.
 *
 * Deliberately decoupled from SenderWindow (same pattern as
 * PacketTimeoutTracker/ReceiverSequenceTracker not depending on each
 * other) -- a future SenderEngine (later stage) is the one place that
 * holds references to all of these and wires them together.
 */
public class RetransmissionController {

    private final PacketTimeoutTracker timeoutTracker;
    private final int retryLimit;

    private int consecutiveRetransmissionRounds = 0;
    private boolean failed = false;

    public RetransmissionController(PacketTimeoutTracker timeoutTracker, int retryLimit) {
        if (retryLimit <= 0) {
            throw new IllegalArgumentException("retryLimit must be positive: " + retryLimit);
        }
        this.timeoutTracker = timeoutTracker;
        this.retryLimit = retryLimit;
    }

    /**
     * Checks whether the oldest in-flight packet has timed out. If so and
     * the retry limit is not exceeded, returns every currently in-flight
     * seqNum (Go-Back-N) for the caller to actually resend over the
     * network, and resets each one's timer. If the retry limit WOULD be
     * exceeded, marks the transfer failed and returns an empty list
     * instead. If nothing has timed out, returns an empty list and does
     * nothing.
     */
    public List<Integer> checkAndRetransmit() {
        if (failed) {
            return List.of();
        }

        List<Integer> inFlight = timeoutTracker.getInFlightSeqNums();
        if (inFlight.isEmpty()) {
            return List.of();
        }

        int oldestInFlight = inFlight.get(0); // ascending order -> index 0 is the oldest
        if (!timeoutTracker.isTimedOut(oldestInFlight)) {
            return List.of();
        }

        consecutiveRetransmissionRounds++;
        if (consecutiveRetransmissionRounds > retryLimit) {
            failed = true;
            return List.of();
        }

        for (int seqNum : inFlight) {
            timeoutTracker.recordSent(seqNum); // reset timer as though just sent again
        }
        return inFlight;
    }

    /** Call when an ACK genuinely advances the window's base -- resets the retry counter. */
    public void notifyProgress() {
        consecutiveRetransmissionRounds = 0;
    }

    public boolean hasFailed() {
        return failed;
    }

    public int getRetransmissionRoundCount() {
        return consecutiveRetransmissionRounds;
    }

    public int getRetryLimit() {
        return retryLimit;
    }
}