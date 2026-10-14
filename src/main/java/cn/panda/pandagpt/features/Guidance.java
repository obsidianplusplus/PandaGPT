package cn.panda.pandagpt.features;

import cn.panda.pandagpt.i18n.Lang;
import cn.panda.pandagpt.i18n.Lang.Prefix;
import cn.panda.pandagpt.i18n.Msg;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

/**
 * 粒子导航：在动作栏显示目标的方向箭头与距离，并在玩家前方画出一段只有他自己看得到的粒子路线。
 *
 * <p>到达（4 格内）、超时、切换世界或玩家下线时自动结束。线程：仅限服务器主线程。
 */
public final class Guidance {
    /** 一个导航目标。 */
    public record Target(String label, UUID world, double x, double y, double z, long until) {}

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static final double ARRIVAL = 4;

    private final Map<UUID, Target> targets = new HashMap<>();

    public void start(Player player, Target target) {
        targets.put(player.getUniqueId(), target);
    }

    public boolean stop(UUID player) {
        return targets.remove(player) != null;
    }

    public Target target(UUID player) {
        return targets.get(player);
    }

    /** 每 10 tick 调用一次。 */
    public void tick(Function<UUID, Player> players, long now) {
        for (UUID id : List.copyOf(targets.keySet())) {
            Target target = targets.get(id);
            Player player = players.apply(id);
            if (player == null || !player.isOnline() || now > target.until()) {
                targets.remove(id);
                continue;
            }
            if (!player.getWorld().getUID().equals(target.world())) {
                targets.remove(id);
                Lang.send(player, Prefix.PANDA, NamedTextColor.GRAY, "guide.world_changed", target.label());
                continue;
            }
            var l = player.getLocation();
            double dx = target.x() - l.getX();
            double dz = target.z() - l.getZ();
            double dy = target.y() - l.getY();
            double distance = Math.hypot(dx, dz);
            if (distance < ARRIVAL && Math.abs(dy) < 6) {
                targets.remove(id);
                Lang.send(player, Prefix.PANDA, NamedTextColor.GREEN, "guide.arrived", target.label());
                continue;
            }
            // 目标方位与玩家朝向之差，换算成 8 个方向的箭头。
            double bearing = Math.toDegrees(Math.atan2(-dx, dz));
            double relative = ((bearing - l.getYaw()) % 360 + 540) % 360 - 180;
            String arrow = ARROWS[(int) Math.floorMod(Math.round(relative / 45.0), 8)];
            player.sendActionBar(Lang.component(
                    player,
                    Prefix.NONE,
                    NamedTextColor.AQUA,
                    Msg.of("guide.bar", target.label(), arrow, Math.round(distance), Math.round(dy))));
            trail(player, dx / distance, dz / distance);
        }
    }

    // 沿目标方向在玩家前方画 6 个粒子点，只发给该玩家。
    private static void trail(Player player, double nx, double nz) {
        var eye = player.getEyeLocation();
        var dust = new Particle.DustOptions(Color.fromRGB(0x55FFFF), 1.1f);
        for (int i = 1; i <= 6; i++) {
            double step = 1.2 * i;
            player.spawnParticle(
                    Particle.DUST,
                    eye.getX() + nx * step,
                    eye.getY() - .6,
                    eye.getZ() + nz * step,
                    1,
                    0,
                    0,
                    0,
                    0,
                    dust);
        }
    }

    public void forget(UUID player) {
        targets.remove(player);
    }

    public int active() {
        return targets.size();
    }
}
