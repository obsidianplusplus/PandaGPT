package cn.panda.pandagpt.integration;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.ToolResult;
import java.lang.reflect.Method;
import java.util.List;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** ItemsAdder CustomStack reads; no registry creation, item granting or configuration access. */
public final class ItemsAdderAdapter implements Integration {
    private Plugin owner;
    private Api api;

    @Override
    public String id() {
        return "itemsadder";
    }

    @Override
    public void validate(Plugin installed) throws ReflectiveOperationException {
        Class<?> stack = Class.forName(
                "dev.lone.itemsadder.api.CustomStack",
                false,
                installed.getClass().getClassLoader());
        Api checked = new Api(
                CustomItemSupport.method(stack, "byItemStack", true, stack, ItemStack.class),
                CustomItemSupport.method(stack, "getNamespacedID", false, String.class),
                CustomItemSupport.method(stack, "getDisplayName", false, String.class),
                CustomItemSupport.method(stack, "isBlock", false, boolean.class),
                CustomItemSupport.method(stack, "hasCustomDurability", false, boolean.class),
                CustomItemSupport.method(stack, "getDurability", false, int.class),
                CustomItemSupport.method(stack, "getMaxDurability", false, int.class),
                CustomItemSupport.method(stack, "hasUsagesAttribute", false, boolean.class),
                CustomItemSupport.method(stack, "getUsages", false, int.class));
        owner = installed;
        api = checked;
    }

    @Override
    public List<AgentTool> tools(ChatGPTIntegration plugin) {
        return List.of(AgentTool.readOnlyOf(
                "itemsadder_item",
                "Identify an ItemsAdder custom item in the current player's own selected hand or armor slot: "
                        + "namespaced ID, display name, lore, block status, custom durability and remaining uses.",
                CustomItemSupport.parameters(),
                p -> CustomItemSupport.installed(p, "ItemsAdder") != null,
                null,
                (context, args) -> {
                    Plugin installed = CustomItemSupport.installed(context.player(), "ItemsAdder");
                    if (installed == null) {
                        return ToolResult.error("ItemsAdder is no longer enabled.");
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
                        if (owner != installed || api == null) {
                            validate(installed);
                        }
                        Object custom = api.byItemStack().invoke(null, stack);
                        if (custom == null) {
                            return ToolResult.ok("The item in own " + slot + " is not identified by ItemsAdder.");
                        }
                        StringBuilder out = CustomItemSupport.describe("ItemsAdder", slot, stack);
                        out.append("\nnamespaced_id: ").append(CustomItemSupport.bounded((String)
                                api.id().invoke(custom)));
                        out.append("\nconfigured display name: ").append(CustomItemSupport.bounded((String)
                                api.name().invoke(custom)));
                        out.append("\ncustom block item: ").append(api.block().invoke(custom));
                        if ((boolean) api.customDurability().invoke(custom)) {
                            out.append("\ncustom durability: ")
                                    .append(api.durability().invoke(custom))
                                    .append('/')
                                    .append(api.maxDurability().invoke(custom));
                        }
                        if ((boolean) api.hasUsages().invoke(custom)) {
                            out.append("\nremaining uses: ").append(api.usages().invoke(custom));
                        }
                        return ToolResult.ok(out.toString());
                    } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                        return ToolResult.error(
                                "ItemsAdder item data is unavailable; the API or content is not ready.");
                    }
                }));
    }

    private record Api(
            Method byItemStack,
            Method id,
            Method name,
            Method block,
            Method customDurability,
            Method durability,
            Method maxDurability,
            Method hasUsages,
            Method usages) {}
}
