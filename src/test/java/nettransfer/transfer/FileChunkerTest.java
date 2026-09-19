package nettransfer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class FileChunkerTest {

    @Test
    void exactMultipleOfChunkSize(@TempDir Path tempDir) throws IOException {
        Path f = tempDir.resolve("exact.bin");
        byte[] data = new byte[300];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        Files.write(f, data);

        try (FileChunker chunker = new FileChunker(f.toString(), 100)) {
            assertEquals(3, chunker.getTotalChunks());
            assertEquals(100, chunker.getChunkLength(2));
            assertArrayEquals(Arrays.copyOfRange(data, 200, 300), chunker.readChunk(2));
        }
    }

    @Test
    void shortLastChunk(@TempDir Path tempDir) throws IOException {
        Path f = tempDir.resolve("remainder.bin");
        Files.write(f, new byte[250]); // chunks of 100, 100, 50

        try (FileChunker chunker = new FileChunker(f.toString(), 100)) {
            assertEquals(3, chunker.getTotalChunks());
            assertEquals(50, chunker.getChunkLength(2));
            assertEquals(50, chunker.readChunk(2).length);
        }
    }

    @Test
    void fileSmallerThanChunkSize(@TempDir Path tempDir) throws IOException {
        Path f = tempDir.resolve("tiny.bin");
        Files.write(f, new byte[10]);

        try (FileChunker chunker = new FileChunker(f.toString(), 1024)) {
            assertEquals(1, chunker.getTotalChunks());
            assertEquals(10, chunker.getChunkLength(0));
        }
    }

    @Test
    void emptyFileYieldsOneZeroLengthChunk(@TempDir Path tempDir) throws IOException {
        Path f = tempDir.resolve("empty.bin");
        Files.write(f, new byte[0]);

        try (FileChunker chunker = new FileChunker(f.toString(), 1024)) {
            assertEquals(1, chunker.getTotalChunks());
            assertEquals(0, chunker.readChunk(0).length);
        }
    }

    @Test
    void outOfRangeSeqNumThrows(@TempDir Path tempDir) throws IOException {
        Path f = tempDir.resolve("small.bin");
        Files.write(f, new byte[50]);

        try (FileChunker chunker = new FileChunker(f.toString(), 100)) {
            assertThrows(IndexOutOfBoundsException.class, () -> chunker.readChunk(1));
            assertThrows(IndexOutOfBoundsException.class, () -> chunker.readChunk(-1));
        }
    }

    @Test
    void supportsOutOfOrderRandomAccess(@TempDir Path tempDir) throws IOException {
        // simulates a retransmission: read chunk 2, then jump back to 0, then 1
        Path f = tempDir.resolve("random.bin");
        byte[] data = new byte[30];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        Files.write(f, data);

        try (FileChunker chunker = new FileChunker(f.toString(), 10)) {
            assertArrayEquals(Arrays.copyOfRange(data, 20, 30), chunker.readChunk(2));
            assertArrayEquals(Arrays.copyOfRange(data, 0, 10), chunker.readChunk(0));
            assertArrayEquals(Arrays.copyOfRange(data, 10, 20), chunker.readChunk(1));
        }
    }

    @Test
    void invalidChunkSizeRejected(@TempDir Path tempDir) throws IOException {
        Path f = tempDir.resolve("x.bin");
        Files.write(f, new byte[10]);
        assertThrows(IllegalArgumentException.class, () -> new FileChunker(f.toString(), 0));
        assertThrows(IllegalArgumentException.class, () -> new FileChunker(f.toString(), -5));
    }
}