package cn.panda.pandagpt.integration;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.ToolResult;
import java.lang.reflect.Method;
import java.util.List;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Oraxen's documented ID reader plus metadata from the player's actual equipment. */
public final class OraxenAdapter implements Integration {
    private Plugin owner;
    private Method getIdByItem;

    @Override
    public String id() {
        return "oraxen";
    }

    @Override
    public void validate(Plugin installed) throws ReflectiveOperationException {
        Class<?> items = Class.forName(
                "io.th0rgal.oraxen.api.OraxenItems", false, installed.getClass().getClassLoader());
        Method checked = CustomItemSupport.method(items, "getIdByItem", true, String.class, ItemStack.class);
        owner = installed;
        getIdByItem = checked;
    }

    @Override
    public List<AgentTool> tools(ChatGPTIntegration plugin) {
        return List.of(AgentTool.readOnlyOf(
                "oraxen_item",
                "Identify an Oraxen custom item in the current player's own selected hand or armor slot, "
                        + "returning its Oraxen item ID and actual stack display name, lore and vanilla damage.",
                CustomItemSupport.parameters(),
                p -> CustomItemSupport.installed(p, "Oraxen") != null,
                null,
                (context, args) -> {
                    Plugin installed = CustomItemSupport.installed(context.player(), "Oraxen");
                    if (installed == null) {
                        return ToolResult.error("Oraxen is no longer enabled.");
                    }
                    String slot = CustomItemSupport.slot(args);
                    if (slot == null) {
                        return ToolResult.error("Select mainhand, offhand, helmet, chestplate, leggings or boots.");
                    }
                    ItemStack stack = CustomItemSupport.snapshot(context.player(), slot);
                    if (stack == null) {
                        return ToolResult.ok("The player's " + slot + " is empty.");
                    }
                    try {
                        if (owner != installed || getIdByItem == null) {
                            validate(installed);
                        }
                        String id = (String) getIdByItem.invoke(null, stack);
                        if (id == null || id.isBlank()) {
                            return ToolResult.ok("The item in own " + slot + " is not identified by Oraxen.");
                        }
                        StringBuilder out = CustomItemSupport.describe("Oraxen", slot, stack);
                        out.append("\nitem_id: ").append(CustomItemSupport.bounded(id));
                        return ToolResult.ok(out.toString());
                    } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                        return ToolResult.error("Oraxen item data is unavailable; the API or content is not ready.");
                    }
                }));
    }
}
