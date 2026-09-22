package nettransfer.storage;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

/** Resolves user-facing filenames within the project's transfer storage. */
public final class FileStorageManager {

    private static final Pattern WINDOWS_DEVICE_NAME = Pattern.compile(
            "(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?");

    private final Path storageDirectory;
    private final Path outgoingDirectory;
    private final Path incomingDirectory;

    public FileStorageManager() throws IOException {
        this(Path.of("").toAbsolutePath());
    }

    /** The supplied directory is the project root, useful for isolated tests. */
    public FileStorageManager(Path projectRoot) throws IOException {
        if (projectRoot == null) {
            throw new IllegalArgumentException("Project root is required");
        }
        storageDirectory = projectRoot.toAbsolutePath().normalize().resolve("storage");
        outgoingDirectory = storageDirectory.resolve("outgoing");
        incomingDirectory = storageDirectory.resolve("incoming");
        createStorageDirectory(storageDirectory);
        createStorageDirectory(outgoingDirectory);
        createStorageDirectory(incomingDirectory);
    }

    public Path getOutgoingDirectory() {
        return outgoingDirectory;
    }

    public Path getIncomingDirectory() {
        return incomingDirectory;
    }

    /** Accepts one filename, not a path supplied by an LLM or remote peer. */
    public Path resolveOutgoingFile(String filename) throws IOException {
        Path path = outgoingDirectory.resolve(validateFilename(filename));
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Outgoing file does not exist or is not a regular file: " + path);
        }
        if (!Files.isReadable(path)) {
            throw new IOException("Outgoing file is not readable: " + path);
        }
        return path;
    }

    /** Checks a destination before the receiver starts; publishing checks it again. */
    public Path resolveIncomingFile(String filename) throws IOException {
        Path path = incomingDirectory.resolve(validateFilename(filename));
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException("Incoming file already exists: " + path);
        }
        return path;
    }

    /** Keeps incomplete transfers outside incoming until verification succeeds. */
    public Path createIncomingTemporaryFile() throws IOException {
        return Files.createTempFile(storageDirectory, ".incoming-", ".part");
    }

    /** Moves a verified transfer into incoming without replacing an existing file. */
    public Path publishIncomingFile(Path temporaryFile, String filename) throws IOException {
        Path destination = resolveIncomingFile(filename);
        if (temporaryFile == null
                || !temporaryFile.toAbsolutePath().normalize().getParent().equals(storageDirectory)
                || !temporaryFile.getFileName().toString().startsWith(".incoming-")
                || !Files.isRegularFile(temporaryFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid incoming temporary file: " + temporaryFile);
        }
        // No REPLACE_EXISTING: a file created after the first check must also be preserved.
        return Files.move(temporaryFile, destination);
    }

    public static String validateFilename(String filename) {
        if (filename == null || filename.isBlank() || filename.equals(".") || filename.equals("..")
                || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0
                || filename.indexOf(':') >= 0 || filename.endsWith(".") || filename.endsWith(" ")
                || WINDOWS_DEVICE_NAME.matcher(filename).matches()) {
            throw new IllegalArgumentException("Expected a safe filename, not a path: " + filename);
        }
        for (int i = 0; i < filename.length(); i++) {
            char character = filename.charAt(i);
            if (Character.isISOControl(character) || "<>\"|?*".indexOf(character) >= 0) {
                throw new IllegalArgumentException("Filename contains an invalid character: " + filename);
            }
        }
        return filename;
    }

    private static void createStorageDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Storage directory must not be a symbolic link: " + directory);
        }
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Storage directory cannot be used: " + directory);
        }
    }
}
