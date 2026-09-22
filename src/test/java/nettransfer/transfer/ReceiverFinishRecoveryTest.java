package nettransfer.transfer;

import nettransfer.integrity.FileHashUtil;
import nettransfer.net.UdpChannel;
import nettransfer.protocol.ControlMessage;
import nettransfer.protocol.Packet;
import nettransfer.protocol.PacketEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ReceiverFinishRecoveryTest {
    @Test
    void resendsCachedFinishAckWithoutRewritingFileAndEndsAtFixedDeadline(@TempDir Path tempDir)
            throws Exception {
        Path output = tempDir.resolve("received.bin");
        byte[] payload = new byte[] {1, 2, 3};
        AtomicReference<TransferResult> result = new AtomicReference<>();
        AtomicReference<Throwable> receiverFailure = new AtomicReference<>();

        try (UdpChannel receiverChannel = new UdpChannel(0);
             UdpChannel sender = new UdpChannel();
             UdpChannel wrongPeer = new UdpChannel()) {
            Thread receiverThread = new Thread(() -> {
                try {
                    result.set(new ReceiverEngine(receiverChannel, 500, 500, 400, null)
                            .receiveFile(output.toString()));
                } catch (Throwable throwable) {
                    receiverFailure.set(throwable);
                }
            });
            receiverThread.start();

            InetAddress loopback = InetAddress.getLoopbackAddress();
            int port = receiverChannel.getLocalPort();
            sender.setReceiveTimeoutMillis(80);
            wrongPeer.setReceiveTimeoutMillis(40);
            ControlMessage start = ControlMessage.createStart("input.bin", payload.length, payload.length);
            send(sender, start, loopback, port);
            assertTrue(readControl(sender).isAccepted());
            sender.send(PacketEncoder.encode(Packet.createData(
                    UUID.fromString(start.getTransferId()), 0, payload)), loopback, port);
            sender.receive();
            ControlMessage finish = ControlMessage.createFinish(
                    start.getTransferId(), FileHashUtil.sha256Hex(output.toString()));
            send(sender, finish, loopback, port);
            ControlMessage firstAck = readControl(sender);
            assertTrue(firstAck.isVerified());
            byte[] before = Files.readAllBytes(output);
            FileTime modifiedBefore = Files.getLastModifiedTime(output);

            send(wrongPeer, finish, loopback, port);
            assertThrows(SocketTimeoutException.class, wrongPeer::receive);
            send(sender, ControlMessage.createFinish(UUID.randomUUID().toString(), finish.getSha256Hex()),
                    loopback, port);
            assertThrows(SocketTimeoutException.class, sender::receive);

            send(sender, finish, loopback, port);
            ControlMessage duplicateAck = readControl(sender);
            assertEquals(firstAck.toJson(), duplicateAck.toJson());

            receiverThread.join(1_000);
            assertFalse(receiverThread.isAlive(), "completion recovery must have a fixed upper bound");
            assertNull(receiverFailure.get(), String.valueOf(receiverFailure.get()));
            assertNotNull(result.get());
            assertTrue(result.get().isSuccess());
            assertArrayEquals(before, Files.readAllBytes(output));
            assertEquals(modifiedBefore, Files.getLastModifiedTime(output));
        }
    }

    private static void send(UdpChannel channel, ControlMessage message,
                             InetAddress address, int port) throws Exception {
        channel.send(message.toJson().getBytes(StandardCharsets.UTF_8), address, port);
    }

    private static ControlMessage readControl(UdpChannel channel) throws Exception {
        return ControlMessage.fromJson(new String(channel.receive().data(), StandardCharsets.UTF_8));
    }
}
