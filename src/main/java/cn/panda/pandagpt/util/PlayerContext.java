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
            long deadline = System.nanoTime() + 1000000; int[] scanned = {0};
            record Nearby(boolean monster, double distance, Map<String, Object> data) {}
            List<Nearby> nearby = new ArrayList<>();
            for (var entity : player.getWorld().getNearbyEntities(org.bukkit.util.BoundingBox.of(location,
                    settings.radius(), settings.radius(), settings.radius()),
                    entity -> scanned[0]++ < 128 && System.nanoTime() < deadline && entity instanceof LivingEntity && !(entity instanceof Player))) {
                if (System.nanoTime() >= deadline) break;
                var at = entity.getLocation(); double distance = at.distanceSquared(location);
                if (distance > radiusSquared || !player.canSee(entity)) continue;
                nearby.add(new Nearby(entity instanceof Monster, distance, Map.of("type", entity.getType().name(),
                        "distance", Math.round(Math.sqrt(distance) * 10) / 10.0,
                        "position", List.of(at.getBlockX(), at.getBlockY(), at.getBlockZ()))));
            }
            nearby.sort(Comparator.comparingInt((Nearby e) -> e.monster() ? 0 : 1).thenComparingDouble(Nearby::distance));
            context.put("nearby_entities", nearby.stream().limit(settings.maxEntities()).map(Nearby::data).toList());
            context.put("nearby_entities_note", "Bounded sample, may be incomplete under load; sorted by monster category then distance. Category is not proof of aggression; empty does not prove absence.");
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
