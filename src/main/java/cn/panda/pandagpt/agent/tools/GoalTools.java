package cn.panda.pandagpt.agent.tools;

import static cn.panda.pandagpt.agent.Schema.integer;
import static cn.panda.pandagpt.agent.Schema.object;
import static cn.panda.pandagpt.agent.Schema.string;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.Schema;
import cn.panda.pandagpt.agent.ToolResult;
import cn.panda.pandagpt.awareness.RecipeKnowledge;
import cn.panda.pandagpt.perception.GoalProgress;
import java.util.List;

/** “goals” 与 “crafting” 技能的工具：目标跟踪与真实配方查询。 */
public final class GoalTools {
    private static final int MAX_RECIPES = 5;

    public static List<AgentTool> create(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.of(
                        "set_goal",
                        "Track something the player said they want to obtain (e.g. 'I want an iron pickaxe') or 'mine' "
                                + "for mining preparation. Shows a progress bar and enables timely tips. Never set a "
                                + "goal from a mere question.",
                        object(
                                string("item", "Minecraft item ID such as IRON_PICKAXE, or 'mine'", true),
                                integer("quantity", "Target amount, 1 to 64 (default 1)", false)),
                        p -> ToolSupport.perception(plugin),
                        null,
                        (context, args) -> ToolSupport.result(plugin.perception()
                                .setGoal(
                                        context.player(),
                                        Schema.text(args, "item"),
                                        Schema.number(args, "quantity", 1)))),
                AgentTool.of(
                        "clear_goal",
                        "Stop tracking the current goal when the player drops it.",
                        object(),
                        p -> ToolSupport.perception(plugin),
                        null,
                        (context, args) ->
                                ToolSupport.result(plugin.perception().clearGoal(context.player()))),
                AgentTool.of(
                        "goal_status",
                        "The tracked goal and its verified progress.",
                        object(),
                        p -> ToolSupport.perception(plugin),
                        null,
                        (context, args) -> ToolResult.ok(plugin.perception().goalStatus(context.player()))),
                AgentTool.of(
                        "find_recipes",
                        "Real crafting recipes registered on this server for an item, and whether the player's "
                                + "inventory already matches each one.",
                        object(string("item", "Minecraft item ID or common name", true)),
                        p -> plugin.recipes() != null,
                        ToolSupport.activity("recipes"),
                        (context, args) -> ToolResult.ok(findRecipes(
                                plugin.recipes(),
                                GoalProgress.normalize(Schema.text(args, "item")),
                                context.player().getInventory().getStorageContents()))),
                AgentTool.of(
                        "craftable_now",
                        "Recipes the player can craft right now from their storage inventory (bounded sample).",
                        object(),
                        p -> plugin.recipes() != null,
                        ToolSupport.activity("recipes"),
                        (context, args) -> {
                            var matches = plugin.recipes()
                                    .matching(context.player().getInventory().getStorageContents(), 12);
                            return ToolResult.ok(
                                    matches.isEmpty()
                                            ? "No indexed recipe matches the current inventory."
                                            : String.join("\n", matches)
                                                    + "\nUnlocks, workstation access and plugin vetoes are not verified.");
                        }));
    }

    static String findRecipes(RecipeKnowledge recipes, String target, org.bukkit.inventory.ItemStack[] storage) {
        var entries = recipes.forTarget(target);
        if (entries.isEmpty()) {
            return "No crafting-table recipe for " + target + " is indexed on this server; it must be obtained another "
                    + "way (smelting, trading, loot, mining...).";
        }
        StringBuilder out = new StringBuilder();
        for (var entry : entries.stream().limit(MAX_RECIPES).toList()) {
            boolean ready = RecipeKnowledge.matches(entry.ingredients(), storage);
            out.append(entry.id())
                    .append(" -> ")
                    .append(entry.result().getType().name())
                    .append(" x")
                    .append(entry.result().getAmount())
                    .append(entry.needsTable() ? " (crafting table)" : " (2x2 grid)")
                    .append(ready ? " READY from inventory" : " missing ingredients")
                    .append("; ingredients=")
                    .append(entry.ingredients())
                    .append('\n');
        }
        return out.toString();
    }

    private GoalTools() {}
}
