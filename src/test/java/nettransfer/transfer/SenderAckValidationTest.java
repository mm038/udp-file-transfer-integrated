package nettransfer.transfer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.UUID;
import nettransfer.net.UdpChannel;
import nettransfer.protocol.Packet;
import nettransfer.protocol.PacketDecoder;
import nettransfer.protocol.PacketEncoder;
import org.junit.jupiter.api.Test;

class SenderAckValidationTest {
    private static final InetAddress EXPECTED_ADDRESS = InetAddress.getLoopbackAddress();
    private static final int EXPECTED_PORT = 9000;

    @Test
    void acceptsValidCurrentDuplicateAndCumulativeAcks() throws Exception {
        UUID transferId = UUID.randomUUID();
        SenderWindow window = sentWindow(5, 3, 3);

        Packet cumulative = Packet.createAck(transferId, 1);
        assertTrue(acceptable(cumulative, encoded(cumulative), transferId, EXPECTED_ADDRESS,
                EXPECTED_PORT, window));
        window.onAckReceived(1);

        Packet duplicate = Packet.createAck(transferId, 1);
        assertTrue(acceptable(duplicate, encoded(duplicate), transferId, EXPECTED_ADDRESS,
                EXPECTED_PORT, window));
    }

    @Test
    void rejectsWrongUuidAddressAndPort() throws Exception {
        UUID transferId = UUID.randomUUID();
        SenderWindow window = sentWindow(3, 3, 3);
        Packet wrongUuid = Packet.createAck(UUID.randomUUID(), 1);
        Packet valid = Packet.createAck(transferId, 1);

        assertFalse(acceptable(wrongUuid, encoded(wrongUuid), transferId, EXPECTED_ADDRESS,
                EXPECTED_PORT, window));
        assertFalse(acceptable(valid, encoded(valid), transferId,
                InetAddress.getByName("127.0.0.2"), EXPECTED_PORT, window));
        assertFalse(acceptable(valid, encoded(valid), transferId, EXPECTED_ADDRESS,
                EXPECTED_PORT + 1, window));
    }

    @Test
    void rejectsInvalidCrcMalformedDataTypeAndInvalidSequenceRange() throws Exception {
        UUID transferId = UUID.randomUUID();
        SenderWindow window = sentWindow(4, 3, 3);

        byte[] corruptedBytes = encoded(Packet.createAck(transferId, 1));
        corruptedBytes[23] ^= 0x01;
        Packet corrupted = PacketDecoder.decode(corruptedBytes);
        assertFalse(acceptable(corrupted, corruptedBytes, transferId, EXPECTED_ADDRESS,
                EXPECTED_PORT, window));

        Packet data = Packet.createData(transferId, 1, new byte[] {7});
        assertFalse(acceptable(data, encoded(data), transferId, EXPECTED_ADDRESS,
                EXPECTED_PORT, window));

        Packet negative = Packet.createAck(transferId, -1);
        Packet unsent = Packet.createAck(transferId, 3);
        Packet beyondTransfer = Packet.createAck(transferId, 99);
        assertFalse(acceptable(negative, encoded(negative), transferId, EXPECTED_ADDRESS,
                EXPECTED_PORT, window));
        assertFalse(acceptable(unsent, encoded(unsent), transferId, EXPECTED_ADDRESS,
                EXPECTED_PORT, window));
        assertFalse(acceptable(beyondTransfer, encoded(beyondTransfer), transferId,
                EXPECTED_ADDRESS, EXPECTED_PORT, window));

        assertNull(SenderEngine.decodePacketOrNull(new byte[] {(byte) 0xFF}));
    }

    @Test
    void senderWindowDefensivelyIgnoresAckForUnsentData() {
        SenderWindow window = sentWindow(5, 3, 2);
        window.onAckReceived(4);
        assertFalse(window.isComplete());
        assertTrue(window.getBase() == 0);
    }

    private static SenderWindow sentWindow(int chunks, int windowSize, int sends) {
        SenderWindow window = new SenderWindow(chunks, windowSize);
        for (int index = 0; index < sends; index++) {
            window.markSent();
        }
        return window;
    }

    private static byte[] encoded(Packet packet) throws Exception {
        return PacketEncoder.encode(packet);
    }

    private static boolean acceptable(
            Packet packet,
            byte[] bytes,
            UUID transferId,
            InetAddress senderAddress,
            int senderPort,
            SenderWindow window) {
        UdpChannel.ReceivedDatagram datagram =
                new UdpChannel.ReceivedDatagram(bytes, senderAddress, senderPort);
        return SenderEngine.isAcceptableAck(
                packet, datagram, transferId, EXPECTED_ADDRESS, EXPECTED_PORT, window);
    }
}
