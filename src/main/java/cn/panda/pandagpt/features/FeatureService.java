package cn.panda.pandagpt.features;

import cn.panda.pandagpt.config.FeatureSettings;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.entity.Player;

/**
 * 内置创意功能的容器：建筑预览、粒子导航、提醒、死亡记录与会话统计，并统一驱动它们的定时任务。
 *
 * <p>线程：仅限服务器主线程。
 */
public final class FeatureService implements AutoCloseable {
    private volatile FeatureSettings settings;
    private final Function<UUID, Player> players;
    private final Clock clock;
    private final GhostPreview preview = new GhostPreview();
    private final Guidance guidance = new Guidance();
    private final Reminders reminders = new Reminders();
    private final DeathLog deaths = new DeathLog();
    private final SessionStats stats = new SessionStats();
    private long ticks;

    public FeatureService(FeatureSettings settings, Function<UUID, Player> players, Clock clock) {
        this.settings = settings;
        this.players = players;
        this.clock = clock;
    }

    /** 每个服务器 tick 调用一次；各功能按自己的频率执行。 */
    public void tick() {
        ticks++;
        long now = clock.millis();
        if (ticks % 10 == 0) {
            guidance.tick(players, now);
        }
        if (ticks % 20 == 0) {
            reminders.tick(players, now);
            preview.tick(players, now);
        }
    }

    /** 重载配置时更新参数；提醒、死亡记录等状态保留。 */
    public void update(FeatureSettings next) {
        settings = next;
    }

    public FeatureSettings settings() {
        return settings;
    }

    public Clock clock() {
        return clock;
    }

    public GhostPreview preview() {
        return preview;
    }

    public Guidance guidance() {
        return guidance;
    }

    public Reminders reminders() {
        return reminders;
    }

    public DeathLog deaths() {
        return deaths;
    }

    public SessionStats stats() {
        return stats;
    }

    /** 玩家下线：恢复预览并释放状态；提醒保留到下次上线。 */
    public void quit(Player player) {
        preview.clear(player);
        guidance.forget(player.getUniqueId());
        stats.forget(player.getUniqueId());
    }

    public String status() {
        return "previews=" + preview.active() + ", guidance=" + guidance.active() + ", reminders=" + reminders.active();
    }

    /** 重载或停用时恢复所有在线玩家看到的预览方块。 */
    public void close(Iterable<? extends Player> online) {
        online.forEach(preview::clear);
    }

    @Override
    public void close() {
        // 无法获取在线玩家时，预览会在玩家重新加载区块后自然消失。
    }
}
