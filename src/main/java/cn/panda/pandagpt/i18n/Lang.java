package cn.panda.pandagpt.i18n;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

/** 面向玩家与控制台发送本地化消息的便捷入口。线程：只能在服务器主线程向玩家发送消息。 */
public final class Lang {
    /** 消息前缀，对应语言文件中的 {@code prefix.*} 键。 */
    public enum Prefix {
        NONE(null),
        PANDA("prefix.panda"),
        VISION("prefix.vision"),
        PERCEPTION("prefix.perception"),
        SUGGESTION("prefix.suggestion"),
        REMINDER("prefix.reminder");

        private final String key;

        Prefix(String key) {
            this.key = key;
        }
    }

    /** 以接收者语言渲染文本。 */
    public static String text(CommandSender sender, Msg message) {
        Messages messages = Messages.active();
        return messages.format(messages.language(sender), message);
    }

    public static String text(CommandSender sender, String key, Object... args) {
        return text(sender, Msg.of(key, args));
    }

    /** 以服务器默认语言渲染文本，用于控制台日志。 */
    public static String console(String key, Object... args) {
        return Msg.of(key, args).toString();
    }

    public static Component component(CommandSender sender, Prefix prefix, NamedTextColor color, Msg message) {
        String body = text(sender, message);
        String head = prefix.key == null ? "" : text(sender, prefix.key) + " ";
        return Component.text(head + body, color);
    }

    public static void send(CommandSender sender, Prefix prefix, NamedTextColor color, Msg message) {
        sender.sendMessage(component(sender, prefix, color, message));
    }

    public static void send(CommandSender sender, Prefix prefix, NamedTextColor color, String key, Object... args) {
        send(sender, prefix, color, Msg.of(key, args));
    }

    private Lang() {}
}
