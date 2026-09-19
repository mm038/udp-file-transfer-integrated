package nettransfer.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simulates a complete happy-path transfer using only SenderWindow and
 * ReceiverSequenceTracker directly -- no UdpChannel, no real network.
 * Confirms the two classes agree with each other end-to-end, at both
 * windowSize=1 (stop-and-wait) and a larger window.
 */
class SlidingWindowHappyPathIntegrationTest {

    @Test
    void stopAndWaitTransferCompletesInOrder() {
        simulateTransfer(6, 1);
    }

    @Test
    void slidingWindowTransferCompletesInOrder() {
        simulateTransfer(6, 3);
    }

    private void simulateTransfer(int totalChunks, int windowSize) {
        SenderWindow sender = new SenderWindow(totalChunks, windowSize);
        ReceiverSequenceTracker receiver = new ReceiverSequenceTracker();

        int safetyLimit = totalChunks * 10; // guards against an infinite loop if logic is broken
        int iterations = 0;

        while (!sender.isComplete()) {
            iterations++;
            assertTrue(iterations < safetyLimit, "Transfer did not complete -- possible logic bug");

            while (sender.canSendMore()) {
                int seqNum = sender.getNextSeqNumToSend();
                sender.markSent();

                // "send over the network" -- happy path, always arrives, always in order
                receiver.onDataReceived(seqNum);

                // receiver sends back its current cumulative ACK
                int ackSeqNum = receiver.getCumulativeAckSeqNum();
                sender.onAckReceived(ackSeqNum);
            }
        }

        assertEquals(totalChunks - 1, receiver.getCumulativeAckSeqNum());
        assertTrue(receiver.hasReceivedThrough(totalChunks - 1));
    }
}