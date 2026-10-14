package cn.panda.pandagpt.commands;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.i18n.Lang;
import cn.panda.pandagpt.i18n.Lang.Prefix;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/**
 * 插件唯一的命令 /pandaadmin，供服主重载、查看状态与清理会话。
 *
 * <p>玩家没有任何命令，一切都通过在聊天中和 Panda 说话完成。线程：服务器主线程。
 */
public final class AdminCommand implements CommandExecutor, TabCompleter {
    private static final List<String> ACTIONS = List.of("reload", "status", "clearchat");

    private final ChatGPTIntegration plugin;

    public AdminCommand(ChatGPTIntegration plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("pandagpt.admin")) {
            return true;
        }
        String action = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "status" ->
                sender.sendMessage(Component.text(plugin.service().status() + "; "
                        + plugin.vision().status() + "; " + plugin.perception().status()));
            case "clearchat" -> {
                plugin.perception().clearTransient();
                plugin.vision().clearAll();
                plugin.service().clearAll();
                Lang.send(sender, Prefix.PANDA, NamedTextColor.WHITE, "admin.cleared");
            }
            case "reload" -> {
                try {
                    plugin.loadService();
                    Lang.send(sender, Prefix.PANDA, NamedTextColor.GREEN, "admin.reloaded");
                } catch (Exception e) {
                    Lang.send(
                            sender,
                            Prefix.PANDA,
                            NamedTextColor.RED,
                            "admin.reload_failed",
                            ChatGPTIntegration.describe(sender, e));
                }
            }
            default -> Lang.send(sender, Prefix.NONE, NamedTextColor.WHITE, "admin.usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !sender.hasPermission("pandagpt.admin")) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return ACTIONS.stream().filter(s -> s.startsWith(prefix)).toList();
    }
}
