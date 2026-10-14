package cn.panda.pandagpt;

import cn.panda.pandagpt.config.Settings;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;

public final class TestSupport {
    public static YamlConfiguration config() {
        try (var stream = TestSupport.class.getResourceAsStream("/config.yml");
                var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static Settings settings() {
        return Settings.load(config());
    }
}
