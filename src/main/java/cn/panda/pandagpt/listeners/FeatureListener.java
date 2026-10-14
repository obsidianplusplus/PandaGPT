package cn.panda.pandagpt.listeners;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.i18n.Lang;
import cn.panda.pandagpt.i18n.Msg;
import cn.panda.pandagpt.memory.GameMemory;
import cn.panda.pandagpt.perception.SceneObjects;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
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
        var death = features.deaths().last(player.getUniqueId());
        remember(
                player,
                "死亡 death",
                "死亡 death: " + cause + "; " + player.getWorld().getName() + " " + pos
                        + "; drops at death (historical): " + death.drops(),
                List.of("死亡", "掉落", "death", "drops"),
                pos);
        if (plugin.perception() != null) {
            plugin.perception()
                    .died(player, pos, Msg.of("suggest.death", pos, e.getDrops().size()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void advancement(PlayerAdvancementDoneEvent e) {
        var display = e.getAdvancement().getDisplay();
        if (display == null || e.getAdvancement().getKey().getKey().startsWith("recipes/")) {
            return;
        }
        String title = PlainTextComponentSerializer.plainText().serialize(display.title());
        remember(
                e.getPlayer(),
                "成就 advancement",
                "完成成就 Completed advancement: " + title + " ("
                        + e.getAdvancement().getKey() + ")",
                List.of("成就", "advancement", "完成"),
                null);
    }

    private void remember(Player player, String topic, String text, List<String> cues, SceneObjects.Pos position) {
        if (plugin.memory() == null || !plugin.memory().settings().enabled() || !player.hasPermission("pandagpt.use")) {
            return;
        }
        long time = plugin.features() == null
                ? System.currentTimeMillis()
                : plugin.features().clock().millis();
        var event = new GameMemory.Event(
                UUID.randomUUID(),
                GameMemory.Kind.EPISODE,
                topic,
                text.substring(0, Math.min(GameMemory.MAX_TEXT, text.length())),
                cues,
                GameMemory.Source.SERVER,
                player.getWorld().getUID(),
                position,
                time,
                0,
                GameMemory.Status.ACTIVE);
        UUID playerId = player.getUniqueId();
        plugin.memory().rememberGame(playerId, event).exceptionally(error -> {
            plugin.getLogger().warning(Lang.console("log.memory_save_failed", playerId, "GameEvent"));
            return null;
        });
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
