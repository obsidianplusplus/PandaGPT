package cn.panda.pandagpt.perception;

import cn.panda.pandagpt.awareness.RecipeKnowledge;
import org.bukkit.Material;
import org.bukkit.inventory.*;
import java.util.*;

/** Goal means possession of a target item, not proof of a particular crafting action. */
public final class GoalProgress {
    public record Progress(String label, String detail, double fraction, boolean complete, boolean ingredientsReady, boolean needsTable) {}
    public static String normalize(String name) {
        return switch (name.strip().toLowerCase(Locale.ROOT)) {
            case "采矿", "挖矿", "mining", "mine" -> "mine";
            case "铁镐" -> "IRON_PICKAXE"; case "石镐" -> "STONE_PICKAXE"; case "木镐" -> "WOODEN_PICKAXE";
            case "火把" -> "TORCH"; case "工作台" -> "CRAFTING_TABLE"; case "熔炉" -> "FURNACE";
            default -> name.strip().replaceFirst("(?i)^minecraft:", "").toUpperCase(Locale.ROOT);
        };
    }
    public static Progress evaluate(String target, int quantity, ItemStack[] stock, List<RecipeKnowledge.Entry> entries) {
        return evaluate(target, quantity, stock, stock, entries);
    }
    public static Progress evaluate(String target, int quantity, ItemStack[] stock, ItemStack[] ownedItems, List<RecipeKnowledge.Entry> entries) {
        if (target.equals("mine")) {
            int torches = count(ownedItems, "TORCH"), food = 0, pickaxes = 0;
            for (ItemStack item : ownedItems) if (item != null) {
                if (item.getType().name().endsWith("_PICKAXE")) pickaxes += item.getAmount();
                if (item.getType().isEdible()) food += item.getAmount();
            }
            return new Progress("采矿准备", "火把=" + torches + "，镐=" + pickaxes + "，食物=" + food,
                    Math.min(1, (Math.min(torches, 16) / 16.0 + (pickaxes > 0 ? 1 : 0) + (food > 0 ? 1 : 0)) / 3), false, false, false);
        }
        int owned = count(ownedItems, target);
        if (owned >= quantity) return new Progress(target + " " + owned + "/" + quantity, "已确认背包持有目标数量；不推断获得方式", 1, true, false, false);
        RecipeKnowledge.Entry best = null; boolean ready = false;
        for (var entry : entries) if (entry.result().getType().name().equals(target)) {
            int batches = (int) Math.ceil((quantity - owned) / (double) entry.result().getAmount());
            if (batches > 16) continue;
            List<RecipeChoice> required = new ArrayList<>(); for (int n = 0; n < batches; n++) required.addAll(entry.ingredients());
            if (RecipeKnowledge.matches(required, stock)) { best = entry; ready = true; break; }
            if (best == null) best = entry;
        }
        String detail = best == null ? "没有已索引的合成台配方；需其他获取方式" : ready
                ? "材料匹配已确认；仍需配方解锁、工作台和服务器允许" : "材料尚未匹配。配方=" + best.id() + "；需求=" + best.ingredients();
        return new Progress(target + " " + owned + "/" + quantity, detail.length() > 500 ? detail.substring(0, 500) : detail,
                Math.min(1, owned / (double) quantity), false, ready, best != null && best.needsTable());
    }
    public static int count(ItemStack[] stock, String name) {
        int count = 0; for (ItemStack item : stock) if (item != null && item.getType().name().equals(name)) count += item.getAmount(); return count;
    }
    public static boolean valid(String goal) { return goal.equals("mine") || Material.matchMaterial(goal) != null; }
    private GoalProgress() {}
}
