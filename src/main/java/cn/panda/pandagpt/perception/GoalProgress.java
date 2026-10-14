package cn.panda.pandagpt.perception;

import cn.panda.pandagpt.awareness.RecipeKnowledge;
import cn.panda.pandagpt.i18n.Msg;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;

/** 目标进度评估。目标表示“持有目标物品”，不证明玩家通过某种特定方式获得它。 */
public final class GoalProgress {
    /** 特殊目标：采矿准备。 */
    public static final String MINE = "mine";

    /**
     * 一次评估结果。
     *
     * @param label 进度标题，例如“IRON_PICKAXE 0/1”
     * @param detail 进度详情
     * @param fraction 0 到 1 的完成度，用于 BossBar
     */
    public record Progress(
            Msg label, Msg detail, double fraction, boolean complete, boolean ingredientsReady, boolean needsTable) {}

    /** 把玩家输入的中英文名称规范化为目标 ID。 */
    public static String normalize(String name) {
        return switch (name.strip().toLowerCase(Locale.ROOT)) {
            case "采矿", "挖矿", "mining", "mine" -> MINE;
            case "铁镐" -> "IRON_PICKAXE";
            case "石镐" -> "STONE_PICKAXE";
            case "木镐" -> "WOODEN_PICKAXE";
            case "火把" -> "TORCH";
            case "工作台" -> "CRAFTING_TABLE";
            case "熔炉" -> "FURNACE";
            default -> name.strip().replaceFirst("(?i)^minecraft:", "").toUpperCase(Locale.ROOT);
        };
    }

    public static Progress evaluate(
            String target, int quantity, ItemStack[] stock, List<RecipeKnowledge.Entry> entries) {
        return evaluate(target, quantity, stock, stock, entries);
    }

    public static Progress evaluate(
            String target,
            int quantity,
            ItemStack[] stock,
            ItemStack[] ownedItems,
            List<RecipeKnowledge.Entry> entries) {
        return evaluate(target, quantity, stock, ownedItems, entries, () -> true);
    }

    /**
     * 评估目标进度。
     *
     * @param stock 可用于合成的储物栏物品
     * @param ownedItems 全部持有物品（含装备与副手），用于判断是否已完成
     * @param permit 主线程时间预算，耗尽时提前结束配方匹配
     */
    public static Progress evaluate(
            String target,
            int quantity,
            ItemStack[] stock,
            ItemStack[] ownedItems,
            List<RecipeKnowledge.Entry> entries,
            BooleanSupplier permit) {
        if (target.equals(MINE)) {
            int torches = count(ownedItems, "TORCH");
            int food = 0;
            int pickaxes = 0;
            for (ItemStack item : ownedItems) {
                if (item == null) {
                    continue;
                }
                if (item.getType().name().endsWith("_PICKAXE")) {
                    pickaxes += item.getAmount();
                }
                if (item.getType().isEdible()) {
                    food += item.getAmount();
                }
            }
            double fraction = (Math.min(torches, 16) / 16.0 + (pickaxes > 0 ? 1 : 0) + (food > 0 ? 1 : 0)) / 3;
            return new Progress(
                    Msg.of("goal.mine_label"),
                    Msg.of("goal.mine_detail", torches, pickaxes, food),
                    Math.min(1, fraction),
                    false,
                    false,
                    false);
        }
        int owned = count(ownedItems, target);
        Msg label = Msg.of("goal.item_label", target, owned, quantity);
        if (owned >= quantity) {
            return new Progress(label, Msg.of("goal.complete_detail"), 1, true, false, false);
        }
        RecipeKnowledge.Entry best = null;
        boolean ready = false;
        for (var entry : entries) {
            if (!permit.getAsBoolean() || !entry.result().getType().name().equals(target)) {
                continue;
            }
            int batches =
                    (int) Math.ceil((quantity - owned) / (double) entry.result().getAmount());
            if (batches > 16) {
                continue;
            }
            List<RecipeChoice> required = new ArrayList<>();
            for (int n = 0; n < batches; n++) {
                required.addAll(entry.ingredients());
            }
            if (RecipeKnowledge.matches(required, stock, permit)) {
                best = entry;
                ready = true;
                break;
            }
            if (best == null) {
                best = entry;
            }
        }
        Msg detail;
        if (best == null) {
            detail = Msg.of("goal.no_recipe");
        } else if (ready) {
            detail = Msg.of("goal.ingredients_ready");
        } else {
            String needs = String.valueOf(best.ingredients());
            detail = Msg.of(
                    "goal.ingredients_missing", best.id(), needs.length() > 400 ? needs.substring(0, 400) : needs);
        }
        return new Progress(
                label, detail, Math.min(1, owned / (double) quantity), false, ready, best != null && best.needsTable());
    }

    public static int count(ItemStack[] stock, String name) {
        int count = 0;
        for (ItemStack item : stock) {
            if (item != null && item.getType().name().equals(name)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    /** 是否持有任意一把镐。 */
    public static boolean hasPickaxe(ItemStack[] stock) {
        for (ItemStack item : stock) {
            if (item != null && item.getType().name().endsWith("_PICKAXE")) {
                return true;
            }
        }
        return false;
    }

    public static boolean valid(String goal) {
        return goal.equals(MINE) || Material.matchMaterial(goal) != null;
    }

    private GoalProgress() {}
}
