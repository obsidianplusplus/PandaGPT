package cn.panda.pandagpt.config;

import cn.panda.pandagpt.i18n.LocalizedException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Agent 配置。
 *
 * @param maxSteps 每个回合最多请求模型的次数
 * @param maxToolCalls 每个回合最多执行的工具调用数
 * @param turnTimeoutSeconds 每个回合的总时长上限（含全部模型请求与工具执行）
 * @param maxToolResultChars 单个工具结果交给模型前的截断长度
 * @param disabledSkills 服主关闭的技能
 * @param proactive 主动建议是否由 Agent 生成
 * @param maxParallelReads 独立只读工具最多同时等待的数量；写入工具构成执行边界
 */
public record AgentSettings(
        int maxSteps,
        int maxToolCalls,
        int turnTimeoutSeconds,
        int maxToolResultChars,
        Set<String> disabledSkills,
        boolean proactive,
        int maxParallelReads) {

    public AgentSettings(
            int maxSteps,
            int maxToolCalls,
            int turnTimeoutSeconds,
            int maxToolResultChars,
            Set<String> disabledSkills,
            boolean proactive) {
        this(maxSteps, maxToolCalls, turnTimeoutSeconds, maxToolResultChars, disabledSkills, proactive, 3);
    }

    public static AgentSettings load(FileConfiguration c) {
        return new AgentSettings(
                number(c, "max_steps", 8, 1, 16),
                number(c, "max_tool_calls", 16, 1, 48),
                number(c, "turn_timeout_seconds", 120, 10, 600),
                number(c, "max_tool_result_chars", 6000, 500, 32000),
                c.getStringList("panda.agent.disabled_skills").stream()
                        .map(s -> s.strip().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet()),
                c.getBoolean("panda.agent.proactive", true),
                number(c, "max_parallel_reads", 3, 1, 8));
    }

    private static int number(FileConfiguration c, String name, int fallback, int min, int max) {
        String path = "panda.agent." + name;
        Object raw = c.get(path);
        if (raw != null && !(raw instanceof Number)) {
            throw new LocalizedException("config.not_number", path);
        }
        long value = c.getLong(path, fallback);
        if (value < min || value > max) {
            throw new LocalizedException("config.out_of_range", path, min, max);
        }
        return (int) value;
    }
}
