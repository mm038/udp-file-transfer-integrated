package nettransfer.control.command;

import nettransfer.control.TransferError;
import nettransfer.control.TransferRequest;
import nettransfer.control.TransferServiceException;
import nettransfer.control.TransferSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static nettransfer.control.TransferError.Code.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CommandValidatorTest {
    @TempDir
    Path temporaryDirectory;

    private Path applicationRoot;
    private Path source;
    private CommandValidator validator;

    @BeforeEach
    void configureApprovedFileAndReceiver() throws IOException {
        applicationRoot = Files.createDirectory(temporaryDirectory.resolve("app"));
        Path inputRoot = Files.createDirectories(applicationRoot.resolve("data/input"));
        source = Files.writeString(inputRoot.resolve("report.txt"), "Approved test input");
        validator = validatorFor(Map.of("report", Path.of("data/input/report.txt")));
    }

    @Test
    void javaDefaultsMatchTheExistingEngineAndResolveFromTheExplicitApplicationRoot() throws IOException {
        UUID requestId = UUID.randomUUID();

        TransferRequest request = validator.validateStart(start(null, null), requestId);

        assertEquals(requestId, request.requestId());
        assertNotNull(request.transferId());
        assertEquals("report", request.fileId());
        assertEquals("receiver-a", request.receiverId());
        assertEquals(source.toRealPath(), request.sourcePath());
        assertTrue(request.sourcePath().isAbsolute());
        assertEquals(new InetSocketAddress("127.0.0.1", 9000), request.receiver());
        assertEquals(new TransferSettings(1024, 1024, 1, 200, 5), request.settings());
    }

    @ParameterizedTest
    @CsvSource({"1024, 50, 1", "1048576, 5000, 1024", "65536, 80, 64", "2047, 200, 1"})
    void acceptsInclusiveBoundsAndRoundsByteWindowsDownToWholePackets(long windowBytes,
                                                                      long timeoutMillis,
                                                                      int expectedPackets) {
        TransferSettings settings = validator.validateStart(start(windowBytes, timeoutMillis),
                UUID.randomUUID()).settings();

        assertEquals(windowBytes, settings.requestedWindowBytes());
        assertEquals(expectedPackets, settings.windowPackets());
        assertEquals(timeoutMillis, settings.timeoutMillis());
        assertEquals(1024, settings.chunkSizeBytes());
        assertEquals(5, settings.retryLimit());
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0, 1023, 1048577, Long.MAX_VALUE})
    void rejectsInvalidWindowBudgetsBeforeNarrowing(long windowBytes) {
        assertRejected(INVALID_PARAMETER, validator, start(windowBytes, null));
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0, 49, 5001, Long.MAX_VALUE})
    void rejectsInvalidTimeoutsBeforeNarrowing(long timeoutMillis) {
        assertRejected(INVALID_PARAMETER, validator, start(null, timeoutMillis));
    }

    @Test
    void rejectsUnknownFileAndReceiverIdsInsteadOfInterpretingThemAsResources() {
        assertRejected(UNKNOWN_FILE, validator,
                new TransferCommand.Start(source.toString(), "receiver-a", null, null));
        assertRejected(UNKNOWN_FILE, validator,
                new TransferCommand.Start("REPORT", "receiver-a", null, null));
        assertRejected(UNKNOWN_RECEIVER, validator,
                new TransferCommand.Start("report", "127.0.0.1:9000", null, null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void missingEssentialIdsRequestClarification(String missingId) {
        assertRejected(CLARIFICATION_REQUIRED, validator,
                new TransferCommand.Start(missingId, "receiver-a", null, null));
        assertRejected(CLARIFICATION_REQUIRED, validator,
                new TransferCommand.Start("report", missingId, null, null));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void missingExplanationQuestionRequestsClarification(String missingQuestion) {
        TransferServiceException exception = assertThrows(TransferServiceException.class,
                () -> validator.validateQuestion(missingQuestion));

        assertEquals(CLARIFICATION_REQUIRED, exception.error().code());
    }

    @Test
    void acceptsAnExplanationQuestionWithoutGeneratingAnExplanation() {
        assertDoesNotThrow(() -> validator.validateQuestion("Why did this run fail?"));
    }

    @Test
    void approvedAbsolutePathsInsideTheInputRootRemainUsable() throws IOException {
        TransferRequest request = validatorFor(Map.of("report", source))
                .validateStart(start(null, null), UUID.randomUUID());

        assertEquals(source.toRealPath(), request.sourcePath());
    }

    @Test
    void missingFilesAndDirectoriesAreUnavailableEvenWhenConfigured() {
        for (Path path : List.of(Path.of("data/input/missing.txt"), Path.of("data/input"))) {
            assertRejected(FILE_UNAVAILABLE, validatorFor(Map.of("report", path)), start(null, null));
        }
    }

    @Test
    void absolutePathsAndTraversalCannotApproveFilesOutsideTheInputRoot() throws IOException {
        Path outside = Files.writeString(applicationRoot.resolve("outside.txt"), "Outside input root");

        for (Path path : List.of(outside, Path.of("data/input/../../outside.txt"))) {
            assertRejected(FILE_UNAVAILABLE, validatorFor(Map.of("report", path)), start(null, null));
        }
    }

    @Test
    void configuredCataloguesAreDefensivelyCopiedAndImmutable() throws IOException {
        Map<String, Path> files = new HashMap<>(Map.of("report", Path.of("data/input/report.txt")));
        InetSocketAddress receiver = new InetSocketAddress("127.0.0.1", 9000);
        Map<String, InetSocketAddress> receivers = new HashMap<>(Map.of("receiver-a", receiver));
        TransferConfiguration configuration = new TransferConfiguration(applicationRoot, files, receivers);

        files.put("report", Path.of("outside.txt"));
        receivers.put("receiver-a", new InetSocketAddress("127.0.0.1", 9001));
        TransferRequest request = new CommandValidator(configuration)
                .validateStart(start(null, null), UUID.randomUUID());

        assertEquals(source.toRealPath(), request.sourcePath());
        assertEquals(receiver, request.receiver());
        assertThrows(UnsupportedOperationException.class,
                () -> configuration.approvedFiles().put("injected", source));
        assertThrows(UnsupportedOperationException.class,
                () -> configuration.approvedReceivers().clear());
    }

    @Test
    void configurationRejectsAnImplicitWorkingDirectoryAndInvalidReceiverEndpoints() {
        assertThrows(IllegalArgumentException.class,
                () -> TransferConfiguration.localhost(Path.of("relative-app"), Map.of()));
        for (InetSocketAddress endpoint : List.of(new InetSocketAddress("127.0.0.1", 0),
                InetSocketAddress.createUnresolved("unresolved.invalid", 9000))) {
            assertThrows(IllegalArgumentException.class,
                    () -> new TransferConfiguration(applicationRoot, Map.of(), Map.of("receiver-a", endpoint)));
        }
    }

    @Test
    void configurationRejectsBlankOrPaddedIds() {
        for (String id : List.of("", " ", " report", "receiver-a ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> TransferConfiguration.localhost(applicationRoot, Map.of(id, source)));
            assertThrows(IllegalArgumentException.class,
                    () -> new TransferConfiguration(applicationRoot, Map.of(),
                            Map.of(id, new InetSocketAddress("127.0.0.1", 9000))));
        }
    }

    @Test
    void directoryLinksCannotEscapeTheApprovedInputTree() throws Exception {
        Path externalDirectory = Files.createDirectory(temporaryDirectory.resolve("external"));
        Files.writeString(externalDirectory.resolve("secret.txt"), "Outside application root");
        Path link = applicationRoot.resolve("data/input/linked");
        createDirectoryLinkOrSkip(link, externalDirectory);
        try {
            assertRejected(FILE_UNAVAILABLE,
                    validatorFor(Map.of("report", Path.of("data/input/linked/secret.txt"))), start(null, null));
        } finally {
            // Delete only the link before @TempDir cleanup, never recursively traverse its target.
            Files.deleteIfExists(link);
        }
        assertTrue(Files.exists(externalDirectory.resolve("secret.txt")));
    }

    @Test
    void redirectingTheInputRootCannotApproveAnExternalDirectory() throws Exception {
        Path externalDirectory = Files.createDirectory(temporaryDirectory.resolve("external"));
        Files.writeString(externalDirectory.resolve("report.txt"), "External report");
        Path redirectedApplication = Files.createDirectory(temporaryDirectory.resolve("redirected-app"));
        Files.createDirectory(redirectedApplication.resolve("data"));
        Path link = redirectedApplication.resolve("data/input");
        createDirectoryLinkOrSkip(link, externalDirectory);
        try {
            CommandValidator redirectedValidator = new CommandValidator(TransferConfiguration.localhost(
                    redirectedApplication, Map.of("report", Path.of("data/input/report.txt"))));

            assertRejected(FILE_UNAVAILABLE, redirectedValidator, start(null, null));
        } finally {
            Files.deleteIfExists(link);
        }
        assertTrue(Files.exists(externalDirectory.resolve("report.txt")));
    }

    @Test
    void redirectingTheInputRootCannotBroadenApprovalToOtherApplicationDirectories() throws Exception {
        Path redirectedApplication = Files.createDirectory(temporaryDirectory.resolve("redirected-app"));
        Path otherDirectory = Files.createDirectory(redirectedApplication.resolve("private"));
        Files.writeString(otherDirectory.resolve("report.txt"), "Not under the fixed input root");
        Files.createDirectory(redirectedApplication.resolve("data"));
        Path link = redirectedApplication.resolve("data/input");
        createDirectoryLinkOrSkip(link, otherDirectory);
        try {
            CommandValidator redirectedValidator = new CommandValidator(TransferConfiguration.localhost(
                    redirectedApplication, Map.of("report", Path.of("data/input/report.txt"))));

            assertRejected(FILE_UNAVAILABLE, redirectedValidator, start(null, null));
        } finally {
            Files.deleteIfExists(link);
        }
        assertTrue(Files.exists(otherDirectory.resolve("report.txt")));
    }

    @Test
    void sourceDirectoryLinksThatStayWithinTheInputRootAreAllowed() throws Exception {
        Path permittedDirectory = Files.createDirectory(applicationRoot.resolve("data/input/subdirectory"));
        Path permittedFile = Files.writeString(permittedDirectory.resolve("report.txt"), "Approved linked input");
        Path link = applicationRoot.resolve("data/input/linked");
        createDirectoryLinkOrSkip(link, permittedDirectory);
        try {
            TransferRequest request = validatorFor(Map.of("report", Path.of("data/input/linked/report.txt")))
                    .validateStart(start(null, null), UUID.randomUUID());

            assertEquals(permittedFile.toRealPath(), request.sourcePath());
        } finally {
            Files.deleteIfExists(link);
        }
        assertTrue(Files.exists(permittedFile));
    }

    @Test
    void unreadableApprovedFilesAreUnavailable() throws IOException {
        AclFileAttributeView aclView = Files.getFileAttributeView(source, AclFileAttributeView.class);
        if (aclView != null) {
            List<AclEntry> originalAcl = aclView.getAcl();
            List<AclEntry> deniedAcl = new ArrayList<>(originalAcl);
            deniedAcl.add(0, AclEntry.newBuilder().setType(AclEntryType.DENY)
                    .setPrincipal(Files.getOwner(source)).setPermissions(AclEntryPermission.READ_DATA).build());
            try {
                aclView.setAcl(deniedAcl);
                assumeFalse(Files.isReadable(source), "This account bypasses the temporary read-denial ACL");
                assertRejected(FILE_UNAVAILABLE, validator, start(null, null));
            } finally {
                aclView.setAcl(originalAcl);
            }
            return;
        }

        PosixFileAttributeView posixView = Files.getFileAttributeView(source, PosixFileAttributeView.class);
        assumeTrue(posixView != null, "Filesystem exposes neither ACL nor POSIX read permissions");
        Set<PosixFilePermission> originalPermissions = posixView.readAttributes().permissions();
        try {
            posixView.setPermissions(Set.of());
            assumeFalse(Files.isReadable(source), "This account bypasses temporary POSIX read denial");
            assertRejected(FILE_UNAVAILABLE, validator, start(null, null));
        } finally {
            posixView.setPermissions(originalPermissions);
        }
    }

    private CommandValidator validatorFor(Map<String, Path> files) {
        return new CommandValidator(TransferConfiguration.localhost(applicationRoot, files));
    }

    private static TransferCommand.Start start(Long windowBytes, Long timeoutMillis) {
        return new TransferCommand.Start("report", "receiver-a", windowBytes, timeoutMillis);
    }

    private static void assertRejected(TransferError.Code code, CommandValidator validator,
                                       TransferCommand.Start command) {
        TransferServiceException exception = assertThrows(TransferServiceException.class,
                () -> validator.validateStart(command, UUID.randomUUID()));
        assertEquals(code, exception.error().code());
        assertFalse(exception.getMessage().isBlank());
    }

    private static void createDirectoryLinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            assumeTrue(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows"),
                    "Cannot create a directory symlink on this filesystem: " + failure);
        }

        // Junctions cover the same real-path escape without Windows symlink privileges.
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        boolean finished = process.waitFor(5, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("Timed out while creating a temporary directory junction");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assumeTrue(process.exitValue() == 0, "Cannot create a directory symlink or junction: " + output);
    }
}
