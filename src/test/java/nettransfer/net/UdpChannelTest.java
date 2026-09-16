package nettransfer.net;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 1 tests: prove raw UDP send/receive works, independent of any
 * reliability logic (which doesn't exist yet). These are the tests you run
 * before starting Stage 2 (handshake).
 */
class UdpChannelTest {

    @Test
    void receiverGetsExactBytesSenderSent() throws Exception {
        try (UdpChannel receiver = new UdpChannel(0); // 0 = let OS pick a free port
             UdpChannel sender = new UdpChannel()) {

            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            byte[] payload = "test-payload-123".getBytes(StandardCharsets.UTF_8);

            sender.send(payload, loopback, receiver.getLocalPort());

            UdpChannel.ReceivedDatagram received = receiver.receive();

            assertArrayEquals(payload, received.data(),
                    "Bytes received must exactly match bytes sent");
            assertEquals(loopback, received.senderAddress());
        }
    }

    @Test
    void receiveTimesOutWhenNothingArrives() throws Exception {
        try (UdpChannel receiver = new UdpChannel(0)) {
            receiver.setReceiveTimeoutMillis(200);

            assertThrows(SocketTimeoutException.class, receiver::receive,
                    "With nothing sent, receive() must time out rather than block forever. "
                  + "This is the mechanism we will reuse for ACK timeouts in Stage 7.");
        }
    }

    @Test
    void twoChannelsCanBindDifferentEphemeralPortsSimultaneously() throws Exception {
        try (UdpChannel a = new UdpChannel();
             UdpChannel b = new UdpChannel()) {
            assertNotEquals(a.getLocalPort(), b.getLocalPort());
        }
    }
}
