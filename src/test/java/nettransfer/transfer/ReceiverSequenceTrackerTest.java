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

    @Test
    void duplicateDataIsReAckedNotReprocessed() {
        ReceiverSequenceTracker tracker = new ReceiverSequenceTracker();

        assertEquals(ReceiverSequenceTracker.DataReceiveOutcome.ACCEPTED, tracker.onDataReceived(0));
        assertEquals(ReceiverSequenceTracker.DataReceiveOutcome.ACCEPTED, tracker.onDataReceived(1));

        // seqNum 0 arrives again (its ACK was presumably lost/delayed, sender retransmitted)
        assertEquals(ReceiverSequenceTracker.DataReceiveOutcome.DUPLICATE, tracker.onDataReceived(0));

        // state must be unchanged by the duplicate -- still at 1, not reset or corrupted
        assertEquals(1, tracker.getCumulativeAckSeqNum());
    }

    @Test
    void outOfOrderDataIsDiscardedNotBuffered() {
        ReceiverSequenceTracker tracker = new ReceiverSequenceTracker();

        tracker.onDataReceived(0);

        // seqNum 2 arrives before seqNum 1 -- a gap exists
        assertEquals(ReceiverSequenceTracker.DataReceiveOutcome.OUT_OF_ORDER_DISCARDED,
                tracker.onDataReceived(2));

        // cumulative ACK must NOT advance to 2 -- chunk 1 is still missing
        assertEquals(0, tracker.getCumulativeAckSeqNum());

        // once the actually-missing chunk 1 arrives (e.g. via Go-Back-N retransmission),
        // it's accepted normally
        assertEquals(ReceiverSequenceTracker.DataReceiveOutcome.ACCEPTED, tracker.onDataReceived(1));
        assertEquals(1, tracker.getCumulativeAckSeqNum());

        // and NOW seqNum 2 (previously discarded) arrives again and is accepted in order
        assertEquals(ReceiverSequenceTracker.DataReceiveOutcome.ACCEPTED, tracker.onDataReceived(2));
        assertEquals(2, tracker.getCumulativeAckSeqNum());
    }

    @Test
    void multipleOutOfOrderArrivalsAreAllDiscarded() {
        ReceiverSequenceTracker tracker = new ReceiverSequenceTracker();
        tracker.onDataReceived(0);

        // chunks 5, 3, 2 all arrive ahead of the gap at seqNum 1 -- none buffered
        assertEquals(ReceiverSequenceTracker.DataReceiveOutcome.OUT_OF_ORDER_DISCARDED, tracker.onDataReceived(5));
        assertEquals(ReceiverSequenceTracker.DataReceiveOutcome.OUT_OF_ORDER_DISCARDED, tracker.onDataReceived(3));
        assertEquals(ReceiverSequenceTracker.DataReceiveOutcome.OUT_OF_ORDER_DISCARDED, tracker.onDataReceived(2));

        assertEquals(0, tracker.getCumulativeAckSeqNum(), "none of the discarded arrivals should have advanced state");
    }
}