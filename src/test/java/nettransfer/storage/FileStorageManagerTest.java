package nettransfer.storage;

import nettransfer.integrity.FileHashUtil;
import nettransfer.net.UdpChannel;
import nettransfer.transfer.ReceiverEngine;
import nettransfer.transfer.SenderEngine;
import nettransfer.transfer.TransferResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FileStorageManagerTest {

    @TempDir Path projectRoot;

    @Test
    void createsStorageDirectoriesAndResolvesOutgoingFile() throws Exception {
        FileStorageManager storage = new FileStorageManager(projectRoot);
        assertTrue(Files.isDirectory(storage.getOutgoingDirectory()));
        assertTrue(Files.isDirectory(storage.getIncomingDirectory()));

        Path source = storage.getOutgoingDirectory().resolve("report.txt");
        Files.writeString(source, "sample");
        assertEquals(source, storage.resolveOutgoingFile("report.txt"));
        assertEquals(storage.getIncomingDirectory().resolve("result.txt"),
                storage.resolveIncomingFile("result.txt"));
    }

    @Test
    void missingOutgoingFileIsRejected() throws Exception {
        FileStorageManager storage = new FileStorageManager(projectRoot);
        IOException error = assertThrows(IOException.class,
                () -> storage.resolveOutgoingFile("missing.txt"));
        assertTrue(error.getMessage().contains("does not exist"));
    }

    @Test
    void rejectsTraversalAbsolutePathsAndWindowsSpecialNames() throws Exception {
        FileStorageManager storage = new FileStorageManager(projectRoot);
        for (String unsafe : new String[] {
                "../secret.txt", "..\\secret.txt", "sub/file.txt", "sub\\file.txt",
                "/tmp/secret.txt", "C:\\outside\\secret.txt", "\\\\server\\share\\secret.txt",
                "C:secret.txt", "CON.txt", "name.", "bad?.txt", "bad|name.txt"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> storage.resolveOutgoingFile(unsafe), unsafe);
            assertThrows(IllegalArgumentException.class,
                    () -> storage.resolveIncomingFile(unsafe), unsafe);
        }
    }

    @Test
    void existingIncomingFileIsNeverOverwritten() throws Exception {
        FileStorageManager storage = new FileStorageManager(projectRoot);
        Path existing = storage.getIncomingDirectory().resolve("result.txt");
        Files.writeString(existing, "original");
        assertThrows(FileAlreadyExistsException.class,
                () -> storage.resolveIncomingFile("result.txt"));

        Path temporary = storage.createIncomingTemporaryFile();
        try {
            Files.writeString(temporary, "replacement");
            assertThrows(FileAlreadyExistsException.class,
                    () -> storage.publishIncomingFile(temporary, "result.txt"));
            assertEquals("original", Files.readString(existing));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Test
    void verifiedUdpTransferIsPublishedInIncomingWithMatchingSha256() throws Exception {
        FileStorageManager storage = new FileStorageManager(projectRoot);
        Path source = storage.getOutgoingDirectory().resolve("payload.bin");
        byte[] payload = new byte[2500];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        Files.write(source, payload);

        Path temporary = storage.createIncomingTemporaryFile();
        Path destination = storage.resolveIncomingFile("received.bin");
        AtomicReference<TransferResult> receiverResult = new AtomicReference<>();
        AtomicReference<Exception> receiverError = new AtomicReference<>();
        try (UdpChannel receiverChannel = new UdpChannel(0)) {
            Thread receiverThread = new Thread(() -> {
                try {
                    receiverResult.set(new ReceiverEngine(
                            receiverChannel, 2_000, 2_000, 100, null)
                            .receiveFile(temporary.toString()));
                } catch (Exception e) {
                    receiverError.set(e);
                }
            });
            receiverThread.start();

            TransferResult senderResult;
            try (UdpChannel senderChannel = new UdpChannel()) {
                SenderEngine sender = new SenderEngine(senderChannel,
                        InetAddress.getByName("127.0.0.1"), receiverChannel.getLocalPort(),
                        1024, 2, 200, 5);
                senderResult = sender.sendFile(storage.resolveOutgoingFile("payload.bin").toString());
            }

            receiverThread.join(10_000);
            assertFalse(receiverThread.isAlive(), "receiver should finish");
            assertNull(receiverError.get());
            assertTrue(senderResult.isSuccess());
            assertNotNull(receiverResult.get());
            assertTrue(receiverResult.get().isSuccess());
            storage.publishIncomingFile(temporary, "received.bin");
            assertArrayEquals(payload, Files.readAllBytes(destination));
            assertEquals(FileHashUtil.sha256Hex(source.toString()),
                    FileHashUtil.sha256Hex(destination.toString()));
            assertFalse(Files.exists(temporary));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
