package nettransfer.transfer;

import nettransfer.net.UdpChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SenderReceiverIntegrationTest {

    @Test
    void stopAndWaitTransfersFileCorrectly(@TempDir Path tempDir) throws Exception {
        runEndToEndTransfer(tempDir, 5000, 1024, 1);
    }

    @Test
    void slidingWindowTransfersFileCorrectly(@TempDir Path tempDir) throws Exception {
        runEndToEndTransfer(tempDir, 5000, 1024, 3);
    }

    @Test
    void emptyFileTransfersCorrectly(@TempDir Path tempDir) throws Exception {
        runEndToEndTransfer(tempDir, 0, 1024, 1);
    }

    private void runEndToEndTransfer(Path tempDir, int fileSizeBytes, int chunkSize, int windowSize)
            throws Exception {
        byte[] original = new byte[fileSizeBytes];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i % 256);
        }
        Path inputFile = tempDir.resolve("input.bin");
        Path outputFile = tempDir.resolve("output.bin");
        Files.write(inputFile, original);

        try (UdpChannel receiverChannel = new UdpChannel(0)) {
            int receiverPort = receiverChannel.getLocalPort();

            AtomicReference<TransferResult> receiverResult = new AtomicReference<>();
            AtomicReference<Exception> receiverError = new AtomicReference<>();

            Thread receiverThread = new Thread(() -> {
                try {
                    ReceiverEngine receiver = new ReceiverEngine(
                            receiverChannel, 2_000, 2_000, 100, null);
                    receiverResult.set(receiver.receiveFile(outputFile.toString()));
                } catch (Exception e) {
                    receiverError.set(e);
                }
            });
            receiverThread.start();

            TransferResult senderResult;
            try (UdpChannel senderChannel = new UdpChannel()) {
                InetAddress loopback = InetAddress.getByName("127.0.0.1");
                SenderEngine sender = new SenderEngine(
                        senderChannel, loopback, receiverPort, chunkSize, windowSize, 200, 5);
                senderResult = sender.sendFile(inputFile.toString());
            }

            receiverThread.join(10_000);
            assertNull(receiverError.get(), "Receiver threw an exception: " + receiverError.get());

            assertTrue(senderResult.isSuccess(), "Sender reported failure: " + senderResult);
            assertTrue(receiverResult.get().isSuccess(), "Receiver reported failure: " + receiverResult.get());

            byte[] received = Files.readAllBytes(outputFile);
            assertArrayEquals(original, received, "Reconstructed file must match the original exactly");
        }
    }
}
