package cn.panda.pandagpt.actions;

import java.util.Locale;
import java.util.Set;

/** Validates the complete command, retaining selectors, signs and decimals in arguments. */
public final class CommandPolicy {
    private static final Set<String> ALWAYS_BLOCKED = Set.of("op", "deop", "stop", "restart", "reload", "execute", "sudo", "panda", "pandaadmin");
    public static String validate(String raw, Set<String> allowed, Set<String> blocked) {
        if (raw == null) throw new IllegalArgumentException("Empty command");
        String command = raw.strip();
        if (command.startsWith("/")) command = command.substring(1);
        if (command.isBlank() || command.length() > 512 || command.chars().anyMatch(Character::isISOControl)
                || command.contains("§") || command.contains(";") || command.contains("|"))
            throw new IllegalArgumentException("Invalid command syntax");
        String root = command.split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (!root.matches("[a-z0-9_-]+") || !allowed.contains(root) || blocked.contains(root) || ALWAYS_BLOCKED.contains(root))
            throw new IllegalArgumentException("Command root is not allowed");
        return root + command.substring(command.indexOf(' ') < 0 ? command.length() : command.indexOf(' '));
    }
    private CommandPolicy() {}
}
