package cn.panda.pandagpt.listeners;

import cn.panda.pandagpt.ChatGPTIntegration;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;

/** 聊天触发与玩家进出服务器。聊天事件在异步线程触发，所有游戏状态访问都转回主线程。 */
public final class ChatListener implements Listener {
    private final ChatGPTIntegration plugin;

    public ChatListener(ChatGPTIntegration plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        var matcher = plugin.triggerPattern().matcher(text);
        if (!matcher.matches()) {
            return;
        }
        String question = matcher.group(1).strip();
        if (plugin.settings().cancelChat()) {
            event.setCancelled(true);
        }
        var player = event.getPlayer();
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled() && plugin.service() != null) {
                    plugin.ask(player, question);
                }
            });
        } catch (IllegalPluginAccessException ignored) {
            // 服务器正在关闭，丢弃该请求。
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (plugin.perception() != null) {
            plugin.perception().joined(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        var id = event.getPlayer().getUniqueId();
        if (plugin.vision() != null) {
            plugin.vision().cancel(id);
        }
        if (plugin.service() != null) {
            plugin.service().clear(id);
        }
        if (plugin.perception() != null) {
            plugin.perception().remove(event.getPlayer());
        }
    }
}
