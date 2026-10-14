package cn.panda.pandagpt.util;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;

/** 玩家与环境状态快照。线程：必须在服务器主线程调用；返回的文本不包含任何 Bukkit 活动对象。 */
public final class PlayerContext {
    private static final Gson GSON = new Gson();

    /** 每个回合都附带的精简状态，让 Agent 不调用工具也能回答简单问题。 */
    public static String brief(Player player) {
        Map<String, Object> context = new LinkedHashMap<>();
        Location location = player.getLocation();
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        context.put("name", player.getName());
        context.put("health", Math.round(player.getHealth() * 10) / 10.0);
        context.put("max_health", maxHealth == null ? 20 : maxHealth.getValue());
        context.put("food", player.getFoodLevel());
        context.put("gamemode", player.getGameMode().name());
        context.put("dimension", player.getWorld().getEnvironment().name());
        context.put("position", List.of(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
        context.put("facing_yaw", Math.round(location.getYaw()));
        context.put("main_hand", describe(player.getInventory().getItemInMainHand()));
        return GSON.toJson(context);
    }

    /** 完整状态：背包、装备、效果与等级。 */
    public static String status(Player player) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("brief", GSON.fromJson(brief(player), Map.class));
        context.put("saturation", player.getSaturation());
        context.put("level", player.getLevel());
        context.put("inventory", summarizeInventory(player.getInventory().getStorageContents()));
        List<String> equipment = new ArrayList<>();
        for (ItemStack item : player.getInventory().getArmorContents()) {
            appendItem(equipment, item);
        }
        appendItem(equipment, player.getInventory().getItemInOffHand());
        context.put("armor_and_offhand", equipment);
        context.put(
                "effects",
                player.getActivePotionEffects().stream()
                        .map(effect -> effect.getType().getKey() + " level=" + (effect.getAmplifier() + 1) + " seconds="
                                + effect.getDuration() / 20)
                        .toList());
        return GSON.toJson(context);
    }

    /** 世界环境：时间、天气、光照、生物群系。 */
    public static String conditions(Player player) {
        Map<String, Object> context = new LinkedHashMap<>();
        var world = player.getWorld();
        long time = world.getTime();
        context.put("world", world.getName());
        context.put("dimension", world.getEnvironment().name());
        context.put("time_ticks", time);
        context.put("phase", time < 12000 ? "day" : time < 13000 ? "sunset" : time < 23000 ? "night" : "sunrise");
        context.put("day", world.getFullTime() / 24000);
        context.put("storm", world.hasStorm());
        context.put("thunder", world.isThundering());
        context.put("light_at_feet", player.getLocation().getBlock().getLightLevel());
        context.put("biome", player.getLocation().getBlock().getBiome().getKey().toString());
        context.put("difficulty", world.getDifficulty().name());
        return GSON.toJson(context);
    }

    /**
     * 附近的生物，按“怪物优先、距离由近到远”排序。查询最多扫描 128 个候选且不超过 1 毫秒。
     *
     * @param radius 搜索半径（格）
     */
    public static String nearby(Player player, int radius, int limit) {
        Location location = player.getLocation();
        double radiusSquared = (double) radius * radius;
        long deadline = System.nanoTime() + 1_000_000;
        int[] scanned = {0};
        record Nearby(boolean monster, double distance, Map<String, Object> data) {}
        List<Nearby> nearby = new ArrayList<>();
        var box = BoundingBox.of(location, radius, radius, radius);
        for (var entity : player.getWorld()
                .getNearbyEntities(
                        box,
                        entity -> scanned[0]++ < 128
                                && System.nanoTime() < deadline
                                && entity instanceof LivingEntity
                                && !(entity instanceof Player))) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            var at = entity.getLocation();
            double distance = at.distanceSquared(location);
            if (distance > radiusSquared || !player.canSee(entity)) {
                continue;
            }
            nearby.add(new Nearby(
                    entity instanceof Monster,
                    distance,
                    Map.of(
                            "type",
                            entity.getType().name(),
                            "hostile_category",
                            entity instanceof Monster,
                            "distance",
                            Math.round(Math.sqrt(distance) * 10) / 10.0,
                            "position",
                            List.of(at.getBlockX(), at.getBlockY(), at.getBlockZ()))));
        }
        nearby.sort(Comparator.comparingInt((Nearby e) -> e.monster() ? 0 : 1).thenComparingDouble(Nearby::distance));
        return GSON.toJson(Map.of(
                "entities",
                nearby.stream().limit(limit).map(Nearby::data).toList(),
                "note",
                "Bounded sample; may be incomplete under load. Category is not proof of aggression; an empty "
                        + "list does not prove absence."));
    }

    public static Map<String, Integer> summarizeInventory(ItemStack[] inventory) {
        Map<String, Integer> summary = new TreeMap<>();
        for (ItemStack item : inventory) {
            if (item != null && item.getType() != Material.AIR) {
                summary.merge(item.getType().name(), item.getAmount(), Integer::sum);
            }
        }
        return Collections.unmodifiableMap(summary);
    }

    private static String describe(ItemStack item) {
        return item == null || item.getType() == Material.AIR
                ? "EMPTY"
                : item.getType().name() + " x" + item.getAmount();
    }

    private static void appendItem(List<String> equipment, ItemStack item) {
        if (item != null && item.getType() != Material.AIR) {
            equipment.add(item.getType().name() + " x" + item.getAmount());
        }
    }

    private PlayerContext() {}
}
