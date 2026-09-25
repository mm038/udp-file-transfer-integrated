package nettransfer.net;

import java.util.Objects;
import java.util.Properties;

/** Explicit, immutable configuration for receive-side experimental impairment. */
public record ImpairmentSettings(boolean enabled, double lossPercent, int delayMillis,
                                 long seed, String scenario) {
    public static final String MECHANISM = "RECEIVE_DELIVERY_V1";
    private static final String PREFIX = "nettransfer.impairment.";

    public ImpairmentSettings {
        if (!Double.isFinite(lossPercent) || lossPercent < 0 || lossPercent > 100) {
            throw new IllegalArgumentException("Impairment lossPercent must be finite and between 0 and 100");
        }
        if (delayMillis < 0 || delayMillis > 5_000) {
            throw new IllegalArgumentException("Impairment delayMs must be between 0 and 5000");
        }
        if (scenario == null || scenario.isBlank()) {
            throw new IllegalArgumentException("Impairment scenario must not be blank");
        }
        if (scenario.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Impairment scenario must not contain control characters");
        }
        scenario = scenario.trim();
        if (scenario.length() > 128) {
            throw new IllegalArgumentException("Impairment scenario must contain at most 128 characters");
        }
        if (!enabled && (lossPercent != 0 || delayMillis != 0 || seed != 0 || !scenario.equals("baseline"))) {
            throw new IllegalArgumentException("Disabled impairment must use the baseline settings");
        }
    }

    public static ImpairmentSettings disabled() {
        return new ImpairmentSettings(false, 0, 0, 0, "baseline");
    }

    public static ImpairmentSettings fromSystemProperties() {
        return fromProperties(System.getProperties());
    }

    public static ImpairmentSettings fromProperties(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        String enabled = properties.getProperty(PREFIX + "enabled");
        boolean hasParameters = false;
        for (String key : new String[]{"lossPercent", "delayMs", "seed", "scenario"}) {
            hasParameters |= properties.getProperty(PREFIX + key) != null;
        }
        if (enabled == null || enabled.equalsIgnoreCase("false")) {
            if (hasParameters) {
                throw new IllegalArgumentException("Impairment parameters require nettransfer.impairment.enabled=true");
            }
            return disabled();
        }
        if (!enabled.equalsIgnoreCase("true")) {
            throw new IllegalArgumentException("nettransfer.impairment.enabled must be true or false");
        }
        try {
            return new ImpairmentSettings(true,
                    Double.parseDouble(required(properties, "lossPercent")),
                    Integer.parseInt(required(properties, "delayMs")),
                    Long.parseLong(required(properties, "seed")), required(properties, "scenario"));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid numeric impairment setting", exception);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(PREFIX + key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Enabled impairment requires " + PREFIX + key);
        }
        return value.trim();
    }
}
