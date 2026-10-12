package cn.panda.pandagpt.actions;

import cn.panda.pandagpt.ai.AiResponse;
import cn.panda.pandagpt.config.Settings;
import cn.panda.pandagpt.util.PandaLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;

/** All methods are invoked on the server thread. Pending actions are bound to one player and one use. */
public final class ActionController {
    private record Pending(String code, long expires, List<String> commands) {}
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Settings settings;
    private final PandaLogger logger;
    private final Clock clock;
    public ActionController(Settings settings, PandaLogger logger) { this(settings, logger, Clock.systemUTC()); }
    public ActionController(Settings settings, PandaLogger logger, Clock clock) {
        this.settings = settings; this.logger = logger; this.clock = clock;
    }
    public void propose(Player player, List<AiResponse.Action> actions) {
        pending.remove(player.getUniqueId());
        if (actions.isEmpty()) return;
        if (!settings.commandAwareness() || !settings.allowExecution() || !player.hasPermission("pandagpt.actions")) {
            player.sendMessage(Component.text("[Panda] 动作执行未开启或你没有权限。", NamedTextColor.YELLOW)); return;
        }
        if (actions.size() > settings.maxActions()) {
            player.sendMessage(Component.text("[Panda] 动作数量超过限制。", NamedTextColor.RED)); return;
        }
        List<String> commands = new ArrayList<>();
        try {
            for (AiResponse.Action action : actions) {
                String command = CommandPolicy.validate(action.command(), settings.allowedCommands(), settings.blockedCommands());
                if (!permitted(player, command)) throw new IllegalArgumentException("Player permission denied");
                commands.add(command);
            }
        } catch (IllegalArgumentException e) {
            player.sendMessage(Component.text("[Panda] 动作被拒绝：命令不在允许列表中或权限不足。", NamedTextColor.RED));
            logger.event(player.getUniqueId(), "action_rejected"); return;
        }
        String code = HexFormat.of().formatHex(random.generateSeed(8));
        pending.put(player.getUniqueId(), new Pending(code, clock.millis() + settings.confirmationTtl().toMillis(), List.copyOf(commands)));
        player.sendMessage(Component.text("[Panda] 以下为待确认动作，尚未执行：", NamedTextColor.YELLOW));
        for (int i = 0; i < commands.size(); i++) {
            player.sendMessage(Component.text("/" + commands.get(i), NamedTextColor.AQUA));
            String reason = actions.get(i).reason();
            if (!reason.isBlank()) player.sendMessage(Component.text("原因：" + reason, NamedTextColor.GRAY));
        }
        player.sendMessage(Component.text("[点击确认执行]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/panda confirm " + code))
                .append(Component.text(" 或 /panda cancel；" + settings.confirmationTtl().toSeconds() + " 秒内有效。", NamedTextColor.GRAY)));
        logger.event(player.getUniqueId(), "action_proposed count=" + commands.size());
    }
    public void confirm(Player player, String code) {
        Pending action = pending.get(player.getUniqueId());
        if (action == null || !action.code.equals(code) || clock.millis() >= action.expires) {
            if (action != null && clock.millis() >= action.expires) pending.remove(player.getUniqueId());
            player.sendMessage(Component.text("[Panda] 确认码无效或已过期。", NamedTextColor.YELLOW)); return;
        }
        pending.remove(player.getUniqueId()); // Consume before dispatch; exceptions cannot permit replay.
        if (!settings.commandAwareness() || !settings.allowExecution() || !player.isOnline()
                || !player.hasPermission("pandagpt.use") || !player.hasPermission("pandagpt.actions")) return;
        for (String command : action.commands) {
            try {
                CommandPolicy.validate(command, settings.allowedCommands(), settings.blockedCommands());
                if (!permitted(player, command)) {
                    player.sendMessage(Component.text("[Panda] 权限已变化，动作停止。", NamedTextColor.RED)); break;
                }
                boolean accepted = player.performCommand(command);
                // Bukkit true only means dispatch accepted; it is not proof the gameplay action succeeded.
                player.sendMessage(Component.text(accepted ? "[Panda] 命令已提交，请查看命令反馈。" : "[Panda] 命令未被接受。",
                        accepted ? NamedTextColor.GREEN : NamedTextColor.RED));
                logger.event(player.getUniqueId(), "action_dispatch accepted=" + accepted);
                if (!accepted) break;
            } catch (RuntimeException e) {
                logger.event(player.getUniqueId(), "action_dispatch_error");
                player.sendMessage(Component.text("[Panda] 命令执行发生错误，后续动作已停止。", NamedTextColor.RED)); break;
            }
        }
    }
    private boolean permitted(Player player, String command) {
        String root = command.split(" ", 2)[0];
        var pluginCommand = player.getServer().getPluginCommand(root);
        return pluginCommand == null || pluginCommand.testPermissionSilent(player);
        // Vanilla and other registered commands also enforce their own permissions at player dispatch.
    }
    public void cancel(UUID player) { pending.remove(player); }
    public void clear() { pending.clear(); }
    public void expire() { long now = clock.millis(); pending.values().removeIf(p -> now >= p.expires); }
}
