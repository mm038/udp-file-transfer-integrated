package nettransfer.transfer;

import nettransfer.net.UdpChannel;
import nettransfer.protocol.ControlMessage;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FinishAckValidationTest {
    private final InetAddress loopback = InetAddress.getLoopbackAddress();
    private final String transferId = UUID.randomUUID().toString();

    @Test
    void acceptsOnlyExplicitFinishAckFromExpectedEndpointAndTransfer() throws Exception {
        ControlMessage valid = ControlMessage.createFinishAck(transferId, true, null);
        UdpChannel.ReceivedDatagram expected = datagram(valid, loopback, 9000);

        assertTrue(SenderEngine.isAttributableFinishAck(valid, expected, transferId, loopback, 9000));
        assertFalse(SenderEngine.isAttributableFinishAck(valid, expected, UUID.randomUUID().toString(), loopback, 9000));
        assertFalse(SenderEngine.isAttributableFinishAck(valid, expected, transferId, loopback, 9001));

        InetAddress wrongAddress = InetAddress.getByName("127.0.0.2");
        assertFalse(SenderEngine.isAttributableFinishAck(valid, expected, transferId, wrongAddress, 9000));
        assertFalse(SenderEngine.isAttributableFinishAck(
                ControlMessage.createError(transferId, "unrelated"), expected,
                transferId, loopback, 9000));
    }

    @Test
    void missingVerifiedStatusIsNotAttributable() {
        ControlMessage missing = ControlMessage.fromJson(
                "{\"type\":\"FINISH_ACK\",\"transferId\":\"" + transferId + "\"}");
        UdpChannel.ReceivedDatagram datagram = datagram(missing, loopback, 9000);

        assertNull(missing.getVerified());
        assertFalse(SenderEngine.isAttributableFinishAck(
                missing, datagram, transferId, loopback, 9000));
    }

    private static UdpChannel.ReceivedDatagram datagram(
            ControlMessage message, InetAddress address, int port) {
        return new UdpChannel.ReceivedDatagram(
                message.toJson().getBytes(StandardCharsets.UTF_8), address, port);
    }
}
