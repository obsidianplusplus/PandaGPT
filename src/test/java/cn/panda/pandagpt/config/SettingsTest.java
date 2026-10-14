package cn.panda.pandagpt.config;

import cn.panda.pandagpt.TestSupport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    @Test void defaultsHaveNoAutomaticExecutionOrProxy() {
        Settings settings = TestSupport.settings();
        assertFalse(settings.allowExecution()); assertFalse(settings.proxy().enabled());
        assertEquals(5, settings.historyRounds());
    }
    @Test void honorsModelAndStripsLegacyKeyQuery() {
        var config = TestSupport.config();
        config.set("panda.model_selection.gemini.model", "custom-model");
        config.set("gemini.api_url", "https://example.com/models/%s:generateContent?key=%s");
        Settings settings = Settings.load(config);
        assertEquals("custom-model", settings.model());
        assertFalse(settings.apiUrl().contains("?"));
    }
    @Test void rejectsInvalidConfigurationBeforeCreatingService() {
        for (String path : new String[]{"panda.requests.workers", "panda.requests.queue_size", "panda.requests.timeout_seconds"}) {
            var config = TestSupport.config(); config.set(path, 0);
            assertThrows(IllegalArgumentException.class, () -> Settings.load(config));
        }
        var config = TestSupport.config(); config.set("panda.command_awareness.executor", "console");
        assertThrows(IllegalArgumentException.class, () -> Settings.load(config));
    }
    @Test void secretsNeverAppearInSettingsString() {
        var config = TestSupport.config(); config.set("gemini.api_key", "test-secret");
        assertFalse(Settings.load(config).toString().contains("test-secret"));
    }
}
