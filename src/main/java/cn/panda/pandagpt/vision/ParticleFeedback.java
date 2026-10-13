package cn.panda.pandagpt.vision;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

/** Visualization of a computed collision, never a client particle sensor or collision callback. */
public final class ParticleFeedback {
    public static void show(Player player, RaySample sample) {
        if (sample.kind() != RaySample.Kind.HIT) return;
        var point = sample.hitPosition();
        player.spawnParticle(Particle.DUST, point.x(), point.y(), point.z(), 1, 0, 0, 0, 0,
                new Particle.DustOptions(Color.fromRGB(sample.rgb()), .65f));
    }
    private ParticleFeedback() {}
}
