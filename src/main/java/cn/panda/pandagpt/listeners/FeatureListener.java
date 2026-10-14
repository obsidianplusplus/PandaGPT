package cn.panda.pandagpt.listeners;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.i18n.Msg;
import cn.panda.pandagpt.perception.SceneObjects;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** 内置功能需要的玩家事件：死亡记录、上线统计基准与下线清理。线程：服务器主线程。 */
public final class FeatureListener implements Listener {
    private final ChatGPTIntegration plugin;

    public FeatureListener(ChatGPTIntegration plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void death(PlayerDeathEvent e) {
        var features = plugin.features();
        if (features == null) {
            return;
        }
        var player = e.getEntity();
        var location = player.getLocation();
        var pos = new SceneObjects.Pos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        String cause = e.deathMessage() == null
                ? "unknown"
                : PlainTextComponentSerializer.plainText().serialize(e.deathMessage());
        features.deaths()
                .record(
                        player.getUniqueId(),
                        player.getWorld().getUID(),
                        player.getWorld().getName(),
                        pos,
                        cause,
                        features.clock().millis(),
                        player.getLevel(),
                        e.getKeepInventory() ? java.util.List.of() : e.getDrops());
        if (plugin.perception() != null) {
            plugin.perception()
                    .died(player, pos, Msg.of("suggest.death", pos, e.getDrops().size()));
        }
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        if (plugin.features() != null) {
            plugin.features().stats().snapshot(e.getPlayer());
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        if (plugin.features() != null) {
            plugin.features().quit(e.getPlayer());
        }
    }
}
