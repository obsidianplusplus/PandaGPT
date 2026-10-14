package cn.panda.pandagpt.features;

import cn.panda.pandagpt.perception.SceneObjects;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.bukkit.inventory.ItemStack;

/** 记录每位玩家最近一次死亡：位置、原因、时间与掉落物，供 Agent 回答“我死在哪了”。线程：仅限服务器主线程。 */
public final class DeathLog {
    /** 一次死亡记录。 */
    public record Death(
            UUID world,
            String worldName,
            SceneObjects.Pos position,
            String cause,
            long time,
            int levels,
            Map<String, Integer> drops) {}

    // 原版掉落物在地上存在 5 分钟。
    public static final long DESPAWN_MILLIS = 300_000;

    private final Map<UUID, Death> last = new HashMap<>();

    public void record(
            UUID player,
            UUID world,
            String worldName,
            SceneObjects.Pos position,
            String cause,
            long time,
            int levels,
            Iterable<ItemStack> drops) {
        Map<String, Integer> summary = new TreeMap<>();
        for (ItemStack item : drops) {
            if (item != null) {
                summary.merge(item.getType().name(), item.getAmount(), Integer::sum);
            }
        }
        last.put(player, new Death(world, worldName, position, cause, time, levels, Map.copyOf(summary)));
    }

    public Death last(UUID player) {
        return last.get(player);
    }
}
