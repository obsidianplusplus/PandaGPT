package cn.panda.pandagpt.awareness;

import cn.panda.pandagpt.config.Settings;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** 描述玩家有权使用的插件命令，供模型参考；从不从模型文本中提取斜杠命令。线程：服务器主线程。 */
public final class CommandAwareness {
    public static String context(Player player, Settings settings) {
        if (!settings.commandAwareness()) return "";
        StringBuilder out = new StringBuilder("Server: ")
                .append(player.getServer().getVersion())
                .append("\nAvailable plugin commands:\n");
        Set<String> seen = new java.util.HashSet<>();
        for (Plugin plugin : player.getServer().getPluginManager().getPlugins()) {
            if (!plugin.isEnabled()) continue;
            plugin.getDescription().getCommands().keySet().stream().sorted().forEach(name -> {
                var command = player.getServer().getPluginCommand(name);
                if (command != null && command.testPermissionSilent(player) && seen.size() < 40 && seen.add(name))
                    out.append('/')
                            .append(name)
                            .append(": ")
                            .append(command.getDescription())
                            .append('\n');
            });
        }
        out.append("Proposed commands must use one of these exact roots: ")
                .append(settings.allowedCommands())
                .append('\n');
        return out.toString();
    }
}
