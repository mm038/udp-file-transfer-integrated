package nettransfer.llm;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** API deadlines are independent of the UDP sender's retransmission timeout. */
public record GptSettings(String model, Duration connectTimeout, Duration requestTimeout, int maxAttempts) {
    private static final long MIN_TIMEOUT_MS = 100;
    private static final long MAX_TIMEOUT_MS = 120_000;

    public GptSettings {
        if (model == null || !model.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                || !validTimeout(connectTimeout) || !validTimeout(requestTimeout)
                || maxAttempts < 1 || maxAttempts > 3) {
            throw new GptException(GptException.Code.INVALID_CONFIGURATION);
        }
    }

    public static GptSettings defaults() {
        return new GptSettings("gpt-5-mini", Duration.ofSeconds(5), Duration.ofSeconds(30), 2);
    }

    public static GptSettings fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        GptSettings defaults = defaults();
        return new GptSettings(environment.getOrDefault("OPENAI_MODEL", defaults.model()),
                readTimeout(environment, "OPENAI_CONNECT_TIMEOUT_MS", defaults.connectTimeout()),
                readTimeout(environment, "OPENAI_REQUEST_TIMEOUT_MS", defaults.requestTimeout()),
                defaults.maxAttempts());
    }

    private static Duration readTimeout(Map<String, String> environment, String name, Duration fallback) {
        String value = environment.get(name);
        if (value == null) {
            return fallback;
        }
        try {
            if (!value.matches("[0-9]+")) {
                throw new NumberFormatException();
            }
            return Duration.ofMillis(Long.parseLong(value));
        } catch (NumberFormatException exception) {
            throw new GptException(GptException.Code.INVALID_CONFIGURATION);
        }
    }

    private static boolean validTimeout(Duration duration) {
        // Compare durations directly: toMillis() on an arbitrary input can overflow.
        return duration != null && duration.compareTo(Duration.ofMillis(MIN_TIMEOUT_MS)) >= 0
                && duration.compareTo(Duration.ofMillis(MAX_TIMEOUT_MS)) <= 0;
    }
}
