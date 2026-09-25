package nettransfer.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

import nettransfer.protocol.MessageType;
import nettransfer.protocol.Packet;
import nettransfer.protocol.PacketDecoder;
import nettransfer.protocol.PacketEncoder;

/**
 * UdpChannel is a thin wrapper around java.net.DatagramSocket.
 *
 * <p>WHY THIS CLASS EXISTS (viva point): every other class in the engine
 * (SenderEngine, ReceiverEngine) should depend on this interface, not on
 * DatagramSocket directly. That gives us one place to:
 *   - control socket lifecycle (bind, close),
 *   - set a receive timeout (needed later for our timeout/retransmission logic),
 *   - apply explicitly configured receive-side loss and delay for experiments.
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
    private final ImpairmentSettings impairmentSettings;
    private final int maxPending;
    private final Object impairmentLock = new Object();
    private final PriorityQueue<PendingDatagram> pending = new PriorityQueue<>(
            Comparator.comparingLong(PendingDatagram::dueNanos).thenComparingLong(PendingDatagram::decisionIndex));
    private Scope scope;
    private volatile int receiveTimeoutMillis;
    private volatile LongConsumer successfulSendObserver;

    /**
     * Opens a UDP socket bound to a specific local port.
     * Use this for the receiver (it must listen on a known port) and,
     * optionally, for a sender that wants a fixed source port.
     */
    public UdpChannel(int localPort) throws SocketException {
        this(localPort, ImpairmentSettings.disabled());
    }

    /**
     * Opens a UDP socket bound to an OS-assigned ephemeral port.
     * Typical for a sender/client that does not need a fixed source port.
     */
    public UdpChannel() throws SocketException {
        this(0, ImpairmentSettings.disabled());
    }

    public UdpChannel(ImpairmentSettings impairmentSettings) throws SocketException {
        this(0, impairmentSettings);
    }

    public UdpChannel(int localPort, ImpairmentSettings impairmentSettings) throws SocketException {
        this(localPort, impairmentSettings, 4096);
    }

    // A smaller queue makes the overload-failure path directly testable.
    UdpChannel(int localPort, ImpairmentSettings impairmentSettings, int maxPending) throws SocketException {
        this.impairmentSettings = Objects.requireNonNull(impairmentSettings, "impairmentSettings");
        if (maxPending < 1) {
            throw new IllegalArgumentException("maxPending must be positive");
        }
        this.maxPending = maxPending;
        this.socket = new DatagramSocket(localPort);
    }

    public ImpairmentSettings getImpairmentSettings() {
        return impairmentSettings;
    }

    /** Bind decisions to the one active transfer; handshake/control packets always pass. */
    public void beginImpairment(String role, InetAddress peer, int peerPort, UUID transferId,
                                Consumer<ImpairmentObservation> observer) {
        if (!"sender".equals(role) && !"receiver".equals(role)) {
            throw new IllegalArgumentException("Impairment role must be sender or receiver");
        }
        Objects.requireNonNull(peer, "peer");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(observer, "observer");
        if (peerPort < 1 || peerPort > 65535) {
            throw new IllegalArgumentException("Invalid impairment peer port");
        }
        synchronized (impairmentLock) {
            if (socket.isClosed()) {
                throw new IllegalStateException("Channel is closed");
            }
            if (scope != null) {
                throw new IllegalStateException("An impairment transfer is already active");
            }
            if (impairmentSettings.enabled()) {
                scope = new Scope(role, peer, peerPort, transferId, observer, impairmentSettings.seed());
            }
        }
    }

    /** Cancel every withheld datagram before another transfer can use the channel. */
    public void finishImpairment() {
        synchronized (impairmentLock) {
            while (!pending.isEmpty()) {
                PendingDatagram cancelled = pending.remove();
                observe(cancelled.scope(), "CANCEL", cancelled.packet(), cancelled.decisionIndex(),
                        "transfer finished before delayed delivery");
            }
            scope = null;
        }
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
        int timeout = receiveTimeoutMillis;
        long deadline = timeout == 0 ? Long.MAX_VALUE : System.nanoTime() + timeout * 1_000_000L;
        while (true) {
            long now = System.nanoTime();
            long wakeAt = deadline;
            synchronized (impairmentLock) {
                if (socket.isClosed()) {
                    throw new SocketException("Socket is closed");
                }
                PendingDatagram first = pending.peek();
                if (first != null && first.dueNanos() <= now && first.dueNanos() <= deadline) {
                    pending.remove();
                    observe(first.scope(), "DELIVER", first.packet(), first.decisionIndex(),
                            "configured delay elapsed");
                    return first.datagram();
                }
                if (first != null) {
                    wakeAt = Math.min(wakeAt, first.dueNanos());
                }
            }
            if (now >= deadline) {
                throw new SocketTimeoutException("Receive deadline elapsed");
            }
            socket.setSoTimeout(wakeAt == Long.MAX_VALUE ? 0 : remainingMillis(wakeAt, now));
            ReceivedDatagram received;
            try {
                received = receiveRaw();
            } catch (SocketTimeoutException timeoutException) {
                // A delayed packet may be due; this wake-up must not restart the caller's deadline.
                continue;
            }
            synchronized (impairmentLock) {
                if (socket.isClosed()) {
                    throw new SocketException("Socket is closed");
                }
                Packet packet = eligiblePacket(received, scope);
                if (packet == null) {
                    return received;
                }
                Scope active = scope;
                long decisionIndex = ++active.decisionIndex;
                if (active.role.equals("receiver")
                        && active.random.nextDouble() < impairmentSettings.lossPercent() / 100.0) {
                    observe(active, "DROP", packet, decisionIndex, "configured DATA loss probability");
                    continue;
                }
                if (impairmentSettings.delayMillis() == 0) {
                    observe(active, "DELIVER", packet, decisionIndex, "no configured delay");
                    return received;
                }
                if (pending.size() >= maxPending) {
                    observe(active, "QUEUE_OVERFLOW", packet, decisionIndex, "delay queue capacity exceeded");
                    throw new IOException("Impairment delay queue capacity exceeded (" + maxPending + ")");
                }
                long due = System.nanoTime() + impairmentSettings.delayMillis() * 1_000_000L;
                pending.add(new PendingDatagram(received, packet, decisionIndex, due, active));
                observe(active, "DELAY", packet, decisionIndex, "configured fixed receive delay");
            }
        }
    }

    private ReceivedDatagram receiveRaw() throws IOException {
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
        receiveTimeoutMillis = millis;
    }

    public int getLocalPort() {
        return socket.getLocalPort();
    }

    @Override
    public void close() {
        socket.close();
        finishImpairment();
    }

    private static int remainingMillis(long deadline, long now) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, (deadline - now + 999_999L) / 1_000_000L));
    }

    private Packet eligiblePacket(ReceivedDatagram datagram, Scope active) {
        if (active == null || !active.peer.equals(datagram.senderAddress()) || active.peerPort != datagram.senderPort()) {
            return null;
        }
        byte[] bytes = datagram.data();
        MessageType expected = active.role.equals("receiver") ? MessageType.DATA : MessageType.ACK;
        if (bytes.length < Packet.HEADER_SIZE || bytes[0] != (byte) expected.ordinal()) {
            return null;
        }
        try {
            Packet packet = PacketDecoder.decode(bytes);
            if (!active.transferId.equals(packet.getTransferId())
                    || packet.getPayload().length > Packet.MAX_PAYLOAD_SIZE
                    || bytes.length != Packet.HEADER_SIZE + packet.getPayload().length
                    || (expected == MessageType.ACK && packet.getPayload().length != 0)
                    || (expected == MessageType.ACK && packet.getSeqNum() < -1)
                    || (expected == MessageType.DATA && packet.getSeqNum() < 0)
                    || packet.getCrc32() != PacketEncoder.computeCrc32(packet.getType(), packet.getTransferId(),
                                                                      packet.getSeqNum(), packet.getPayload())) {
                return null;
            }
            return packet;
        } catch (IOException | RuntimeException invalidPacket) {
            return null;
        }
    }

    private void observe(Scope active, String action, Packet packet, long decisionIndex, String reason) {
        active.observer.accept(new ImpairmentObservation(action, packet.getType().name(), packet.getSeqNum(),
                decisionIndex, impairmentSettings.delayMillis(), active.transferId, reason));
    }

    private record PendingDatagram(ReceivedDatagram datagram, Packet packet, long decisionIndex,
                                   long dueNanos, Scope scope) { }

    private static final class Scope {
        private final String role;
        private final InetAddress peer;
        private final int peerPort;
        private final UUID transferId;
        private final Consumer<ImpairmentObservation> observer;
        private final Random random;
        private long decisionIndex;

        private Scope(String role, InetAddress peer, int peerPort, UUID transferId,
                       Consumer<ImpairmentObservation> observer, long seed) {
            this.role = role;
            this.peer = peer;
            this.peerPort = peerPort;
            this.transferId = transferId;
            this.observer = observer;
            this.random = new Random(seed);
        }
    }

    /** Simple immutable holder for "bytes that arrived, and who sent them". */
    public record ReceivedDatagram(byte[] data, InetAddress senderAddress, int senderPort) {
    }
}
