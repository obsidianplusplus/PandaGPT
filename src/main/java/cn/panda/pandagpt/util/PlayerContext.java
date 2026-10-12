package cn.panda.pandagpt.util;

import cn.panda.pandagpt.awareness.RecipeKnowledge;
import cn.panda.pandagpt.config.Settings;
import com.google.gson.Gson;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.*;

/** Must be called on the server thread; returns text containing no live Bukkit objects. */
public final class PlayerContext {
    public static String capture(Player player, String question, Settings settings, RecipeKnowledge recipes) {
        if (!settings.environmentEnabled()) return "";
        Map<String, Object> context = new LinkedHashMap<>();
        Location location = player.getLocation();
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        context.put("health", player.getHealth()); context.put("max_health", maxHealth == null ? 20 : maxHealth.getValue());
        context.put("food", player.getFoodLevel()); context.put("saturation", player.getSaturation());
        context.put("level", player.getLevel()); context.put("gamemode", player.getGameMode().name());
        context.put("world", player.getWorld().getName()); context.put("dimension", player.getWorld().getEnvironment().name());
        context.put("position", List.of(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
        context.put("time", player.getWorld().getTime()); context.put("storm", player.getWorld().hasStorm());
        context.put("light", location.getBlock().getLightLevel());
        ItemStack[] storage = player.getInventory().getStorageContents();
        context.put("inventory", summarizeInventory(storage));
        List<String> equipment = new ArrayList<>();
        for (ItemStack item : player.getInventory().getArmorContents()) appendItem(equipment, item);
        appendItem(equipment, player.getInventory().getItemInMainHand());
        appendItem(equipment, player.getInventory().getItemInOffHand());
        context.put("equipment", equipment);
        context.put("effects", player.getActivePotionEffects().stream().map(effect ->
                effect.getType().getKey() + " level=" + (effect.getAmplifier() + 1) + " seconds=" + effect.getDuration() / 20).toList());
        String lower = question.toLowerCase(Locale.ROOT);
        if (lower.matches("(?s).*(附近|周围|危险|怪物|生存|nearby|danger|mob|surviv).*")) {
            double radiusSquared = settings.radius() * settings.radius();
            context.put("nearby_entities", player.getNearbyEntities(settings.radius(), settings.radius(), settings.radius()).stream()
                    .filter(entity -> entity instanceof LivingEntity && !(entity instanceof Player))
                    .filter(entity -> entity.getLocation().distanceSquared(location) <= radiusSquared)
                    .sorted(Comparator.comparingInt((org.bukkit.entity.Entity entity) -> entity instanceof Monster ? 0 : 1)
                            .thenComparingDouble(entity -> entity.getLocation().distanceSquared(location)))
                    .limit(settings.maxEntities()).map(entity -> Map.of("type", entity.getType().name(),
                            "distance", Math.round(entity.getLocation().distance(location) * 10) / 10.0,
                            "position", List.of(entity.getLocation().getBlockX(), entity.getLocation().getBlockY(), entity.getLocation().getBlockZ())))
                    .toList());
            context.put("nearby_entities_note", "Sorted by monster category then distance; category is not proof of aggression.");
        }
        if (lower.matches("(?s).*(合成|配方|材料|craft|recipe).*")) {
            context.put("ingredient_matching_recipes", recipes.matching(storage, settings.maxRecipes()));
            context.put("recipe_note", "Limited crafting-recipe sample matching storage ingredients only; unlocks, workstation access and plugin vetoes are not verified. Custom predicate recipes are omitted.");
        }
        return new Gson().toJson(context);
    }
    public static Map<String, Integer> summarizeInventory(ItemStack[] inventory) {
        Map<String, Integer> summary = new TreeMap<>();
        for (ItemStack item : inventory) if (item != null && item.getType() != Material.AIR)
            summary.merge(item.getType().name(), item.getAmount(), Integer::sum);
        return Collections.unmodifiableMap(summary);
    }
    private static void appendItem(List<String> equipment, ItemStack item) {
        if (item != null && item.getType() != Material.AIR) equipment.add(item.getType().name() + " x" + item.getAmount());
    }
    private PlayerContext() {}
}
