package nettransfer.net;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ImpairmentSettingsTest {
    @Test
    void absentConfigurationDisablesImpairment() {
        assertEquals(ImpairmentSettings.disabled(), ImpairmentSettings.fromProperties(new Properties()));
    }

    @Test
    void enabledConfigurationRequiresEverySetting() {
        for (String key : new String[]{"lossPercent", "delayMs", "seed", "scenario"}) {
            Properties properties = configured();
            properties.remove("nettransfer.impairment." + key);
            assertThrows(IllegalArgumentException.class, () -> ImpairmentSettings.fromProperties(properties), key);
        }
    }

    @Test
    void parametersCannotBeSilentlyIgnored() {
        Properties properties = configured();
        properties.remove("nettransfer.impairment.enabled");
        assertThrows(IllegalArgumentException.class, () -> ImpairmentSettings.fromProperties(properties));
        properties.setProperty("nettransfer.impairment.enabled", "false");
        assertThrows(IllegalArgumentException.class, () -> ImpairmentSettings.fromProperties(properties));
    }

    @Test
    void explicitConfigurationRetainsUnitsAndSeed() {
        assertEquals(new ImpairmentSettings(true, 2.5, 80, -1234, "loss-delay"),
                ImpairmentSettings.fromProperties(configured()));
    }

    @Test
    void malformedValuesAndOutOfRangeValuesAreRejected() {
        for (String value : new String[]{"-1", "100.1", "NaN", "Infinity", "unknown"}) {
            Properties properties = configured();
            properties.setProperty("nettransfer.impairment.lossPercent", value);
            assertThrows(IllegalArgumentException.class, () -> ImpairmentSettings.fromProperties(properties), value);
        }
        for (String value : new String[]{"-1", "5001", "1.5", "unknown"}) {
            Properties properties = configured();
            properties.setProperty("nettransfer.impairment.delayMs", value);
            assertThrows(IllegalArgumentException.class, () -> ImpairmentSettings.fromProperties(properties), value);
        }
        Properties badSeed = configured();
        badSeed.setProperty("nettransfer.impairment.seed", "1.5");
        assertThrows(IllegalArgumentException.class, () -> ImpairmentSettings.fromProperties(badSeed));
        Properties badBoolean = configured();
        badBoolean.setProperty("nettransfer.impairment.enabled", "yes");
        assertThrows(IllegalArgumentException.class, () -> ImpairmentSettings.fromProperties(badBoolean));
        assertThrows(IllegalArgumentException.class, () -> new ImpairmentSettings(true, 0, 0, 1, " "));
        assertThrows(IllegalArgumentException.class,
                () -> new ImpairmentSettings(true, 0, 0, 1, "x".repeat(129)));
        assertEquals(128, new ImpairmentSettings(true, 0, 0, 1, "x".repeat(128)).scenario().length());
        for (String control : new String[]{"\n", "\r", "\t", "\u007f"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ImpairmentSettings(true, 0, 0, 1, "loss" + control + "test"));
        }
    }

    private static Properties configured() {
        Properties properties = new Properties();
        properties.setProperty("nettransfer.impairment.enabled", "true");
        properties.setProperty("nettransfer.impairment.lossPercent", "2.5");
        properties.setProperty("nettransfer.impairment.delayMs", "80");
        properties.setProperty("nettransfer.impairment.seed", "-1234");
        properties.setProperty("nettransfer.impairment.scenario", "loss-delay");
        return properties;
    }
}
