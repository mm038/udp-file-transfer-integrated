package nettransfer.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReceiverSequenceTrackerTest {

    @Test
    void startsWithNothingReceived() {
        ReceiverSequenceTracker tracker = new ReceiverSequenceTracker();
        assertEquals(-1, tracker.getCumulativeAckSeqNum());
        assertFalse(tracker.hasReceivedThrough(0));
    }

    @Test
    void inOrderArrivalAdvancesCumulativeAck() {
        ReceiverSequenceTracker tracker = new ReceiverSequenceTracker();

        tracker.onDataReceived(0);
        assertEquals(0, tracker.getCumulativeAckSeqNum());

        tracker.onDataReceived(1);
        assertEquals(1, tracker.getCumulativeAckSeqNum());

        tracker.onDataReceived(2);
        assertEquals(2, tracker.getCumulativeAckSeqNum());
        assertTrue(tracker.hasReceivedThrough(1));
    }
}