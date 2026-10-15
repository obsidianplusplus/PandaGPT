package cn.panda.pandagpt.config;

import static org.junit.jupiter.api.Assertions.*;

import cn.panda.pandagpt.TestSupport;
import org.junit.jupiter.api.Test;

class SettingsTest {
    @Test
    void defaultsHaveNoAutomaticExecutionOrProxy() {
        Settings settings = TestSupport.settings();
        assertFalse(settings.allowExecution());
        assertFalse(settings.proxy().enabled());
        assertEquals(5, settings.historyRounds());
        assertTrue(settings.memory().enabled());
        assertTrue(settings.memory().distillation());
        assertEquals(6, settings.memory().recallLimit());
    }

    @Test
    void honorsModelAndStripsLegacyKeyQuery() {
        var config = TestSupport.config();
        config.set("panda.model_selection.gemini.model", "custom-model");
        config.set("panda.model_selection.gemini.api_url", "https://example.com/models/%s:generateContent?key=%s");
        Settings settings = Settings.load(config);
        assertEquals("custom-model", settings.model());
        assertFalse(settings.apiUrl().contains("?"));
    }

    @Test
    void rejectsInvalidConfigurationBeforeCreatingService() {
        for (String path : new String[] {
            "panda.requests.workers",
            "panda.requests.queue_size",
            "panda.requests.timeout_seconds",
            "panda.memory.recall_limit",
            "panda.memory.context_chars"
        }) {
            var config = TestSupport.config();
            config.set(path, 0);
            assertThrows(IllegalArgumentException.class, () -> Settings.load(config));
        }
        var config = TestSupport.config();
        config.set("panda.command_awareness.executor", "console");
        assertThrows(IllegalArgumentException.class, () -> Settings.load(config));
    }

    @Test
    void secretsNeverAppearInSettingsString() {
        var config = TestSupport.config();
        config.set("panda.model_selection.gemini.api_key", "test-secret");
        assertFalse(Settings.load(config).toString().contains("test-secret"));
    }

    @Test
    void validatesRuntimeLimitsAndCanDisableFollowUps() {
        for (String field : new String[] {"follow_up_min_seconds", "max_follow_ups"}) {
            for (Object value : new Object[] {-1, 1.5, "invalid", 2001}) {
                var config = TestSupport.config();
                config.set("panda.agent.runtime." + field, value);
                assertThrows(IllegalArgumentException.class, () -> Settings.load(config));
            }
        }
        var config = TestSupport.config();
        config.set("panda.agent.runtime.max_follow_ups", 0);
        assertEquals(0, Settings.load(config).agent().runtime().maxFollowUps());
        assertTrue(Settings.load(config).agent().runtime().enabled());
    }
}
