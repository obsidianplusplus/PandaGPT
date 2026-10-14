package cn.panda.pandagpt.vision;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

/** 把已计算出的命中点以私有粒子显示给玩家；不是客户端粒子传感器，也没有碰撞回传。 */
public final class ParticleFeedback {
    public static void show(Player player, RaySample sample) {
        if (sample.kind() != RaySample.Kind.HIT) return;
        var point = sample.hitPosition();
        player.spawnParticle(
                Particle.DUST,
                point.x(),
                point.y(),
                point.z(),
                1,
                0,
                0,
                0,
                0,
                new Particle.DustOptions(Color.fromRGB(sample.rgb()), .65f));
    }

    private ParticleFeedback() {}
}
