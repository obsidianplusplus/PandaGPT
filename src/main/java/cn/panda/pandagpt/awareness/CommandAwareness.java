package cn.panda.pandagpt.awareness;

import cn.panda.pandagpt.config.Settings;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.Comparator;
import java.util.Set;

/** Describes registered plugin commands; never extracts slash text from model prose. */
public final class CommandAwareness {
    public static String context(Player player, Settings settings) {
        if (!settings.commandAwareness()) return "";
        StringBuilder out = new StringBuilder("Server: ").append(player.getServer().getVersion()).append("\nAvailable plugin commands:\n");
        Set<String> seen = new java.util.HashSet<>();
        for (Plugin plugin : player.getServer().getPluginManager().getPlugins()) {
            if (!plugin.isEnabled()) continue;
            plugin.getDescription().getCommands().keySet().stream().sorted().forEach(name -> {
                var command = player.getServer().getPluginCommand(name);
                if (command != null && command.testPermissionSilent(player) && seen.size() < 40 && seen.add(name))
                    out.append('/').append(name).append(": ").append(command.getDescription()).append('\n');
            });
        }
        out.append("Proposed commands must use one of these exact roots: ").append(settings.allowedCommands()).append('\n');
        return out.toString();
    }
}
