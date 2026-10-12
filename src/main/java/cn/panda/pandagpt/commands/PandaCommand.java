package cn.panda.pandagpt.commands;

import cn.panda.pandagpt.ChatGPTIntegration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class PandaCommand implements CommandExecutor, TabCompleter {
    private final ChatGPTIntegration plugin;
    public PandaCommand(ChatGPTIntegration plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("pandaadmin")) {
            if (!sender.hasPermission("pandagpt.admin")) return true;
            String action = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
            switch (action) {
                case "status" -> sender.sendMessage(Component.text(plugin.service().status()));
                case "clearchat" -> { plugin.service().clearAll(); sender.sendMessage(Component.text("Panda 会话和待确认动作已清除。")); }
                case "reload" -> {
                    try { plugin.loadService(); sender.sendMessage(Component.text("Panda 配置已重载。", NamedTextColor.GREEN)); }
                    catch (Exception e) { sender.sendMessage(Component.text("重载失败，仍使用原配置：" + ChatGPTIntegration.safeConfigurationError(e), NamedTextColor.RED)); }
                }
                default -> sender.sendMessage(Component.text("/pandaadmin <reload|status|clearchat>"));
            }
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("提问仅限玩家；控制台可使用 /pandaadmin。")); return true;
        }
        if (!player.hasPermission("pandagpt.use")) return true;
        if (args.length == 0) { player.sendMessage(Component.text("/panda <问题|clear|confirm 确认码|cancel>")); return true; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "clear" -> {
                if (args.length != 1) break;
                plugin.service().clear(player.getUniqueId());
                player.sendMessage(Component.text("对话、当前请求和待确认动作已清除。")); return true;
            }
            case "cancel" -> {
                if (args.length != 1) break;
                plugin.service().cancelActions(player.getUniqueId());
                player.sendMessage(Component.text("待确认动作已取消。")); return true;
            }
            case "confirm" -> {
                if (args.length == 2) plugin.service().confirm(player, args[1]);
                else player.sendMessage(Component.text("/panda confirm <确认码>"));
                return true;
            }
            case "ask" -> {
                plugin.service().ask(player, String.join(" ", Arrays.copyOfRange(args, 1, args.length))); return true;
            }
            default -> {}
        }
        plugin.service().ask(player, String.join(" ", args));
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        List<String> options = command.getName().equalsIgnoreCase("pandaadmin")
                ? (sender.hasPermission("pandagpt.admin") ? List.of("reload", "status", "clearchat") : List.of())
                : List.of("ask", "clear", "confirm", "cancel");
        return options.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
}
