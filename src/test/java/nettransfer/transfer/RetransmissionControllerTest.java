package nettransfer.transfer;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

class RetransmissionControllerTest {

    private static class FakeClock implements LongSupplier {
        private long currentMillis = 0;
        @Override public long getAsLong() { return currentMillis; }
        void advanceBy(long millis) { currentMillis += millis; }
    }

    @Test
    void nothingInFlightMeansNoRetransmission() {
        PacketTimeoutTracker tracker = new PacketTimeoutTracker(200);
        RetransmissionController controller = new RetransmissionController(tracker, 5);
        assertTrue(controller.checkAndRetransmit().isEmpty());
    }

    @Test
    void noRetransmissionBeforeTimeoutElapses() {
        FakeClock clock = new FakeClock();
        PacketTimeoutTracker tracker = new PacketTimeoutTracker(200, clock);
        tracker.recordSent(0);
        clock.advanceBy(150);

        RetransmissionController controller = new RetransmissionController(tracker, 5);
        assertTrue(controller.checkAndRetransmit().isEmpty());
    }

    @Test
    void goBackNResendsEveryInFlightSeqNumWhenOldestTimesOut() {
        FakeClock clock = new FakeClock();
        PacketTimeoutTracker tracker = new PacketTimeoutTracker(200, clock);
        tracker.recordSent(0);
        clock.advanceBy(50);
        tracker.recordSent(1);
        clock.advanceBy(50);
        tracker.recordSent(2);
        clock.advanceBy(100); // seq0: 200ms elapsed (timed out); seq1: 150ms; seq2: 100ms

        RetransmissionController controller = new RetransmissionController(tracker, 5);
        List<Integer> resent = controller.checkAndRetransmit();

        assertEquals(List.of(0, 1, 2), resent,
                "Go-Back-N resends the timed-out packet AND everything after it, "
                        + "even packets that haven't individually hit 200ms yet");
        assertFalse(tracker.isTimedOut(0), "timer should be reset after retransmission");
        assertFalse(tracker.isTimedOut(1));
        assertFalse(tracker.isTimedOut(2));
    }

    @Test
    void stopAndWaitRetransmitsJustTheOnePacket() {
        // windowSize=1 case: "everything in flight" is naturally just one seqNum
        FakeClock clock = new FakeClock();
        PacketTimeoutTracker tracker = new PacketTimeoutTracker(200, clock);
        tracker.recordSent(3);
        clock.advanceBy(200);

        RetransmissionController controller = new RetransmissionController(tracker, 5);
        assertEquals(List.of(3), controller.checkAndRetransmit());
    }

    @Test
    void retransmissionRoundCounterIncrementsEachTimeoutEvent() {
        FakeClock clock = new FakeClock();
        PacketTimeoutTracker tracker = new PacketTimeoutTracker(200, clock);
        tracker.recordSent(0);
        RetransmissionController controller = new RetransmissionController(tracker, 5);

        clock.advanceBy(200);
        controller.checkAndRetransmit();
        assertEquals(1, controller.getRetransmissionRoundCount());

        clock.advanceBy(200);
        controller.checkAndRetransmit();
        assertEquals(2, controller.getRetransmissionRoundCount());
    }

    @Test
    void transferFailsAfterExceedingRetryLimit() {
        FakeClock clock = new FakeClock();
        PacketTimeoutTracker tracker = new PacketTimeoutTracker(200, clock);
        tracker.recordSent(0);
        RetransmissionController controller = new RetransmissionController(tracker, 2); // only 2 retries

        clock.advanceBy(200);
        assertFalse(controller.checkAndRetransmit().isEmpty()); // round 1: ok
        clock.advanceBy(200);
        assertFalse(controller.checkAndRetransmit().isEmpty()); // round 2: ok
        assertFalse(controller.hasFailed());

        clock.advanceBy(200);
        assertTrue(controller.checkAndRetransmit().isEmpty(),
                "round 3 exceeds retryLimit=2 -- should fail instead of retransmitting again");
        assertTrue(controller.hasFailed());
    }

    @Test
    void notifyProgressResetsRoundCounter() {
        FakeClock clock = new FakeClock();
        PacketTimeoutTracker tracker = new PacketTimeoutTracker(200, clock);
        tracker.recordSent(0);
        RetransmissionController controller = new RetransmissionController(tracker, 5);

        clock.advanceBy(200);
        controller.checkAndRetransmit();
        assertEquals(1, controller.getRetransmissionRoundCount());

        controller.notifyProgress(); // e.g. an ACK finally arrived and moved the window forward
        assertEquals(0, controller.getRetransmissionRoundCount());
    }

    @Test
    void invalidRetryLimitRejected() {
        PacketTimeoutTracker tracker = new PacketTimeoutTracker(200);
        assertThrows(IllegalArgumentException.class, () -> new RetransmissionController(tracker, 0));
        assertThrows(IllegalArgumentException.class, () -> new RetransmissionController(tracker, -1));
    }
}