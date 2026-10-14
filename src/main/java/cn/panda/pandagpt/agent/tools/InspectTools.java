package cn.panda.pandagpt.agent.tools;

import static cn.panda.pandagpt.agent.Schema.choice;
import static cn.panda.pandagpt.agent.Schema.integer;
import static cn.panda.pandagpt.agent.Schema.object;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.Schema;
import cn.panda.pandagpt.agent.ToolResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.util.BoundingBox;

/** “items”“trading”“server-guide” 技能的工具：检查物品、查看附近村民交易、列出服务器插件。 */
public final class InspectTools {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    public static List<AgentTool> create(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.readOnlyOf(
                        "inspect_item",
                        "Details of an item the player holds or wears: durability left, enchantments, repair cost, "
                                + "custom name and lore.",
                        object(choice(
                                "slot",
                                "Which item",
                                false,
                                "main_hand",
                                "off_hand",
                                "helmet",
                                "chestplate",
                                "leggings",
                                "boots")),
                        p -> true,
                        ToolSupport.activity("status"),
                        (context, args) -> ToolResult.ok(describe(item(context.player(), Schema.text(args, "slot"))))),
                AgentTool.readOnlyOf(
                        "villager_trades",
                        "Trades offered by villagers and wandering traders the player can see nearby, including "
                                + "enchanted book enchantments. Use it to find who sells or buys something.",
                        object(integer("radius", "Search radius, 4 to 32 blocks (default 16)", false)),
                        p -> true,
                        ToolSupport.activity("nearby"),
                        (context, args) -> ToolResult.ok(
                                trades(context.player(), Math.clamp(Schema.number(args, "radius", 16), 4, 32)))),
                AgentTool.readOnlyOf(
                        "server_plugins",
                        "Plugins installed on this server with versions and descriptions, to know which features "
                                + "and commands exist. Combine with list_server_commands and search_server_knowledge.",
                        object(),
                        p -> true,
                        ToolSupport.activity("knowledge"),
                        (context, args) -> ToolResult.ok(java.util.Arrays.stream(context.player()
                                        .getServer()
                                        .getPluginManager()
                                        .getPlugins())
                                .filter(org.bukkit.plugin.Plugin::isEnabled)
                                .sorted(Comparator.comparing(org.bukkit.plugin.Plugin::getName))
                                .limit(80)
                                .map(p -> {
                                    var meta = p.getPluginMeta();
                                    String description = meta.getDescription() == null ? "" : meta.getDescription();
                                    return p.getName() + " " + meta.getVersion()
                                            + (description.isBlank() ? "" : ": " + description.strip());
                                })
                                .collect(Collectors.joining("\n")))));
    }

    private static ItemStack item(Player player, String slot) {
        var inventory = player.getInventory();
        return switch (slot) {
            case "off_hand" -> inventory.getItemInOffHand();
            case "helmet" -> inventory.getHelmet();
            case "chestplate" -> inventory.getChestplate();
            case "leggings" -> inventory.getLeggings();
            case "boots" -> inventory.getBoots();
            default -> inventory.getItemInMainHand();
        };
    }

    static String describe(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return "The slot is empty.";
        }
        StringBuilder out = new StringBuilder(item.getType().name() + " x" + item.getAmount());
        var meta = item.getItemMeta();
        if (meta == null) {
            return out.toString();
        }
        if (meta.hasDisplayName() && meta.displayName() != null) {
            out.append("\nname: ").append(PLAIN.serialize(meta.displayName()));
        }
        int max = item.getType().getMaxDurability();
        if (max > 0 && meta instanceof Damageable damageable) {
            int left = max - damageable.getDamage();
            out.append("\ndurability: ")
                    .append(left)
                    .append("/")
                    .append(max)
                    .append(" (")
                    .append(Math.round(left * 100.0 / max))
                    .append("%)");
        }
        if (meta.isUnbreakable()) {
            out.append("\nunbreakable");
        }
        if (!meta.getEnchants().isEmpty()) {
            out.append("\nenchantments: ").append(enchantments(meta.getEnchants()));
        }
        if (meta instanceof EnchantmentStorageMeta book && book.hasStoredEnchants()) {
            out.append("\nstored enchantments: ").append(enchantments(book.getStoredEnchants()));
        }
        if (meta instanceof Repairable repairable && repairable.hasRepairCost()) {
            out.append("\nanvil repair cost: ")
                    .append(repairable.getRepairCost())
                    .append(repairable.getRepairCost() >= 39 ? " (too expensive to repair in survival)" : "");
        }
        if (meta.lore() != null) {
            meta.lore().stream().limit(6).forEach(line -> out.append("\nlore: ").append(PLAIN.serialize(line)));
        }
        return out.toString();
    }

    private static String enchantments(java.util.Map<org.bukkit.enchantments.Enchantment, Integer> map) {
        return map.entrySet().stream()
                .map(e -> e.getKey().getKey().getKey() + " " + e.getValue())
                .collect(Collectors.joining(", "));
    }

    // 只列出玩家看得见的商人，每个最多 10 条交易，最多 8 个商人。
    static String trades(Player player, int radius) {
        var location = player.getLocation();
        List<AbstractVillager> merchants = new ArrayList<>();
        int[] scanned = {0};
        for (var entity : player.getWorld()
                .getNearbyEntities(
                        BoundingBox.of(location, radius, radius, radius),
                        e -> scanned[0]++ < 128 && e instanceof AbstractVillager)) {
            if (player.canSee(entity)) {
                merchants.add((AbstractVillager) entity);
            }
        }
        merchants.sort(Comparator.comparingDouble(m -> m.getLocation().distanceSquared(location)));
        if (merchants.isEmpty()) {
            return "No villager or wandering trader visible within " + radius + " blocks.";
        }
        StringBuilder out = new StringBuilder();
        for (AbstractVillager merchant : merchants.stream().limit(8).toList()) {
            out.append(merchant.getType().name());
            if (merchant instanceof Villager villager) {
                out.append(" ")
                        .append(villager.getProfession().getKey().getKey())
                        .append(" level ")
                        .append(villager.getVillagerLevel());
            }
            var at = merchant.getLocation();
            out.append(" at ")
                    .append(at.getBlockX())
                    .append(",")
                    .append(at.getBlockY())
                    .append(",")
                    .append(at.getBlockZ())
                    .append(" (")
                    .append(Math.round(at.distance(location)))
                    .append(" blocks)\n");
            List<MerchantRecipe> recipes = merchant.getRecipes();
            if (recipes.isEmpty()) {
                out.append("  no trades yet (unemployed or not unlocked)\n");
            }
            for (MerchantRecipe recipe : recipes.stream().limit(10).toList()) {
                out.append("  ")
                        .append(recipe.getIngredients().stream()
                                .filter(i -> i != null && i.getType() != Material.AIR)
                                .map(i -> i.getType().name() + " x" + i.getAmount())
                                .collect(Collectors.joining(" + ")))
                        .append(" -> ")
                        .append(recipe.getResult().getType().name())
                        .append(" x")
                        .append(recipe.getResult().getAmount());
                if (recipe.getResult().getItemMeta() instanceof EnchantmentStorageMeta book
                        && book.hasStoredEnchants()) {
                    out.append(" [")
                            .append(enchantments(book.getStoredEnchants()))
                            .append("]");
                }
                if (recipe.getUses() >= recipe.getMaxUses()) {
                    out.append(" (sold out until restock)");
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    private InspectTools() {}
}
