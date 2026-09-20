package nettransfer.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TransferCliMainTest {
    @TempDir Path root;

    @Test
    void trustedStartupArgumentsCreateExplicitFileCatalogAndFixedReceiver() {
        var config = TransferCliMain.configurationFromArgs(new String[]{root.toString(),
                "report=data/input/report.txt", "other=data/input/other file.txt"});

        assertEquals(root, config.applicationRoot());
        assertEquals(Path.of("data/input/report.txt"), config.approvedFiles().get("report"));
        assertEquals(Path.of("data/input/other file.txt"), config.approvedFiles().get("other"));
        assertEquals("127.0.0.1", config.approvedReceivers().get("receiver-a").getAddress().getHostAddress());
        assertEquals(9000, config.approvedReceivers().get("receiver-a").getPort());
    }

    @Test
    void relativeRootIsNormalizedToAnAbsolutePath() {
        var config = TransferCliMain.configurationFromArgs(new String[]{".", "report=data/input/report.txt"});

        assertEquals(Path.of(".").toAbsolutePath().normalize(), config.applicationRoot());
    }

    @Test
    void invalidAndDuplicateEntriesAreRejectedInsteadOfSilentlyReplacingFiles() {
        for (String[] args : new String[][]{
                {}, {root.toString()}, {root.toString(), "report"}, {root.toString(), "=data/input/a"},
                {root.toString(), "report="}, {root.toString(), " =data/input/a"},
                {root.toString(), "report=data/input/a", "report=data/input/b"},
                {root.toString(), "report=" + root.resolve("data/input/a")}}) {
            assertThrows(IllegalArgumentException.class, () -> TransferCliMain.configurationFromArgs(args));
        }
    }
}
