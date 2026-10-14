package cn.panda.pandagpt.features;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

/** 玩家统计：生涯总计与本次上线以来的变化。线程：仅限服务器主线程。 */
public final class SessionStats {
    /** 汇报给 Agent 的常用无类型统计项。 */
    public static final List<Statistic> TRACKED = List.of(
            Statistic.PLAY_ONE_MINUTE,
            Statistic.DEATHS,
            Statistic.MOB_KILLS,
            Statistic.PLAYER_KILLS,
            Statistic.WALK_ONE_CM,
            Statistic.SPRINT_ONE_CM,
            Statistic.FLY_ONE_CM,
            Statistic.SWIM_ONE_CM,
            Statistic.JUMP,
            Statistic.DAMAGE_DEALT,
            Statistic.DAMAGE_TAKEN,
            Statistic.FISH_CAUGHT,
            Statistic.ANIMALS_BRED,
            Statistic.TRADED_WITH_VILLAGER,
            Statistic.SLEEP_IN_BED);

    /** 常见矿物，用于汇报挖掘数量。 */
    public static final List<Material> ORES = List.of(
            Material.COAL_ORE,
            Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE,
            Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE,
            Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE,
            Material.DEEPSLATE_GOLD_ORE,
            Material.REDSTONE_ORE,
            Material.DEEPSLATE_REDSTONE_ORE,
            Material.LAPIS_ORE,
            Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE,
            Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE,
            Material.DEEPSLATE_EMERALD_ORE,
            Material.NETHER_QUARTZ_ORE,
            Material.NETHER_GOLD_ORE,
            Material.ANCIENT_DEBRIS);

    private final Map<UUID, Map<String, Integer>> baseline = new HashMap<>();

    /** 玩家上线时记录基准值。 */
    public void snapshot(Player player) {
        baseline.put(player.getUniqueId(), read(player));
    }

    /** 当前值与本次上线以来的增量。 */
    public Map<String, int[]> report(Player player) {
        Map<String, Integer> now = read(player);
        Map<String, Integer> start = baseline.getOrDefault(player.getUniqueId(), now);
        Map<String, int[]> out = new LinkedHashMap<>();
        now.forEach((key, value) -> out.put(key, new int[] {value, value - start.getOrDefault(key, value)}));
        return out;
    }

    private static Map<String, Integer> read(Player player) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (Statistic statistic : TRACKED) {
            values.put(statistic.name(), player.getStatistic(statistic));
        }
        Map<Material, Integer> ores = new EnumMap<>(Material.class);
        for (Material ore : ORES) {
            int mined = player.getStatistic(Statistic.MINE_BLOCK, ore);
            if (mined > 0) {
                ores.put(ore, mined);
            }
        }
        ores.forEach((ore, count) -> values.put("MINED_" + ore.name(), count));
        return values;
    }

    public void forget(UUID player) {
        baseline.remove(player);
    }
}
