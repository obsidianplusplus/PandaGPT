package cn.panda.pandagpt.actions;

import cn.panda.pandagpt.config.Settings;
import cn.panda.pandagpt.i18n.Lang;
import cn.panda.pandagpt.i18n.Lang.Prefix;
import cn.panda.pandagpt.util.PandaLogger;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * 待确认命令提案的生命周期管理。
 *
 * <p>每批提案绑定一名玩家、只能使用一次并带有效期。玩家可以点击聊天中的按钮，也可以直接告诉 Agent “执行吧/算了”；
 * 按钮使用服务器端点击回调，不依赖任何插件命令。线程：全部方法在服务器主线程调用。
 */
public final class ActionController {
    /** 创建点击按钮的事件；测试中可替换为不依赖服务器实现的版本。 */
    @FunctionalInterface
    public interface Buttons {
        ClickEvent<?> create(Consumer<Audience> onClick, Duration lifetime);

        /** Paper 提供的一次性点击回调。 */
        Buttons CALLBACK = (onClick, lifetime) -> ClickEvent.callback(
                onClick::accept,
                ClickCallback.Options.builder().uses(1).lifetime(lifetime).build());
    }

    private record Pending(String code, long expires, List<String> commands) {}

    private final Map<UUID, Pending> pending = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Settings settings;
    private final PandaLogger logger;
    private final Clock clock;
    private final Buttons buttons;

    public ActionController(Settings settings, PandaLogger logger) {
        this(settings, logger, Clock.systemUTC(), Buttons.CALLBACK);
    }

    public ActionController(Settings settings, PandaLogger logger, Clock clock) {
        this(settings, logger, clock, Buttons.CALLBACK);
    }

    public ActionController(Settings settings, PandaLogger logger, Clock clock, Buttons buttons) {
        this.settings = settings;
        this.logger = logger;
        this.clock = clock;
        this.buttons = buttons;
    }

    /** 该玩家能否收到命令提案。 */
    public boolean enabled(Player player) {
        return settings.commandAwareness() && settings.allowExecution() && player.hasPermission("pandagpt.actions");
    }

    /**
     * 校验一条命令是否可以提案。
     *
     * @return 规范化后的命令
     * @throws IllegalArgumentException 不在允许列表、被拒绝或玩家没有权限
     */
    public String validate(Player player, String command) {
        String normalized = CommandPolicy.validate(command, settings.allowedCommands(), settings.blockedCommands());
        if (!permitted(player, normalized)) {
            throw new IllegalArgumentException("Player permission denied");
        }
        return normalized;
    }

    /** 校验并展示提案；新提案会替换该玩家尚未确认的旧提案。 */
    public void propose(Player player, List<ProposedCommand> actions) {
        pending.remove(player.getUniqueId());
        if (actions.isEmpty()) {
            return;
        }
        if (!enabled(player)) {
            Lang.send(player, Prefix.PANDA, NamedTextColor.YELLOW, "action.disabled");
            return;
        }
        if (actions.size() > settings.maxActions()) {
            Lang.send(player, Prefix.PANDA, NamedTextColor.RED, "action.too_many");
            return;
        }
        List<String> commands = new ArrayList<>();
        try {
            for (ProposedCommand action : actions) {
                commands.add(validate(player, action.command()));
            }
        } catch (IllegalArgumentException e) {
            Lang.send(player, Prefix.PANDA, NamedTextColor.RED, "action.rejected");
            logger.event(player.getUniqueId(), "action_rejected");
            return;
        }
        // 使用 nextBytes 而不是 generateSeed：后者用于播种，可能较慢甚至阻塞主线程。
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        String code = HexFormat.of().formatHex(bytes);
        UUID owner = player.getUniqueId();
        Duration ttl = settings.confirmationTtl();
        pending.put(owner, new Pending(code, clock.millis() + ttl.toMillis(), List.copyOf(commands)));
        Lang.send(player, Prefix.PANDA, NamedTextColor.YELLOW, "action.pending_header");
        for (int i = 0; i < commands.size(); i++) {
            player.sendMessage(Component.text("/" + commands.get(i), NamedTextColor.AQUA));
            String reason = actions.get(i).reason();
            if (!reason.isBlank()) {
                Lang.send(player, Prefix.NONE, NamedTextColor.GRAY, "action.reason", reason);
            }
        }
        // 按钮只对提案的所有者生效，且只能点击一次。
        Component confirm = Component.text(Lang.text(player, "action.click_confirm"), NamedTextColor.GREEN)
                .clickEvent(buttons.create(
                        audience -> {
                            if (audience instanceof Player clicker
                                    && clicker.getUniqueId().equals(owner)) {
                                confirm(clicker, code);
                            }
                        },
                        ttl));
        Component cancel = Component.text(Lang.text(player, "action.click_cancel"), NamedTextColor.RED)
                .clickEvent(buttons.create(
                        audience -> {
                            if (audience instanceof Player clicker
                                    && clicker.getUniqueId().equals(owner)) {
                                cancelByPlayer(clicker, code);
                            }
                        },
                        ttl));
        player.sendMessage(confirm.append(Component.text(" "))
                .append(cancel)
                .append(Component.text(
                        " " + Lang.text(player, "action.confirm_hint", ttl.toSeconds()), NamedTextColor.GRAY)));
        logger.event(owner, "action_proposed count=" + commands.size());
    }

    /** 核对确认码后按顺序派发；任何一步被拒绝或出错都会停止剩余命令。 */
    public void confirm(Player player, String code) {
        Pending action = pending.get(player.getUniqueId());
        long now = clock.millis();
        if (action == null || !action.code.equals(code) || now >= action.expires) {
            if (action != null && now >= action.expires) {
                pending.remove(player.getUniqueId());
            }
            Lang.send(player, Prefix.PANDA, NamedTextColor.YELLOW, "action.code_invalid");
            return;
        }
        // 派发前先消费提案，即使后续抛出异常也无法重放。
        pending.remove(player.getUniqueId());
        if (!enabled(player) || !player.isOnline() || !player.hasPermission("pandagpt.use")) {
            return;
        }
        for (String command : action.commands) {
            try {
                CommandPolicy.validate(command, settings.allowedCommands(), settings.blockedCommands());
                if (!permitted(player, command)) {
                    Lang.send(player, Prefix.PANDA, NamedTextColor.RED, "action.permission_changed");
                    break;
                }
                boolean accepted = player.performCommand(command);
                // Bukkit 返回 true 只表示命令被接受派发，并不证明游戏动作成功。
                Lang.send(
                        player,
                        Prefix.PANDA,
                        accepted ? NamedTextColor.GREEN : NamedTextColor.RED,
                        accepted ? "action.dispatched" : "action.not_accepted");
                logger.event(player.getUniqueId(), "action_dispatch accepted=" + accepted);
                if (!accepted) {
                    break;
                }
            } catch (RuntimeException e) {
                logger.event(player.getUniqueId(), "action_dispatch_error");
                Lang.send(player, Prefix.PANDA, NamedTextColor.RED, "action.dispatch_error");
                break;
            }
        }
    }

    private void cancelByPlayer(Player player, String code) {
        Pending action = pending.get(player.getUniqueId());
        if (action != null && action.code.equals(code)) {
            pending.remove(player.getUniqueId());
            Lang.send(player, Prefix.PANDA, NamedTextColor.GRAY, "action.cancelled");
        }
    }

    // 插件命令在此预先检查权限；原版及其他已注册命令会在玩家派发时自行检查权限。
    private boolean permitted(Player player, String command) {
        String root = command.split(" ", 2)[0];
        var pluginCommand = player.getServer().getPluginCommand(root);
        return pluginCommand == null || pluginCommand.testPermissionSilent(player);
    }

    /** 玩家是否有尚未过期的待确认动作。 */
    public boolean hasPending(UUID player) {
        Pending action = pending.get(player);
        return action != null && clock.millis() < action.expires;
    }

    /** 尚未过期的待确认命令，供 Agent 在提示词中了解上下文。 */
    public List<String> pendingCommands(UUID player) {
        return hasPending(player) ? pending.get(player).commands : List.of();
    }

    /** 玩家用自然语言同意时确认动作，与点击确认按钮等价。 */
    public void confirmPending(Player player) {
        Pending action = pending.get(player.getUniqueId());
        if (action != null) {
            confirm(player, action.code);
        }
    }

    public void cancel(UUID player) {
        pending.remove(player);
    }

    public void clear() {
        pending.clear();
    }

    public void expire() {
        long now = clock.millis();
        pending.values().removeIf(p -> now >= p.expires);
    }
}
