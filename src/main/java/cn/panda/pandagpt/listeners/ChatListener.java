package cn.panda.pandagpt.listeners;

import cn.panda.pandagpt.ChatGPTIntegration;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public final class ChatListener implements Listener {
    private final ChatGPTIntegration plugin;
    public ChatListener(ChatGPTIntegration plugin) { this.plugin = plugin; }
    @EventHandler(ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        var matcher = plugin.triggerPattern().matcher(text);
        if (!matcher.matches()) return;
        String question = matcher.group(1).strip();
        if (plugin.settings().cancelChat()) event.setCancelled(true);
        // Read/modify world state and send responses only on the server thread.
        var player = event.getPlayer();
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled() && plugin.service() != null) plugin.ask(player, question);
            });
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { /* Server is stopping. */ }
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) { plugin.perception().joined(event.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        plugin.vision().cancel(event.getPlayer().getUniqueId());
        plugin.service().clear(event.getPlayer().getUniqueId());
        plugin.perception().remove(event.getPlayer());
    }
}
