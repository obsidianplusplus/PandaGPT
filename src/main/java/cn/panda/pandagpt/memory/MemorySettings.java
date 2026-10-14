package cn.panda.pandagpt.memory;

import cn.panda.pandagpt.i18n.LocalizedException;
import org.bukkit.configuration.file.FileConfiguration;

/** 游戏记忆与蒸馏的独立开关；历史事件和检索都有固定上限。 */
public record MemorySettings(
        boolean enabled, boolean distillation, int recallLimit, int contextChars, int distillOutputTokens) {
    public MemorySettings(boolean enabled, boolean distillation, int recallLimit, int contextChars) {
        this(enabled, distillation, recallLimit, contextChars, 4096);
    }

    public static MemorySettings defaults() {
        return new MemorySettings(true, true, 6, 1800);
    }

    public static MemorySettings load(FileConfiguration config) {
        return new MemorySettings(
                config.getBoolean("panda.memory.enabled", true),
                config.getBoolean("panda.memory.distillation", true),
                number(config, "panda.memory.recall_limit", 6, 1, 12),
                number(config, "panda.memory.context_chars", 1800, 256, 4000),
                number(config, "panda.memory.distill_output_tokens", 4096, 512, 8192));
    }

    private static int number(FileConfiguration config, String path, int fallback, int min, int max) {
        Object raw = config.get(path);
        if (raw != null && !(raw instanceof Number)) {
            throw new LocalizedException("config.not_number", path);
        }
        long value = config.getLong(path, fallback);
        if (value < min || value > max) {
            throw new LocalizedException("config.out_of_range", path, min, max);
        }
        return (int) value;
    }
}
