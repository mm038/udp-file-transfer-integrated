package nettransfer.transfer;

import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Confirms SenderWindow and PacketTimeoutTracker compose correctly without
 * either class knowing about the other -- the pattern a future SenderEngine
 * (Stage 8) will follow: call both together on send and on ACK.
 */
class SenderWindowTimeoutIntegrationTest {

    private static class FakeClock implements LongSupplier {
        private long currentMillis = 0;
        @Override public long getAsLong() { return currentMillis; }
        void advanceBy(long millis) { currentMillis += millis; }
    }

    @Test
    void stopAndWaitDetectsTimeoutWhenAckNeverArrives() {
        FakeClock clock = new FakeClock();
        SenderWindow window = new SenderWindow(3, 1); // windowSize = 1
        PacketTimeoutTracker timeouts = new PacketTimeoutTracker(200, clock);

        int seqNum = window.getNextSeqNumToSend();
        window.markSent();
        timeouts.recordSent(seqNum);

        clock.advanceBy(199);
        assertFalse(timeouts.isTimedOut(seqNum), "should not time out yet");

        clock.advanceBy(1);
        assertTrue(timeouts.isTimedOut(seqNum), "should time out at 200ms with no ACK");

        // window itself doesn't know anything timed out -- that's Stage 8's job
        // (retransmission) to act on. Stage 7 only detects it.
        assertFalse(window.canSendMore(), "window still correctly blocked, unaware of the timeout");
    }

    @Test
    void ackBeforeTimeoutClearsTrackingAndAllowsNextSend() {
        FakeClock clock = new FakeClock();
        SenderWindow window = new SenderWindow(3, 1);
        PacketTimeoutTracker timeouts = new PacketTimeoutTracker(200, clock);

        int seqNum = window.getNextSeqNumToSend();
        window.markSent();
        timeouts.recordSent(seqNum);

        clock.advanceBy(50); // well within timeout
        window.onAckReceived(seqNum);
        timeouts.recordAcked(seqNum);

        clock.advanceBy(500); // long past the original timeout window
        assertFalse(timeouts.isTimedOut(seqNum), "acked packet should never time out");
        assertTrue(window.canSendMore(), "window should have slid forward");
    }

    @Test
    void slidingWindowTracksMultipleInFlightTimeoutsIndependently() {
        FakeClock clock = new FakeClock();
        SenderWindow window = new SenderWindow(5, 3); // windowSize = 3
        PacketTimeoutTracker timeouts = new PacketTimeoutTracker(200, clock);

        // Send chunks 0, 1, 2 at staggered times, filling the window
        int seq0 = window.getNextSeqNumToSend(); window.markSent(); timeouts.recordSent(seq0);
        clock.advanceBy(100);
        int seq1 = window.getNextSeqNumToSend(); window.markSent(); timeouts.recordSent(seq1);
        clock.advanceBy(150); // seq0 now at 250ms (timed out), seq1 at 150ms (not yet)

        assertTrue(timeouts.isTimedOut(seq0));
        assertFalse(timeouts.isTimedOut(seq1));
        assertEquals(java.util.List.of(seq0), timeouts.getTimedOutSeqNums());
    }
}