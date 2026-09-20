package nettransfer.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GptSettingsTest {
    @Test
    void defaultsKeepApiDeadlinesSeparateFromUdpTimeouts() {
        GptSettings settings = GptSettings.fromEnvironment(Map.of());

        assertEquals("gpt-5-mini", settings.model());
        assertEquals(Duration.ofSeconds(5), settings.connectTimeout());
        assertEquals(Duration.ofSeconds(30), settings.requestTimeout());
        assertEquals(2, settings.maxAttempts());
    }

    @Test
    void environmentCanConfigureModelAndBothTimeoutsWithoutCredentials() {
        GptSettings settings = GptSettings.fromEnvironment(Map.of(
                "OPENAI_MODEL", "gpt-5-mini-2025-08-07",
                "OPENAI_CONNECT_TIMEOUT_MS", "750",
                "OPENAI_REQUEST_TIMEOUT_MS", "1500"));

        assertEquals("gpt-5-mini-2025-08-07", settings.model());
        assertEquals(Duration.ofMillis(750), settings.connectTimeout());
        assertEquals(Duration.ofMillis(1500), settings.requestTimeout());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "0", "99", "120001", "-1", "1.5", "1e3", "forever",
            "999999999999999999999999"})
    void invalidRequestTimeoutsFailWithSafeConfigurationError(String value) {
        GptException error = assertThrows(GptException.class,
                () -> GptSettings.fromEnvironment(Map.of("OPENAI_REQUEST_TIMEOUT_MS", value)));
        assertEquals(GptException.Code.INVALID_CONFIGURATION, error.code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "120001", "not-a-timeout"})
    void invalidConnectTimeoutsFailWithSafeConfigurationError(String value) {
        GptException error = assertThrows(GptException.class,
                () -> GptSettings.fromEnvironment(Map.of("OPENAI_CONNECT_TIMEOUT_MS", value)));
        assertEquals(GptException.Code.INVALID_CONFIGURATION, error.code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "gpt 5", "../arbitrary", "gpt\nmodel"})
    void invalidModelNamesFailWithoutEchoingTheInput(String model) {
        GptException error = assertThrows(GptException.class,
                () -> GptSettings.fromEnvironment(Map.of("OPENAI_MODEL", model)));
        assertEquals(GptException.Code.INVALID_CONFIGURATION, error.code());
        if (model.length() > 2) assertFalse(error.getMessage().contains(model));
    }

    @Test
    void constructorEnforcesFiniteTimeoutsAndBoundedAttempts() {
        assertConfigurationError(() -> new GptSettings("gpt-5-mini", Duration.ZERO, Duration.ofSeconds(1), 1));
        assertConfigurationError(() -> new GptSettings("gpt-5-mini", Duration.ofSeconds(1), Duration.ZERO, 1));
        assertConfigurationError(() -> new GptSettings("gpt-5-mini", Duration.ofSeconds(1), Duration.ofSeconds(121), 1));
        assertConfigurationError(() -> new GptSettings("gpt-5-mini", Duration.ofSeconds(1), Duration.ofSeconds(1), 0));
        assertConfigurationError(() -> new GptSettings("gpt-5-mini", Duration.ofSeconds(1), Duration.ofSeconds(1), 4));
    }

    private static void assertConfigurationError(org.junit.jupiter.api.function.Executable action) {
        assertEquals(GptException.Code.INVALID_CONFIGURATION,
                assertThrows(GptException.class, action).code());
    }
}
