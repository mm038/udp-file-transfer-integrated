package nettransfer.transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Tracks, per seqNum currently in flight, when it was sent -- and can
 * report which ones have exceeded the timeout without being acknowledged.
 *
 * Deliberately decoupled from SenderWindow: this class only knows about
 * "seqNum -> time it was sent." A future SenderEngine (Stage 8) calls
 * both SenderWindow.markSent() and this class's recordSent() together
 * when a chunk goes out, and recordAcked() alongside SenderWindow's
 * onAckReceived() when a chunk is confirmed. Neither class needs to know
 * the other exists.
 *
 * Per-packet tracking (one timer per in-flight seqNum, not one timer for
 * the whole window) is what makes this work correctly once windowSize > 1:
 * different chunks in the window were sent at different times, so they
 * must time out independently. At windowSize = 1 this naturally reduces
 * to "the one in-flight packet has its own timer" -- no special case.
 *
 * TIMEOUT VALUE: 200ms is an initial experimental baseline, not a value
 * claimed to be optimal. On localhost (dev/demo) real RTT is near-zero,
 * so almost any value "works" here -- the real test is once Stage 14's
 * impairment shim introduces actual delay. This value should be measured
 * against real RTT samples at that point and revised if the data
 * justifies it; see PROTOCOL.md / evaluation report for that discussion.
 *
 * Stage 7 scope: timeout DETECTION only. Acting on a timeout (retransmit)
 * is Stage 8. No adaptive RTT/RTO estimation -- fixed timeout is a
 * deliberate simplification, chosen specifically so spurious retransmissions
 * under jitter are visible and discussable in the report, not smoothed away.
 */
public class PacketTimeoutTracker {

    public static final int DEFAULT_TIMEOUT_MILLIS = 200;

    private final int timeoutMillis;
    private final LongSupplier clockMillis;
    private final Map<Integer, Long> sentAtBySeqNum = new HashMap<>();

    public PacketTimeoutTracker(int timeoutMillis) {
        this(timeoutMillis, System::currentTimeMillis);
    }

    /** Package-visible: lets tests inject a fake clock instead of waiting on real time. */
    PacketTimeoutTracker(int timeoutMillis, LongSupplier clockMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive: " + timeoutMillis);
        }
        this.timeoutMillis = timeoutMillis;
        this.clockMillis = clockMillis;
    }

    /** Call the moment a chunk with this seqNum is actually sent over the wire. */
    public void recordSent(int seqNum) {
        sentAtBySeqNum.put(seqNum, clockMillis.getAsLong());
    }

    /** Call when a (validated) ACK confirms this seqNum -- cancels its timer. */
    public void recordAcked(int seqNum) {
        sentAtBySeqNum.remove(seqNum);
    }

    /** True if this seqNum is currently in flight AND has exceeded the timeout. */
    public boolean isTimedOut(int seqNum) {
        Long sentAt = sentAtBySeqNum.get(seqNum);
        if (sentAt == null) {
            return false; // never sent, or already acked -- nothing to time out
        }
        return (clockMillis.getAsLong() - sentAt) >= timeoutMillis;
    }

    /** All currently in-flight seqNums that have exceeded the timeout, ascending order. */
    public List<Integer> getTimedOutSeqNums() {
        long now = clockMillis.getAsLong();
        List<Integer> timedOut = new ArrayList<>();
        for (Map.Entry<Integer, Long> entry : sentAtBySeqNum.entrySet()) {
            if (now - entry.getValue() >= timeoutMillis) {
                timedOut.add(entry.getKey());
            }
        }
        Collections.sort(timedOut);
        return timedOut;
    }

    /** True if this seqNum was sent and not yet acked (regardless of timeout status). */
    public boolean isInFlight(int seqNum) {
        return sentAtBySeqNum.containsKey(seqNum);
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }
}