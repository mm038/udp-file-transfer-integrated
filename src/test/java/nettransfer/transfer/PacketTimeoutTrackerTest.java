package nettransfer.transfer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

class PacketTimeoutTrackerTest {

    /** A controllable fake clock so tests don't have to Thread.sleep() for real time. */
    private static class FakeClock implements LongSupplier {
        private long currentMillis = 0;

        @Override
        public long getAsLong() {
            return currentMillis;
        }

        void advanceBy(long millis) {
            currentMillis += millis;
        }
    }

    @Test
    void freshlySentPacketIsNotTimedOut() {
        FakeClock clock = new FakeClock();
        PacketTimeoutTracker tracker = new PacketTimeoutTracker(200, clock);

        tracker.recordSent(0);
        assertFalse(tracker.isTimedOut(0));
        assertTrue(tracker.isInFlight(0));
    }

    @Test
    void packetTimesOutAfterThresholdElapses() {
        FakeClock clock = new FakeClock();
        PacketTimeoutTracker tracker = new PacketTimeoutTracker(200, clock);

        tracker.recordSent(0);
        clock.advanceBy(199);
        assertFalse(tracker.isTimedOut(0), "should not time out 1ms early");

        clock.advanceBy(1); // now exactly 200ms elapsed
        assertTrue(tracker.isTimedOut(0), "should time out at exactly the threshold");
    }

    @Test
    void ackedPacketIsNoLongerTracked() {
        FakeClock clock = new FakeClock();
        PacketTimeoutTracker tracker = new PacketTimeoutTracker(200, clock);

        tracker.recordSent(0);
        tracker.recordAcked(0);

        clock.advanceBy(500); // well past timeout, but it was acked
        assertFalse(tracker.isTimedOut(0));
        assertFalse(tracker.isInFlight(0));
    }

    @Test
    void neverSentPacketIsNotTimedOut() {
        PacketTimeoutTracker tracker = new PacketTimeoutTracker(200);
        assertFalse(tracker.isTimedOut(5));
        assertFalse(tracker.isInFlight(5));
    }

    @Test
    void multiplePacketsTimeOutIndependently() {
        // Simulates windowSize > 1: several chunks in flight, sent at different times
        FakeClock clock = new FakeClock();
        PacketTimeoutTracker tracker = new PacketTimeoutTracker(200, clock);

        tracker.recordSent(0);
        clock.advanceBy(100);
        tracker.recordSent(1);
        clock.advanceBy(100);
        tracker.recordSent(2);

        // Now: seqNum 0 sent 200ms ago, seqNum 1 sent 100ms ago, seqNum 2 just sent
        assertTrue(tracker.isTimedOut(0));
        assertFalse(tracker.isTimedOut(1));
        assertFalse(tracker.isTimedOut(2));

        List<Integer> timedOut = tracker.getTimedOutSeqNums();
        assertEquals(List.of(0), timedOut);

        clock.advanceBy(100); // now seqNum 1 has also hit 200ms
        assertEquals(List.of(0, 1), tracker.getTimedOutSeqNums());
    }

    @Test
    void invalidTimeoutRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PacketTimeoutTracker(0));
        assertThrows(IllegalArgumentException.class, () -> new PacketTimeoutTracker(-50));
    }

    @Test
    void defaultTimeoutConstantIs200() {
        assertEquals(200, PacketTimeoutTracker.DEFAULT_TIMEOUT_MILLIS);
    }

    @Test
    void getInFlightSeqNumsReturnsOnlyUnackedOnesInOrder() {
        FakeClock clock = new FakeClock();
        PacketTimeoutTracker tracker = new PacketTimeoutTracker(200, clock);

        tracker.recordSent(2);
        tracker.recordSent(0);
        tracker.recordSent(1);
        tracker.recordAcked(1);

        assertEquals(List.of(0, 2), tracker.getInFlightSeqNums());
    }
}