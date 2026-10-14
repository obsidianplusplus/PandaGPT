package cn.panda.pandagpt.config;

import cn.panda.pandagpt.i18n.LocalizedException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * 内置创意功能与外部插件集成的配置。
 *
 * @param previewMaxBlocks 一次建筑预览最多显示的方块数
 * @param previewSeconds 预览自动消失前的秒数
 * @param guidanceSeconds 粒子导航的最长持续时间
 * @param maxReminders 每位玩家同时存在的提醒上限
 * @param mapUrl 在线地图链接模板，支持 {world} {x} {y} {z}；为空则不提供地图链接
 * @param disabledIntegrations 不启用的集成编号
 * @param placeholderDenylist 包含这些片段的 PlaceholderAPI 变量一律拒绝解析（防止泄露 IP 等隐私）
 * @param blockHistoryDays CoreProtect 方块记录的查询天数上限
 */
public record FeatureSettings(
        int previewMaxBlocks,
        int previewSeconds,
        int guidanceSeconds,
        int maxReminders,
        String mapUrl,
        Set<String> disabledIntegrations,
        List<String> placeholderDenylist,
        int blockHistoryDays) {

    public static FeatureSettings load(FileConfiguration c) {
        String mapUrl = c.getString("panda.features.map_url", "").strip();
        if (!mapUrl.isEmpty() && !mapUrl.startsWith("https://") && !mapUrl.startsWith("http://")) {
            throw new LocalizedException("config.map_url_invalid");
        }
        List<String> denylist = c.contains("panda.integrations.placeholder_denylist")
                ? c.getStringList("panda.integrations.placeholder_denylist")
                : List.of("ip", "address", "password", "token", "email", "uuid");
        return new FeatureSettings(
                number(c, "panda.features.preview_max_blocks", 2048, 1, 8192),
                number(c, "panda.features.preview_seconds", 180, 10, 1800),
                number(c, "panda.features.guidance_seconds", 600, 30, 3600),
                number(c, "panda.features.max_reminders", 10, 0, 50),
                mapUrl,
                c.getStringList("panda.integrations.disabled").stream()
                        .map(s -> s.strip().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet()),
                denylist.stream()
                        .map(s -> s.strip().toLowerCase(Locale.ROOT))
                        .filter(s -> !s.isEmpty())
                        .toList(),
                number(c, "panda.integrations.block_history_days", 7, 1, 90));
    }

    private static int number(FileConfiguration c, String path, int fallback, int min, int max) {
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
