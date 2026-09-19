package nettransfer.integrity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileHashUtilTest {

    @Test
    void emptyFileHashesToKnownSha256Value(@TempDir Path tempDir) throws IOException {
        Path f = tempDir.resolve("empty.bin");
        Files.write(f, new byte[0]);

        // SHA-256 of zero bytes is a fixed, publicly documented constant --
        // any correct implementation must produce exactly this value.
        String expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        assertEquals(expected, FileHashUtil.sha256Hex(f.toString()));
    }

    @Test
    void identicalContentProducesIdenticalHash(@TempDir Path tempDir) throws IOException {
        byte[] data = "identical content".getBytes();
        Path a = tempDir.resolve("a.bin");
        Path b = tempDir.resolve("b.bin");
        Files.write(a, data);
        Files.write(b, data);

        assertEquals(FileHashUtil.sha256Hex(a.toString()), FileHashUtil.sha256Hex(b.toString()));
    }

    @Test
    void differentContentProducesDifferentHash(@TempDir Path tempDir) throws IOException {
        Path a = tempDir.resolve("a.bin");
        Path b = tempDir.resolve("b.bin");
        Files.write(a, "content A".getBytes());
        Files.write(b, "content B".getBytes());

        assertNotEquals(FileHashUtil.sha256Hex(a.toString()), FileHashUtil.sha256Hex(b.toString()));
    }

    @Test
    void singleByteCorruptionChangesHash(@TempDir Path tempDir) throws IOException {
        // Simulates exactly the scenario this class exists to catch:
        // a "reconstructed" file that differs from the original by one byte.
        byte[] original = new byte[500];
        for (int i = 0; i < original.length; i++) original[i] = (byte) i;

        byte[] corrupted = original.clone();
        corrupted[250] ^= 0xFF;

        Path originalFile = tempDir.resolve("original.bin");
        Path corruptedFile = tempDir.resolve("corrupted.bin");
        Files.write(originalFile, original);
        Files.write(corruptedFile, corrupted);

        assertNotEquals(
                FileHashUtil.sha256Hex(originalFile.toString()),
                FileHashUtil.sha256Hex(corruptedFile.toString()));
    }

    @Test
    void nonExistentFileThrowsIOException() {
        assertThrows(IOException.class, () -> FileHashUtil.sha256Hex("this/path/does/not/exist.bin"));
    }
}