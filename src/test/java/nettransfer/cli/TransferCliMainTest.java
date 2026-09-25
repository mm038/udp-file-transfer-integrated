package nettransfer.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import nettransfer.llm.GptException;
import nettransfer.llm.InterpretationRequest;
import nettransfer.explanation.ExplanationRequest;
import nettransfer.explanation.SyntheticExplanationFixtures;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TransferCliMainTest {
    @TempDir Path root;

    @Test
    void redirectedConsolePreservesUnicodeInputAndOutputAsUtf8() throws Exception {
        String punctuation = "Quoted \u2018report\u2019 \u2014 ready\u2026";
        var bytes = new ByteArrayOutputStream();
        var output = TransferCliMain.redirectedOutput(bytes);
        output.println(punctuation);
        assertArrayEquals((punctuation + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                bytes.toByteArray());

        var input = new BufferedReader(TransferCliMain.redirectedInput(
                new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(punctuation, input.readLine());
        assertNull(input.readLine());
    }

    @Test
    void missingCredentialsAreReportedOnlyWhenNaturalLanguageIsUsed() {
        var client = assertDoesNotThrow(() -> TransferCliMain.gptFromEnvironment(Map.of()));
        var error = assertThrows(GptException.class, () -> client.interpret(request()));
        assertEquals(GptException.Code.MISSING_CREDENTIALS, error.code());
    }

    @Test
    void invalidOptionalApiConfigurationDoesNotAbortConsoleStartup() {
        var client = assertDoesNotThrow(() -> TransferCliMain.gptFromEnvironment(
                Map.of("OPENAI_API_KEY", "test-secret", "OPENAI_REQUEST_TIMEOUT_MS", "invalid")));
        var error = assertThrows(GptException.class, () -> client.interpret(request()));
        assertEquals(GptException.Code.INVALID_CONFIGURATION, error.code());
        assertFalse(error.toString().contains("test-secret"));
    }

    private static InterpretationRequest request() {
        return new InterpretationRequest(UUID.randomUUID(), "Hello", List.of("report"), List.of("receiver-a"),
                null, null, null, null, List.of());
    }

    @Test
    void explanationCredentialsAreCheckedOnlyWhenAnalysisIsInvoked() {
        var client = assertDoesNotThrow(() -> TransferCliMain.explanationFromEnvironment(Map.of()));
        var error = assertThrows(GptException.class, () -> client.explain(explanationRequest()));
        assertEquals(GptException.Code.MISSING_CREDENTIALS, error.code());
    }

    @Test
    void invalidExplanationConfigurationDoesNotAbortConsoleStartup() {
        var client = assertDoesNotThrow(() -> TransferCliMain.explanationFromEnvironment(
                Map.of("OPENAI_API_KEY", "test-secret", "OPENAI_REQUEST_TIMEOUT_MS", "invalid")));
        var error = assertThrows(GptException.class, () -> client.explain(explanationRequest()));
        assertEquals(GptException.Code.INVALID_CONFIGURATION, error.code());
        assertFalse(error.toString().contains("test-secret"));
    }

    private static ExplanationRequest explanationRequest() {
        var fixture = SyntheticExplanationFixtures.baseline();
        return new ExplanationRequest(UUID.randomUUID(), "Explain these synthetic measurements",
                fixture.evidence(), fixture.state(), fixture.integrity());
    }

    @Test
    void trustedStartupArgumentsCreateExplicitFileCatalogAndFixedReceiver() {
        var config = TransferCliMain.configurationFromArgs(new String[]{root.toString(),
                "report=storage/outgoing/report.txt", "other=storage/outgoing/other file.txt"});

        assertEquals(root, config.applicationRoot());
        assertEquals(Path.of("storage/outgoing/report.txt"), config.approvedFiles().get("report"));
        assertEquals(Path.of("storage/outgoing/other file.txt"), config.approvedFiles().get("other"));
        assertEquals("127.0.0.1", config.approvedReceivers().get("receiver-a").getAddress().getHostAddress());
        assertEquals(9000, config.approvedReceivers().get("receiver-a").getPort());
        assertEquals(root.resolve("logs"), TransferCliMain.loggingRoot(config));
    }

    @Test
    void runtimeUsesOneTrustedLoggingRootForSenderAndEvidencePipeline() throws Exception {
        var config = TransferCliMain.configurationFromArgs(new String[]{root.toString(),
                "report=storage/outgoing/report.txt"});

        try (var runtime = TransferCliMain.realRuntime(config, request -> {
            throw new AssertionError("Runtime construction must not invoke the explanation client");
        })) {
            assertEquals(TransferCliMain.loggingRoot(config).toAbsolutePath().normalize(),
                    runtime.loggingRoot());
            assertNotNull(runtime.service());
            assertNotNull(runtime.explanations());
        }
    }

    @Test
    void relativeRootIsNormalizedToAnAbsolutePath() {
        var config = TransferCliMain.configurationFromArgs(new String[]{".", "report=storage/outgoing/report.txt"});

        assertEquals(Path.of(".").toAbsolutePath().normalize(), config.applicationRoot());
    }

    @Test
    void invalidAndDuplicateEntriesAreRejectedInsteadOfSilentlyReplacingFiles() {
        for (String[] args : new String[][]{
                {}, {root.toString()}, {root.toString(), "report"}, {root.toString(), "=storage/outgoing/a"},
                {root.toString(), "report="}, {root.toString(), " =storage/outgoing/a"},
                {root.toString(), "report=storage/outgoing/a", "report=storage/outgoing/b"},
                {root.toString(), "report=" + root.resolve("storage/outgoing/a")}}) {
            assertThrows(IllegalArgumentException.class, () -> TransferCliMain.configurationFromArgs(args));
        }
    }
}
