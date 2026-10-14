package cn.panda.pandagpt.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** 文本格式化工具。 */
public final class FormatUtil {
    /** 只解码传统颜色代码；模型文本无法携带点击或执行命令事件。 */
    public static Component formatChatMessage(String message) {
        String clean = message.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        return LegacyComponentSerializer.legacySection().deserialize(clean);
    }

    /** 截断到最多 max 个 UTF-16 单元，不会把代理对（如 emoji）切成两半。 */
    public static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int end = Math.max(0, max);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private FormatUtil() {}
}
