package cn.panda.pandagpt;

import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.Collections;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigurationReloadTest {
    @TempDir Path folder;
    private ChatGPTIntegration plugin() throws Exception {
        ChatGPTIntegration plugin = mock(ChatGPTIntegration.class);
        Server server = mock(Server.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(server.recipeIterator()).thenAnswer(invocation -> Collections.emptyIterator());
        byte[] defaults;
        try (var resource = getClass().getResourceAsStream("/config.yml")) { defaults = resource.readAllBytes(); }
        when(plugin.getResource("config.yml")).thenAnswer(invocation -> new ByteArrayInputStream(defaults));
        doCallRealMethod().when(plugin).loadService();
        when(plugin.service()).thenCallRealMethod(); when(plugin.settings()).thenCallRealMethod();
        Files.writeString(folder.resolve("knowledge.md"), "test knowledge");
        TestSupport.config().save(folder.resolve("config.yml").toFile());
        return plugin;
    }
    @Test void invalidReloadPreservesTheRunningService() throws Exception {
        ChatGPTIntegration plugin = plugin(); plugin.loadService();
        try {
            var original = plugin.service();
            var invalid = TestSupport.config(); invalid.set("panda.requests.workers", 0);
            invalid.save(folder.resolve("config.yml").toFile());
            assertThrows(IllegalArgumentException.class, plugin::loadService);
            assertSame(original, plugin.service());
            assertEquals(4, plugin.settings().workers());
        } finally { plugin.service().close(); }
    }
    @Test void legacyMigrationBacksUpAndPreservesUserSettings() throws Exception {
        ChatGPTIntegration plugin = plugin();
        String legacy = "gemini:\n  api_key: test-key\npanda:\n  history_size: 5\n  model_selection:\n    gemini:\n      model: custom-model\n";
        Files.writeString(folder.resolve("config.yml"), legacy);
        plugin.loadService();
        try {
            assertEquals(legacy, Files.readString(folder.resolve("config-v1-backup.yml")));
            assertEquals(2, plugin.settings().historyRounds());
            assertEquals("custom-model", plugin.settings().model());
            assertFalse(plugin.settings().allowExecution());
            assertEquals(2, YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile()).getInt("config_version"));
        } finally { plugin.service().close(); }
    }
}
