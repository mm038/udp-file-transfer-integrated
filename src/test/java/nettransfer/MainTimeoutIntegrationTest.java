package nettransfer;

import nettransfer.net.UdpChannel;
import nettransfer.storage.FileStorageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MainTimeoutIntegrationTest {

    @TempDir Path projectRoot;

    @BeforeEach
    void useTemporaryLogRoot() {
        System.setProperty("nettransfer.logsRoot", projectRoot.resolve("logs").toString());
    }

    @AfterEach
    void clearTimeoutProperties() {
        System.clearProperty("nettransfer.startHandshakeTimeoutMs");
        System.clearProperty("nettransfer.startRetryLimit");
        System.clearProperty("nettransfer.finishHandshakeTimeoutMs");
        System.clearProperty("nettransfer.finishRetryLimit");
        System.clearProperty("nettransfer.receiverInitialTimeoutMs");
        System.clearProperty("nettransfer.receiverInactivityTimeoutMs");
        System.clearProperty("nettransfer.logsRoot");
    }

    @Test
    void timeoutDefaultsMatchDocumentedValues() {
        assertEquals(300_000, nettransfer.transfer.ReceiverEngine.DEFAULT_INITIAL_TIMEOUT_MS);
        assertEquals(60_000, nettransfer.transfer.ReceiverEngine.DEFAULT_INACTIVITY_TIMEOUT_MS);
        assertEquals(1_000, nettransfer.transfer.SenderEngine.DEFAULT_START_HANDSHAKE_TIMEOUT_MS);
        assertEquals(5, nettransfer.transfer.SenderEngine.DEFAULT_START_RETRY_LIMIT);
        assertEquals(1_000, nettransfer.transfer.SenderEngine.DEFAULT_FINISH_HANDSHAKE_TIMEOUT_MS);
        assertEquals(5, nettransfer.transfer.SenderEngine.DEFAULT_FINISH_RETRY_LIMIT);
        assertEquals(6_250, nettransfer.transfer.ReceiverEngine.DEFAULT_COMPLETION_GRACE_MS);
        assertEquals(200, nettransfer.transfer.PacketTimeoutTracker.DEFAULT_TIMEOUT_MILLIS);
        assertEquals(300_000, Main.configuredReceiverInitialTimeoutMillis());
        assertEquals(60_000, Main.configuredReceiverInactivityTimeoutMillis());
        assertEquals(1_000, Main.configuredStartHandshakeTimeoutMillis());
        assertEquals(5, Main.configuredStartRetryLimit());
        assertEquals(1_000, Main.configuredFinishHandshakeTimeoutMillis());
        assertEquals(5, Main.configuredFinishRetryLimit());
        assertEquals(6_250, Main.configuredReceiverCompletionGraceMillis());
    }

    @Test
    void systemPropertiesOverrideAllApplicationTimeoutDefaults() {
        System.setProperty("nettransfer.receiverInitialTimeoutMs", "5000");
        System.setProperty("nettransfer.receiverInactivityTimeoutMs", "6000");
        System.setProperty("nettransfer.startHandshakeTimeoutMs", "700");
        System.setProperty("nettransfer.startRetryLimit", "2");
        System.setProperty("nettransfer.finishHandshakeTimeoutMs", "400");
        System.setProperty("nettransfer.finishRetryLimit", "3");

        assertEquals(5000, Main.configuredReceiverInitialTimeoutMillis());
        assertEquals(6000, Main.configuredReceiverInactivityTimeoutMillis());
        assertEquals(700, Main.configuredStartHandshakeTimeoutMillis());
        assertEquals(2, Main.configuredStartRetryLimit());
        assertEquals(400, Main.configuredFinishHandshakeTimeoutMillis());
        assertEquals(3, Main.configuredFinishRetryLimit());
        assertEquals(1_850, Main.configuredReceiverCompletionGraceMillis());
    }

    @Test
    void initialTimeoutCleansTemporaryFileAndReleasesPort() throws Exception {
        FileStorageManager storage = new FileStorageManager(projectRoot);
        int port;
        try (UdpChannel probe = new UdpChannel(0)) {
            port = probe.getLocalPort();
        }
        Main.runReceiver(port, "new.txt", storage, 40, 40);
        assertFalse(Files.exists(storage.getIncomingDirectory().resolve("new.txt")));
        try (var files = Files.list(projectRoot.resolve("storage"))) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
        try (UdpChannel reused = new UdpChannel(port)) {
            assertEquals(port, reused.getLocalPort());
        }
    }

    @Test
    void existingIncomingFileIsPreservedBeforeReceiverBinds() throws Exception {
        FileStorageManager storage = new FileStorageManager(projectRoot);
        Path existing = storage.getIncomingDirectory().resolve("existing.txt");
        Files.writeString(existing, "keep me");
        int port;
        try (UdpChannel probe = new UdpChannel(0)) {
            port = probe.getLocalPort();
        }
        assertThrows(FileAlreadyExistsException.class,
                () -> Main.runReceiver(port, "existing.txt", storage, 40, 40));
        assertEquals("keep me", Files.readString(existing));
        try (UdpChannel reused = new UdpChannel(port)) {
            assertEquals(port, reused.getLocalPort());
        }
    }

    @Test
    void bindFailureCleansTemporaryFile() throws Exception {
        FileStorageManager storage = new FileStorageManager(projectRoot);
        try (UdpChannel occupied = new UdpChannel(0)) {
            assertThrows(java.net.BindException.class,
                    () -> Main.runReceiver(occupied.getLocalPort(), "new.txt", storage, 40, 40));
        }
        assertFalse(Files.exists(storage.getIncomingDirectory().resolve("new.txt")));
        try (var files = Files.list(projectRoot.resolve("storage"))) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".part")));
        }
    }
}
