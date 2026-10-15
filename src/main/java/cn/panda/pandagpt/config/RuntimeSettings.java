package cn.panda.pandagpt.config;

import cn.panda.pandagpt.i18n.LocalizedException;
import org.bukkit.configuration.file.FileConfiguration;

/** Limits for model-directed, session-local plans and follow-ups. */
public record RuntimeSettings(boolean enabled, int followUpMinSeconds, int maxFollowUps) {
    public static RuntimeSettings defaults() {
        return new RuntimeSettings(true, 60, 3);
    }

    public static RuntimeSettings load(FileConfiguration config) {
        return new RuntimeSettings(
                config.getBoolean("panda.agent.runtime.enabled", true),
                number(config, "follow_up_min_seconds", 60, 30, 1800),
                number(config, "max_follow_ups", 3, 0, 6));
    }

    private static int number(FileConfiguration config, String key, int fallback, int min, int max) {
        String path = "panda.agent.runtime." + key;
        Object raw = config.get(path);
        if (raw != null && !(raw instanceof Number)) throw new LocalizedException("config.not_number", path);
        double value = config.getDouble(path, fallback);
        if (!Double.isFinite(value) || value != Math.rint(value) || value < min || value > max) {
            throw new LocalizedException("config.out_of_range", path, min, max);
        }
        return (int) value;
    }
}
