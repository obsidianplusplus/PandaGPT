package cn.panda.pandagpt.integration;

import static cn.panda.pandagpt.agent.Schema.integer;
import static cn.panda.pandagpt.agent.Schema.object;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.ToolResult;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/** Native shop identity plus trades already visible in the calling player's trading UI. */
public final class ShopkeepersAdapter implements Integration {
    record Shop(int id, String name, String tradePermission) {}

    interface Access {
        boolean ready() throws ReflectiveOperationException;

        Shop shop(Entity entity) throws ReflectiveOperationException;

        Shop tradingShop(Player player) throws ReflectiveOperationException;
    }

    private Access access;

    public ShopkeepersAdapter() {}

    ShopkeepersAdapter(Access access) {
        this.access = access;
    }

    ShopkeepersAdapter(NpcShopReadSupport.ApiClasses classes) throws ReflectiveOperationException {
        access = new NativeAccess(classes);
    }

    @Override
    public String id() {
        return "shopkeepers";
    }

    @Override
    public void validate(Plugin installed) throws ReflectiveOperationException {
        access = new NativeAccess(installed.getClass().getClassLoader());
    }

    private boolean available(Player player) {
        if (!player.hasPermission("shopkeeper.trade")
                || NpcShopReadSupport.installed(player, "Shopkeepers") == null
                || access == null) return false;
        try {
            return access.ready();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return false;
        }
    }

    private static boolean permitted(Player player, Shop shop) {
        return shop.tradePermission() == null
                || shop.tradePermission().isBlank()
                || player.hasPermission(shop.tradePermission());
    }

    @Override
    public List<AgentTool> tools(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.readOnlyOf(
                        "shopkeepers_nearby",
                        "List up to 12 visible nearby Shopkeepers entity shops with IDs, names and positions. "
                                + "Checks general and native admin-shop trade permissions; actual trade access can require "
                                + "additional conditions. Excludes block/sign and virtual shops. No containers are read.",
                        object(integer("radius", "Search radius in blocks, 1–32 (default 16)", false)),
                        this::available,
                        null,
                        (context, args) -> nearby(context.player(), args)),
                AgentTool.readOnlyOf(
                        "shopkeepers_trades",
                        "Read up to 12 trade recipes from this player's currently open, valid Shopkeepers trading "
                                + "interface. The player must open the shop themselves first. No other shop's offers, "
                                + "stock containers, transaction histories or other players' inventories are read.",
                        object(),
                        this::available,
                        null,
                        (context, args) -> trades(context.player())));
    }

    private ToolResult nearby(Player player, com.google.gson.JsonObject args) {
        int radius = NpcShopReadSupport.radius(args);
        if (radius < 0) return ToolResult.error("radius must be an integer from 1 to 32.");
        if (!available(player)) return ToolResult.error("Shopkeepers is unavailable or trading permission is missing.");
        var origin = player.getLocation();
        record Found(Shop shop, org.bukkit.Location location, double distance) {}
        List<Found> found = new ArrayList<>();
        int scanned = 0;
        boolean capped = false;
        try {
            for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
                if (scanned++ >= NpcShopReadSupport.MAX_SCANNED_ENTITIES) {
                    capped = true;
                    break;
                }
                if (!NpcShopReadSupport.visible(player, entity, origin, radius)) continue;
                Shop shop = access.shop(entity);
                if (shop == null || !permitted(player, shop)) continue;
                var location = entity.getLocation();
                found.add(new Found(shop, location, Math.sqrt(origin.distanceSquared(location))));
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return ToolResult.error("Shopkeepers query failed; its public API may be incompatible.");
        }
        found.sort(Comparator.comparingDouble(Found::distance)
                .thenComparingInt(row -> row.shop().id()));
        StringBuilder out = new StringBuilder(
                "Visible Shopkeepers entity shops in " + origin.getWorld().getName() + ":\n");
        for (Found row : found.stream().limit(NpcShopReadSupport.MAX_RESULTS).toList()) {
            out.append("#")
                    .append(row.shop().id())
                    .append(" ")
                    .append(NpcShopReadSupport.text(row.shop().name()))
                    .append(" at ")
                    .append(NpcShopReadSupport.position(row.location()))
                    .append(" (")
                    .append(String.format(Locale.ROOT, "%.1f", row.distance()))
                    .append(" blocks)\n");
        }
        if (found.isEmpty()) out.append("No visible permitted entity shops found in the inspected range.\n");
        if (capped) out.append("Entity scan capped at 256; this result may be incomplete.\n");
        if (found.size() > NpcShopReadSupport.MAX_RESULTS)
            out.append("Only the nearest 12 inspected shops are shown.\n");
        out.append("Listing a shop does not confirm it is open or trading is currently allowed. "
                + "Interact with it to check; open its trading UI to inspect displayed offers.");
        return ToolResult.ok(out.toString());
    }

    private ToolResult trades(Player player) {
        if (!available(player)) return ToolResult.error("Shopkeepers is unavailable or trading permission is missing.");
        try {
            Shop shop = access.tradingShop(player);
            if (shop == null || !permitted(player, shop)) {
                return ToolResult.error("Open an accessible Shopkeepers trading interface yourself first.");
            }
            if (!(player.getOpenInventory().getTopInventory() instanceof MerchantInventory inventory)) {
                return ToolResult.error("Your Shopkeepers session is not displaying a merchant trading inventory.");
            }
            var merchant = inventory.getMerchant();
            if (merchant.getTrader() != player) {
                return ToolResult.error("The displayed merchant is not trading with you.");
            }
            List<MerchantRecipe> recipes = merchant.getRecipes();
            StringBuilder out = new StringBuilder(
                    "Your displayed trades with #" + shop.id() + " " + NpcShopReadSupport.text(shop.name()) + ":\n");
            for (int i = 0; i < Math.min(recipes.size(), NpcShopReadSupport.MAX_RESULTS); i++) {
                MerchantRecipe recipe = recipes.get(i);
                List<ItemStack> inputs = recipe.getIngredients();
                out.append(i + 1).append(". ").append(item(recipe.getAdjustedIngredient1()));
                if (inputs.size() > 1 && !inputs.get(1).getType().isAir()) {
                    out.append(" + ").append(item(inputs.get(1)));
                }
                out.append(" -> ").append(item(recipe.getResult()));
                if (recipe.getUses() >= recipe.getMaxUses()) out.append(" [unavailable in current UI]");
                out.append('\n');
            }
            if (recipes.isEmpty()) out.append("No trade recipes are currently displayed.\n");
            if (recipes.size() > NpcShopReadSupport.MAX_RESULTS)
                out.append("Only the first 12 displayed offers are shown.\n");
            out.append("These are displayed offers; the plugin rechecks stock and conditions when trading.");
            return ToolResult.ok(out.toString());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return ToolResult.error("Shopkeepers trading query failed; its public API may be incompatible.");
        }
    }

    private static String item(ItemStack item) {
        if (item == null || item.getType().isAir()) return "none";
        StringBuilder out =
                new StringBuilder(item.getAmount() + " " + item.getType().name());
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            out.append(" (")
                    .append(NpcShopReadSupport.text(meta.getDisplayName()))
                    .append(")");
        }
        var enchantments =
                meta instanceof EnchantmentStorageMeta book ? book.getStoredEnchants() : item.getEnchantments();
        if (!enchantments.isEmpty()) {
            out.append(" [")
                    .append(enchantments.entrySet().stream()
                            .map(entry -> entry.getKey().getKey() + " " + entry.getValue())
                            .sorted()
                            .limit(6)
                            .collect(java.util.stream.Collectors.joining(", ")))
                    .append("]");
            if (enchantments.size() > 6) out.append(" [additional enchantments omitted]");
        }
        return out.toString();
    }

    private static final class NativeAccess implements Access {
        private final Method isEnabled;
        private final Method getRegistry;
        private final Method getShop;
        private final Method isValid;
        private final Method getId;
        private final Method getName;
        private final Class<?> adminShop;
        private final Method getTradePermission;
        private final Method getUiRegistry;
        private final Method getUiSession;
        private final Method sessionValid;
        private final Method sessionActive;
        private final Method sessionPlayer;
        private final Method sessionShop;
        private final Method sessionType;
        private final Method getDefaultUiTypes;
        private final Method getTradingUiType;

        NativeAccess(ClassLoader loader) throws ReflectiveOperationException {
            this(name -> NpcShopReadSupport.apiClass(loader, name));
        }

        NativeAccess(NpcShopReadSupport.ApiClasses classes) throws ReflectiveOperationException {
            String base = "com.nisovin.shopkeepers.api.";
            Class<?> api = classes.resolve(base + "ShopkeepersAPI");
            Class<?> registry = classes.resolve(base + "shopkeeper.ShopkeeperRegistry");
            Class<?> shop = classes.resolve(base + "shopkeeper.Shopkeeper");
            adminShop = classes.resolve(base + "shopkeeper.admin.AdminShopkeeper");
            Class<?> uiRegistry = classes.resolve(base + "ui.UIRegistry");
            Class<?> uiSession = classes.resolve(base + "ui.UISession");
            Class<?> uiType = classes.resolve(base + "ui.UIType");
            Class<?> defaultUiTypes = classes.resolve(base + "ui.DefaultUITypes");
            isEnabled = NpcShopReadSupport.method(api, "isEnabled", boolean.class, true);
            getRegistry = NpcShopReadSupport.method(api, "getShopkeeperRegistry", registry, true);
            getShop = NpcShopReadSupport.method(registry, "getShopkeeperByEntity", shop, false, Entity.class);
            isValid = NpcShopReadSupport.method(shop, "isValid", boolean.class, false);
            getId = NpcShopReadSupport.method(shop, "getId", int.class, false);
            getName = NpcShopReadSupport.method(shop, "getName", String.class, false);
            getTradePermission = NpcShopReadSupport.method(adminShop, "getTradePermission", String.class, false);
            getUiRegistry = NpcShopReadSupport.method(api, "getUIRegistry", uiRegistry, true);
            getUiSession = NpcShopReadSupport.method(uiRegistry, "getUISession", uiSession, false, Player.class);
            sessionValid = NpcShopReadSupport.method(uiSession, "isValid", boolean.class, false);
            sessionActive = NpcShopReadSupport.method(uiSession, "isUIActive", boolean.class, false);
            sessionPlayer = NpcShopReadSupport.method(uiSession, "getPlayer", Player.class, false);
            sessionShop = NpcShopReadSupport.method(uiSession, "getShopkeeper", shop, false);
            sessionType = NpcShopReadSupport.method(uiSession, "getUIType", uiType, false);
            getDefaultUiTypes = NpcShopReadSupport.method(api, "getDefaultUITypes", defaultUiTypes, true);
            getTradingUiType = NpcShopReadSupport.method(defaultUiTypes, "getTradingUIType", uiType, false);
        }

        public boolean ready() throws ReflectiveOperationException {
            return Boolean.TRUE.equals(isEnabled.invoke(null));
        }

        private Shop describe(Object shop) throws ReflectiveOperationException {
            if (shop == null || !Boolean.TRUE.equals(isValid.invoke(shop))) return null;
            String permission = adminShop.isInstance(shop) ? (String) getTradePermission.invoke(shop) : null;
            return new Shop((int) getId.invoke(shop), (String) getName.invoke(shop), permission);
        }

        public Shop shop(Entity entity) throws ReflectiveOperationException {
            return describe(getShop.invoke(getRegistry.invoke(null), entity));
        }

        public Shop tradingShop(Player player) throws ReflectiveOperationException {
            Object session = getUiSession.invoke(getUiRegistry.invoke(null), player);
            if (session == null
                    || !Boolean.TRUE.equals(sessionValid.invoke(session))
                    || !Boolean.TRUE.equals(sessionActive.invoke(session))
                    || sessionPlayer.invoke(session) != player) return null;
            Object tradingType = getTradingUiType.invoke(getDefaultUiTypes.invoke(null));
            if (tradingType == null || !tradingType.equals(sessionType.invoke(session))) return null;
            return describe(sessionShop.invoke(session));
        }
    }
}
