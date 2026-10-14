package cn.panda.pandagpt.perception;

import cn.panda.pandagpt.i18n.LocalizedException;
import org.bukkit.configuration.file.FileConfiguration;

/** 感知与主动辅助配置快照。 */
public record PerceptionSettings(
        boolean enabled,
        int observeSeconds,
        int sceneTtlSeconds,
        int entityRadius,
        int maxEntities,
        int maxPlayers,
        boolean proactive,
        int suggestionSeconds,
        int routinePerHalfHour) {

    public static PerceptionSettings load(FileConfiguration c) {
        return new PerceptionSettings(
                c.getBoolean("panda.perception.enabled", true),
                n(c, "observe_seconds", 30, 10, 300),
                n(c, "scene_ttl_seconds", 60, 10, 180),
                n(c, "entity_radius", 20, 4, 32),
                n(c, "max_entities", 16, 1, 32),
                n(c, "max_players", 128, 1, 512),
                c.getBoolean("panda.perception.proactive", true),
                n(c, "suggestion_seconds", 900, 60, 3600),
                n(c, "routine_per_half_hour", 2, 0, 6));
    }

    private static int n(FileConfiguration c, String name, int fallback, int min, int max) {
        String path = "panda.perception." + name;
        Object raw = c.get(path);
        if (raw != null && !(raw instanceof Number)) {
            throw new LocalizedException("config.not_number", path);
        }
        double value = c.getDouble(path, fallback);
        if (!Double.isFinite(value) || value != Math.rint(value) || value < min || value > max) {
            throw new LocalizedException("config.out_of_range", path, min, max);
        }
        return (int) value;
    }
}
