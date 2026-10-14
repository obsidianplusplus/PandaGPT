package cn.panda.pandagpt.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

/** Decode only legacy styling; model text cannot supply click/run-command events. */
public final class FormatUtil {
    public static Component formatChatMessage(String message) {
        String clean = message.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        return LegacyComponentSerializer.legacySection().deserialize(clean);
    }
    private FormatUtil() {}
}
