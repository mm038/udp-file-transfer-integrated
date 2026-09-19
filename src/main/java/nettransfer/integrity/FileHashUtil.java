package nettransfer.integrity;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Computes a SHA-256 hash of a file, streamed in fixed-size chunks so the
 * whole file never needs to sit in memory at once -- consistent with
 * FileChunker's approach for the same reason (must scale to large files).
 *
 * This is a WHOLE-FILE integrity check, separate from and complementary
 * to PacketValidator's per-packet CRC-32:
 *   - CRC-32 (Stage 5) catches corruption of an individual packet in
 *     transit, checked as each one arrives.
 *   - SHA-256 (this class) catches anything wrong with the RECONSTRUCTED
 *     file as a whole -- e.g. a chunk written to the wrong offset, a bug
 *     in reassembly order, or (extremely unlikely, but the reason a
 *     cryptographic hash rather than another CRC is used here) two
 *     different corrupted contents that happen to produce the same CRC.
 *
 * Computed once by the sender (before transfer, sent in the FINISH
 * message per PROTOCOL.md) and once by the receiver (after every chunk
 * has been written to disk); the two are compared to give the final
 * transfer-succeeded/failed verdict.
 */
public class FileHashUtil {

    private static final String ALGORITHM = "SHA-256";
    private static final int BUFFER_SIZE = 8192;

    public static String sha256Hex(String filePath) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory algorithm on every standard JVM -- this
            // should never actually happen. Wrapped as unchecked because it
            // would indicate something wrong with the JVM itself, not a
            // normal, recoverable I/O problem.
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }

        try (FileInputStream fileInputStream = new FileInputStream(filePath)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }

        return bytesToHex(digest.digest());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}