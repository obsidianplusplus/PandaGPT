package cn.panda.pandagpt.actions;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 校验完整命令文本；参数中的选择器、负号与小数原样保留。 */
public final class CommandPolicy {
    // 管理与包装类命令无论配置如何都拒绝。
    private static final Set<String> ALWAYS_BLOCKED =
            Set.of("op", "deop", "stop", "restart", "reload", "execute", "sudo", "panda", "pandaadmin");
    private static final Pattern ROOT = Pattern.compile("[a-z0-9_-]+");

    /**
     * 返回规范化后的命令（去掉开头的斜杠，根名称转为小写）。
     *
     * @throws IllegalArgumentException 语法非法、根名称不在允许列表或位于拒绝列表
     */
    public static String validate(String raw, Set<String> allowed, Set<String> blocked) {
        if (raw == null) {
            throw new IllegalArgumentException("Empty command");
        }
        String command = raw.strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (command.isBlank()
                || command.length() > 512
                || command.chars().anyMatch(Character::isISOControl)
                || command.contains("§")
                || command.contains(";")
                || command.contains("|")) {
            throw new IllegalArgumentException("Invalid command syntax");
        }
        int space = command.indexOf(' ');
        String root = (space < 0 ? command : command.substring(0, space)).toLowerCase(Locale.ROOT);
        if (!ROOT.matcher(root).matches()
                || !allowed.contains(root)
                || blocked.contains(root)
                || ALWAYS_BLOCKED.contains(root)) {
            throw new IllegalArgumentException("Command root is not allowed");
        }
        return root + (space < 0 ? "" : command.substring(space));
    }

    private CommandPolicy() {}
}
