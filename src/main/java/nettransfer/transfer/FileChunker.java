package nettransfer.transfer;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Splits a file into ordered, fixed-size chunks and allows reading any
 * chunk by its sequence number (0-indexed), not just sequentially.
 *
 * Random access (not just sequential streaming) is required because the
 * sender must be able to re-read and retransmit an arbitrary earlier chunk
 * after a timeout (Stage 7/8) without keeping the whole file in memory.
 *
 * All chunks are exactly chunkSize bytes except possibly the last, which
 * may be shorter. An empty file is treated as a single zero-length chunk
 * so the handshake/DATA/FINISH lifecycle doesn't need a special case.
 */
public class FileChunker implements AutoCloseable {

    private final RandomAccessFile file;
    private final long fileSize;
    private final int chunkSize;
    private final int totalChunks;

    public FileChunker(String filePath, int chunkSize) throws IOException {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive: " + chunkSize);
        }
        this.file = new RandomAccessFile(filePath, "r");
        this.fileSize = file.length();
        this.chunkSize = chunkSize;
        // ceil(fileSize / chunkSize), minimum 1 so an empty file still yields one chunk
        this.totalChunks = (int) Math.max(1, (fileSize + chunkSize - 1) / chunkSize);
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public long getFileSize() {
        return fileSize;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    /** Payload length of this chunk (== chunkSize except possibly the last). */
    public int getChunkLength(int seqNum) {
        validateSeqNum(seqNum);
        if (seqNum < totalChunks - 1) {
            return chunkSize;
        }
        long remainder = fileSize - (long) seqNum * chunkSize;
        return (int) Math.max(0, remainder);
    }

    /** Seeks to and reads chunk seqNum. Safe to call in any order, any number of times. */
    public synchronized byte[] readChunk(int seqNum) throws IOException {
        validateSeqNum(seqNum);
        byte[] buffer = new byte[getChunkLength(seqNum)];
        file.seek((long) seqNum * chunkSize);
        file.readFully(buffer);
        return buffer;
    }

    private void validateSeqNum(int seqNum) {
        if (seqNum < 0 || seqNum >= totalChunks) {
            throw new IndexOutOfBoundsException(
                    "seqNum " + seqNum + " out of range [0, " + totalChunks + ")");
        }
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}