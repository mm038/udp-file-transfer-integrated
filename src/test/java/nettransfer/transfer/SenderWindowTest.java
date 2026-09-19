package nettransfer.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SenderWindowTest {

    @Test
    void windowSizeOneBehavesLikeStopAndWait() {
        SenderWindow window = new SenderWindow(3, 1);

        assertTrue(window.canSendMore());
        assertEquals(0, window.getNextSeqNumToSend());
        window.markSent();

        // windowSize=1: chunk 0 is in flight, no room for chunk 1 yet
        assertFalse(window.canSendMore());

        window.onAckReceived(0);
        assertTrue(window.canSendMore());
        assertEquals(1, window.getNextSeqNumToSend());
        window.markSent();

        assertFalse(window.canSendMore());
        window.onAckReceived(1);
        assertEquals(2, window.getNextSeqNumToSend());
        window.markSent();

        window.onAckReceived(2);
        assertTrue(window.isComplete());
        assertFalse(window.canSendMore());
    }

    @Test
    void largerWindowAllowsMultipleInFlight() {
        SenderWindow window = new SenderWindow(5, 3);

        // Can send 0, 1, 2 before needing any ACK
        assertEquals(0, window.getNextSeqNumToSend());
        window.markSent();
        assertTrue(window.canSendMore());
        assertEquals(1, window.getNextSeqNumToSend());
        window.markSent();
        assertTrue(window.canSendMore());
        assertEquals(2, window.getNextSeqNumToSend());
        window.markSent();

        // Window full (0,1,2 all in flight, windowSize=3)
        assertFalse(window.canSendMore());

        // ACK for chunk 0 slides the window forward by one
        window.onAckReceived(0);
        assertTrue(window.canSendMore());
        assertEquals(3, window.getNextSeqNumToSend());
        window.markSent();

        // Window full again (1,2,3 in flight)
        assertFalse(window.canSendMore());
    }

    @Test
    void isCompleteOnlyAfterLastChunkAcked() {
        SenderWindow window = new SenderWindow(2, 1);
        window.markSent();
        window.onAckReceived(0);
        assertFalse(window.isComplete());

        window.markSent();
        window.onAckReceived(1);
        assertTrue(window.isComplete());
    }

    @Test
    void cannotSendPastTotalChunks() {
        SenderWindow window = new SenderWindow(1, 5); // window bigger than the whole file
        assertTrue(window.canSendMore());
        window.markSent();
        assertFalse(window.canSendMore()); // no more chunks exist, regardless of window room
    }

    @Test
    void invalidConstructionRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SenderWindow(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SenderWindow(5, 0));
        assertThrows(IllegalArgumentException.class, () -> new SenderWindow(5, -1));
    }

    @Test
    void getNextSeqNumToSendThrowsWhenNoRoom() {
        SenderWindow window = new SenderWindow(1, 1);
        window.markSent();
        assertThrows(IllegalStateException.class, window::getNextSeqNumToSend);
    }

    @Test
    void duplicateAckDoesNotAdvanceBaseTwice() {
        SenderWindow window = new SenderWindow(5, 1);
        window.markSent();
        window.onAckReceived(0);
        assertEquals(1, window.getBase());

        // the same ACK for seqNum 0 arrives again (e.g. sender's retransmitted
        // DATA prompted a second, redundant ACK)
        window.onAckReceived(0);
        assertEquals(1, window.getBase(), "duplicate ACK must not move base again");
    }

    @Test
    void staleAckBelowBaseIsIgnored() {
        SenderWindow window = new SenderWindow(5, 1);
        window.markSent();
        window.onAckReceived(0);
        assertEquals(1, window.getBase());

        // a stale/delayed ACK for an already-superseded seqNum arrives late
        window.onAckReceived(0);
        assertEquals(1, window.getBase(), "stale ACK below base must not move base backward or re-trigger anything");
    }

    @Test
    void staleAckDoesNotCorruptSubsequentProgress() {
        SenderWindow window = new SenderWindow(5, 1);
        window.markSent();
        window.onAckReceived(0);
        window.markSent();

        // a stale ACK for seqNum 0 arrives again, after the window has already moved on
        window.onAckReceived(0);
        assertEquals(1, window.getBase(), "stale ACK must not undo progress already made");

        // real progress still works correctly afterward
        window.onAckReceived(1);
        assertEquals(2, window.getBase());
    }
}