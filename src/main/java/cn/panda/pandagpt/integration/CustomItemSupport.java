package cn.panda.pandagpt.integration;

import static cn.panda.pandagpt.agent.Schema.choice;
import static cn.panda.pandagpt.agent.Schema.object;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/** Shared bounded, current-player reads for optional custom-item APIs. */
final class CustomItemSupport {
    private static final List<String> SLOTS =
            List.of("mainhand", "offhand", "helmet", "chestplate", "leggings", "boots");

    static JsonObject parameters() {
        JsonObject schema = object(choice(
                "slot",
                "The current player's own equipment slot; defaults to mainhand.",
                false,
                SLOTS.toArray(String[]::new)));
        schema.addProperty("additionalProperties", false);
        return schema;
    }

    static String slot(JsonObject args) {
        JsonElement value = args.get("slot");
        if (value == null || value.isJsonNull()) {
            return "mainhand";
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        String slot = value.getAsString().strip().toLowerCase(Locale.ROOT);
        return SLOTS.contains(slot) ? slot : null;
    }

    /** Clone before crossing the optional API boundary; the inventory remains owned by Bukkit. */
    static ItemStack snapshot(Player player, String slot) {
        PlayerInventory inventory = player.getInventory();
        ItemStack stack = switch (slot) {
            case "mainhand" -> inventory.getItemInMainHand();
            case "offhand" -> inventory.getItemInOffHand();
            case "helmet" -> inventory.getHelmet();
            case "chestplate" -> inventory.getChestplate();
            case "leggings" -> inventory.getLeggings();
            case "boots" -> inventory.getBoots();
            default -> null;
        };
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0 ? null : stack.clone();
    }

    static Plugin installed(Player player, String name) {
        Plugin installed = player.getServer().getPluginManager().getPlugin(name);
        return installed != null && installed.isEnabled() ? installed : null;
    }

    /** Resolve documented public signatures without invoking or initializing an API class. */
    static Method method(Class<?> owner, String name, boolean isStatic, Class<?> returns, Class<?>... arguments)
            throws NoSuchMethodException {
        Method method = owner.getMethod(name, arguments);
        if (Modifier.isStatic(method.getModifiers()) != isStatic || method.getReturnType() != returns) {
            throw new NoSuchMethodException(owner.getName() + "." + name + " has an incompatible signature");
        }
        return method;
    }

    static StringBuilder describe(String plugin, String slot, ItemStack stack) {
        StringBuilder out = new StringBuilder(plugin + " item in own " + slot + ":\nmaterial: "
                + stack.getType().name() + "\namount: " + stack.getAmount());
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return out;
        }
        if (meta.hasDisplayName()) {
            Component name = meta.displayName();
            if (name != null) {
                out.append("\nstack display name: ").append(plain(name));
            }
        }
        if (meta.hasItemName()) {
            Component name = meta.itemName();
            if (name != null) {
                out.append("\nstack item name: ").append(plain(name));
            }
        }
        List<Component> lore = meta.lore();
        if (lore != null) {
            for (int i = 0; i < Math.min(lore.size(), 6); i++) {
                if (lore.get(i) != null) {
                    out.append("\nlore: ").append(plain(lore.get(i)));
                }
            }
            if (lore.size() > 6) {
                out.append("\n[additional lore omitted]");
            }
        }
        if (meta instanceof Damageable damageable) {
            out.append("\nvanilla damage: ").append(damageable.getDamage());
        }
        return out;
    }

    static String bounded(String text) {
        if (text == null) {
            return "unknown";
        }
        String clean = text.replaceAll("[\\p{Cntrl}]+", " ");
        return clean.length() <= 256 ? clean : clean.substring(0, 255) + "…";
    }

    private static String plain(Component component) {
        return bounded(PlainTextComponentSerializer.plainText().serialize(component));
    }

    private CustomItemSupport() {}
}
