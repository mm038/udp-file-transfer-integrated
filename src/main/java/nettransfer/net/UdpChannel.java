package nettransfer.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.function.LongConsumer;

/**
 * UdpChannel is a thin wrapper around java.net.DatagramSocket.
 *
 * <p>WHY THIS CLASS EXISTS (viva point): every other class in the engine
 * (SenderEngine, ReceiverEngine) should depend on this interface, not on
 * DatagramSocket directly. That gives us one place to:
 *   - control socket lifecycle (bind, close),
 *   - set a receive timeout (needed later for our timeout/retransmission logic),
 *   - later slot in the ImpairmentShim (Stage 14) without touching the
 *     sender/receiver logic at all.
 *
 * <p>Stage 1 scope: this class does nothing "reliable" yet. It only sends
 * and receives raw datagrams. Reliability is built in later stages on top
 * of this.
 */
public class UdpChannel implements AutoCloseable {

    /** Maximum UDP payload we will ever receive into. Comfortably larger
     *  than our chosen application chunk size (<=1024 B payload, see
     *  protocol design) plus header and JSON control-message overhead. */
    public static final int MAX_PACKET_SIZE = 2048;

    private final DatagramSocket socket;
    private volatile LongConsumer successfulSendObserver;

    /**
     * Opens a UDP socket bound to a specific local port.
     * Use this for the receiver (it must listen on a known port) and,
     * optionally, for a sender that wants a fixed source port.
     */
    public UdpChannel(int localPort) throws SocketException {
        this.socket = new DatagramSocket(localPort);
    }

    /**
     * Opens a UDP socket bound to an OS-assigned ephemeral port.
     * Typical for a sender/client that does not need a fixed source port.
     */
    public UdpChannel() throws SocketException {
        this.socket = new DatagramSocket();
    }

    /** Sends raw bytes to the given destination. No reliability guarantees. */
    public void send(byte[] data, InetAddress destAddress, int destPort) throws IOException {
        DatagramPacket packet = new DatagramPacket(data, data.length, destAddress, destPort);
        socket.send(packet);
        LongConsumer observer = successfulSendObserver;
        if (observer != null) {
            observer.accept(data.length);
        }
    }

    /**
     * Installs an endpoint-local observer called after each successful socket send.
     * Passing null detaches the observer. Incoming datagrams are never reported here.
     */
    public void setSuccessfulSendObserver(LongConsumer observer) {
        successfulSendObserver = observer;
    }

    /**
     * Blocks until a datagram arrives (or the receive timeout, if set, expires).
     *
     * @return the received bytes, sized exactly to what arrived, plus who sent it
     * @throws SocketTimeoutException if a timeout was set via setReceiveTimeoutMillis
     *                                 and no datagram arrived in time
     */
    public ReceivedDatagram receive() throws IOException {
        byte[] buffer = new byte[MAX_PACKET_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        socket.receive(packet); // blocking call
        byte[] actualData = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), 0, actualData, 0, packet.getLength());
        return new ReceivedDatagram(actualData, packet.getAddress(), packet.getPort());
    }

    /**
     * Sets how long receive() will block before throwing SocketTimeoutException.
     * 0 means "block forever" (the DatagramSocket default). We will use this
     * from Stage 7 onward to detect "no ACK arrived in time".
     */
    public void setReceiveTimeoutMillis(int millis) throws SocketException {
        socket.setSoTimeout(millis);
    }

    public int getLocalPort() {
        return socket.getLocalPort();
    }

    @Override
    public void close() {
        socket.close();
    }

    /** Simple immutable holder for "bytes that arrived, and who sent them". */
    public record ReceivedDatagram(byte[] data, InetAddress senderAddress, int senderPort) {
    }
}
