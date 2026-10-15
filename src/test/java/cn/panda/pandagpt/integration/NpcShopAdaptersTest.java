package cn.panda.pandagpt.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.ToolContext;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

class NpcShopAdaptersTest {
    @Test
    void declarationsLoadWithoutOptionalApisAndAreIndependentReads() {
        ChatGPTIntegration plugin = mock(ChatGPTIntegration.class);
        var tools = new ArrayList<AgentTool>();
        tools.addAll(new CitizensAdapter().tools(plugin));
        tools.addAll(new ShopkeepersAdapter().tools(plugin));
        assertEquals(
                List.of("citizens_nearby", "shopkeepers_nearby", "shopkeepers_trades"),
                tools.stream().map(AgentTool::name).toList());
        assertTrue(tools.stream().allMatch(AgentTool::independentRead));
    }

    @Test
    void disabledPluginAndMissingTradePermissionPreventNativeReads() throws Exception {
        Player player = player("Shopkeepers");
        ShopkeepersAdapter.Access access = mock(ShopkeepersAdapter.Access.class);
        AgentTool tool = new ShopkeepersAdapter(access).tools(null).getFirst();
        when(player.hasPermission("shopkeeper.trade")).thenReturn(false);
        assertFalse(tool.available(player));
        assertTrue(call(tool, player, new JsonObject()).startsWith("ERROR:"));
        verifyNoInteractions(access);
        when(player.hasPermission("shopkeeper.trade")).thenReturn(true);
        when(player.getServer().getPluginManager().isPluginEnabled("Shopkeepers"))
                .thenReturn(false);
        assertFalse(tool.available(player));
        assertTrue(call(tool, player, new JsonObject()).startsWith("ERROR:"));
        verifyNoInteractions(access);
    }

    @Test
    void invalidRadiusIsRejectedBeforeAnyApiOrWorldQuery() throws Exception {
        CitizensAdapter.Access access = mock(CitizensAdapter.Access.class);
        Player player = player("Citizens");
        AgentTool tool = new CitizensAdapter(access).tools(null).getFirst();
        for (String value : List.of("-1", "33", "1.5", "lots")) {
            JsonObject args = new JsonObject();
            args.addProperty("radius", value);
            assertTrue(call(tool, player, args).startsWith("ERROR:"));
        }
        verifyNoInteractions(access);
        verify(player, never()).getNearbyEntities(anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void nearbyNpcsDoNotExposeHiddenOutOfRangeObstructedOrOtherWorldEntities() throws Exception {
        CitizensAdapter.Access access = mock(CitizensAdapter.Access.class);
        when(access.ready()).thenReturn(true);
        Player player = player("Citizens");
        World world = player.getWorld();
        Entity visible = entity(player, new Location(world, 3, 64, 0));
        Entity hidden = entity(player, new Location(world, 4, 64, 0));
        Entity obstructed = entity(player, new Location(world, 5, 64, 0));
        Entity far = entity(player, new Location(world, 17, 64, 0));
        Entity otherWorld = entity(player, new Location(mock(World.class), 3, 64, 0));
        when(player.canSee(hidden)).thenReturn(false);
        when(player.hasLineOfSight(obstructed)).thenReturn(false);
        when(player.getNearbyEntities(16, 16, 16)).thenReturn(List.of(hidden, obstructed, far, otherWorld, visible));
        when(access.npc(player, visible)).thenReturn(new CitizensAdapter.Npc(4, "§aGuide\nNPC"));
        String result = call(new CitizensAdapter(access).tools(null).getFirst(), player, new JsonObject());
        assertTrue(result.contains("#4 Guide NPC at 3,64,0 (3.0 blocks)"), result);
        verify(access).npc(player, visible);
        verify(access, never()).npc(player, hidden);
        verify(access, never()).npc(player, obstructed);
        verify(access, never()).npc(player, far);
        verify(access, never()).npc(player, otherWorld);
    }

    @Test
    void nativeCitizensFilterIsReadWithoutCreatingTraits() throws Exception {
        Player player = player("Citizens");
        Entity entity = entity(player, new Location(player.getWorld(), 2, 64, 0));
        when(player.getNearbyEntities(16, 16, 16)).thenReturn(List.of(entity));
        CitizenRegistry registry = mock(CitizenRegistry.class);
        CitizenNpc npc = mock(CitizenNpc.class);
        CitizenFilter filter = mock(CitizenFilter.class);
        when(registry.getNPC(entity)).thenReturn(npc);
        when(npc.isSpawned()).thenReturn(true);
        when(npc.getTraitNullable(CitizenFilter.class)).thenReturn(filter);
        when(npc.getId()).thenReturn(9);
        when(npc.getFullName()).thenReturn("Secret guide");
        try (var api = mockStatic(CitizenApi.class)) {
            api.when(CitizenApi::hasImplementation).thenReturn(true);
            api.when(CitizenApi::getNPCRegistry).thenReturn(registry);
            AgentTool tool = new CitizensAdapter(citizenClasses()).tools(null).getFirst();
            when(filter.isHidden(player)).thenReturn(true);
            assertFalse(call(tool, player, new JsonObject()).contains("Secret guide"));
            when(filter.isHidden(player)).thenReturn(false);
            assertTrue(call(tool, player, new JsonObject()).contains("#9 Secret guide"));
            when(npc.isSpawned()).thenReturn(false);
            assertFalse(call(tool, player, new JsonObject()).contains("Secret guide"));
            verify(npc, times(2)).getTraitNullable(CitizenFilter.class);
            verify(filter, times(2)).isHidden(player);
        }
    }

    @Test
    void entityInspectionsAndNpcResultsAreCappedAndReported() throws Exception {
        CitizensAdapter.Access access = mock(CitizensAdapter.Access.class);
        when(access.ready()).thenReturn(true);
        Player player = player("Citizens");
        List<Entity> entities = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            Entity entity = entity(player, new Location(player.getWorld(), 1, 64, 0));
            entities.add(entity);
            when(access.npc(player, entity)).thenReturn(new CitizensAdapter.Npc(i, "NPC"));
        }
        when(player.getNearbyEntities(16, 16, 16)).thenReturn(entities);
        String result = call(new CitizensAdapter(access).tools(null).getFirst(), player, new JsonObject());
        assertEquals(12, result.lines().filter(line -> line.startsWith("#")).count());
        assertTrue(result.contains("Entity scan capped at 256"));
        assertTrue(result.contains("nearest 12"));
        verify(access, times(256)).npc(eq(player), any(Entity.class));
    }

    @Test
    void nativeAdminShopPermissionIsRequiredBeforeExposingNearbyShop() throws Exception {
        Player player = player("Shopkeepers");
        Entity entity = entity(player, new Location(player.getWorld(), 2, 64, 0));
        when(player.getNearbyEntities(16, 16, 16)).thenReturn(List.of(entity));
        ShopRegistry registry = mock(ShopRegistry.class);
        AdminShop shop = mock(AdminShop.class);
        when(registry.getShopkeeperByEntity(entity)).thenReturn(shop);
        when(shop.isValid()).thenReturn(true);
        when(shop.getId()).thenReturn(12);
        when(shop.getName()).thenReturn("Members shop");
        when(shop.getTradePermission()).thenReturn("shops.members");
        try (var api = mockStatic(ShopApi.class)) {
            api.when(ShopApi::isEnabled).thenReturn(true);
            api.when(ShopApi::getShopkeeperRegistry).thenReturn(registry);
            AgentTool tool = new ShopkeepersAdapter(shopClasses()).tools(null).getFirst();
            when(player.hasPermission("shops.members")).thenReturn(false);
            assertFalse(call(tool, player, new JsonObject()).contains("Members shop"));
            when(player.hasPermission("shops.members")).thenReturn(true);
            String result = call(tool, player, new JsonObject());
            assertTrue(result.contains("#12 Members shop"));
            assertTrue(result.contains("does not confirm it is open"));
        }
    }

    @Test
    void invalidInactiveWrongPlayerAndEditorSessionsNeverReadInventory() throws Exception {
        Player player = player("Shopkeepers");
        ShopUiRegistry registry = mock(ShopUiRegistry.class);
        ShopSession session = mock(ShopSession.class);
        ShopUiType trading = mock(ShopUiType.class);
        ShopDefaultUiTypes types = mock(ShopDefaultUiTypes.class);
        when(registry.getUISession(player)).thenReturn(session);
        when(types.getTradingUIType()).thenReturn(trading);
        try (var api = mockStatic(ShopApi.class)) {
            api.when(ShopApi::isEnabled).thenReturn(true);
            api.when(ShopApi::getUIRegistry).thenReturn(registry);
            api.when(ShopApi::getDefaultUITypes).thenReturn(types);
            AgentTool tool = new ShopkeepersAdapter(shopClasses()).tools(null).get(1);
            assertTrue(call(tool, player, new JsonObject()).startsWith("ERROR:"));
            when(session.isValid()).thenReturn(true);
            assertTrue(call(tool, player, new JsonObject()).startsWith("ERROR:"));
            when(session.isUIActive()).thenReturn(true);
            when(session.getPlayer()).thenReturn(mock(Player.class));
            assertTrue(call(tool, player, new JsonObject()).startsWith("ERROR:"));
            when(session.getPlayer()).thenReturn(player);
            when(session.getUIType()).thenReturn(mock(ShopUiType.class));
            assertTrue(call(tool, player, new JsonObject()).startsWith("ERROR:"));
            verify(player, never()).getOpenInventory();
            verify(session, never()).getShopkeeper();
        }
    }

    @Test
    void ownActiveNativeSessionReadsOnlyOwnDisplayedAdjustedOffers() throws Exception {
        Player player = player("Shopkeepers");
        ShopUiRegistry registry = mock(ShopUiRegistry.class);
        ShopSession session = mock(ShopSession.class);
        ShopUiType trading = mock(ShopUiType.class);
        ShopDefaultUiTypes types = mock(ShopDefaultUiTypes.class);
        Shop shop = mock(Shop.class);
        when(registry.getUISession(player)).thenReturn(session);
        when(types.getTradingUIType()).thenReturn(trading);
        when(session.isValid()).thenReturn(true);
        when(session.isUIActive()).thenReturn(true);
        when(session.getPlayer()).thenReturn(player);
        when(session.getUIType()).thenReturn(trading);
        when(session.getShopkeeper()).thenReturn(shop);
        when(shop.isValid()).thenReturn(true);
        when(shop.getId()).thenReturn(5);
        when(shop.getName()).thenReturn("Books");
        MerchantInventory inventory = mock(MerchantInventory.class);
        Merchant merchant = mock(Merchant.class);
        InventoryView view = mock(InventoryView.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(inventory);
        when(inventory.getMerchant()).thenReturn(merchant);
        when(merchant.getTrader()).thenReturn(player);
        MerchantRecipe recipe = mock(MerchantRecipe.class);
        ItemStack baseEmeralds = item(Material.EMERALD, 10);
        ItemStack inputBook = item(Material.BOOK, 1);
        ItemStack adjustedEmeralds = item(Material.EMERALD, 7);
        when(recipe.getIngredients()).thenReturn(List.of(baseEmeralds, inputBook));
        when(recipe.getAdjustedIngredient1()).thenReturn(adjustedEmeralds);
        ItemStack book = item(Material.ENCHANTED_BOOK, 1);
        EnchantmentStorageMeta meta = mock(EnchantmentStorageMeta.class);
        when(meta.hasDisplayName()).thenReturn(true);
        when(meta.getDisplayName()).thenReturn("§aLibrary book");
        when(meta.getStoredEnchants()).thenReturn(Map.of());
        when(book.getItemMeta()).thenReturn(meta);
        when(recipe.getResult()).thenReturn(book);
        when(recipe.getUses()).thenReturn(3);
        when(recipe.getMaxUses()).thenReturn(3);
        when(merchant.getRecipes()).thenReturn(List.of(recipe));
        try (var api = mockStatic(ShopApi.class)) {
            api.when(ShopApi::isEnabled).thenReturn(true);
            api.when(ShopApi::getUIRegistry).thenReturn(registry);
            api.when(ShopApi::getDefaultUITypes).thenReturn(types);
            String result =
                    call(new ShopkeepersAdapter(shopClasses()).tools(null).get(1), player, new JsonObject());
            assertTrue(result.contains("Your displayed trades with #5 Books"), result);
            assertTrue(result.contains("7 EMERALD + 1 BOOK -> 1 ENCHANTED_BOOK"), result);
            assertTrue(result.contains("1 ENCHANTED_BOOK (Library book)"), result);
            assertFalse(result.contains("10 EMERALD"));
            assertTrue(result.contains("unavailable in current UI"));
            verify(registry).getUISession(player);
            verify(inventory, never()).getContents();
            api.verify(ShopApi::getShopkeeperRegistry, never());
        }
    }

    @Test
    void merchantOfAnotherPlayerNeverExposesRecipes() throws Exception {
        Player player = player("Shopkeepers");
        ShopkeepersAdapter.Access access = mock(ShopkeepersAdapter.Access.class);
        when(access.ready()).thenReturn(true);
        when(access.tradingShop(player)).thenReturn(new ShopkeepersAdapter.Shop(1, "shop", null));
        InventoryView view = mock(InventoryView.class);
        MerchantInventory inventory = mock(MerchantInventory.class);
        Merchant merchant = mock(Merchant.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(inventory);
        when(inventory.getMerchant()).thenReturn(merchant);
        when(merchant.getTrader()).thenReturn(mock(Player.class));
        String result = call(new ShopkeepersAdapter(access).tools(null).get(1), player, new JsonObject());
        assertTrue(result.startsWith("ERROR:"));
        verify(merchant, never()).getRecipes();
    }

    @Test
    void missingApiAndIncompatibleSignaturesFailValidation() {
        Plugin plugin = mock(Plugin.class);
        assertThrows(ClassNotFoundException.class, () -> new CitizensAdapter().validate(plugin));
        assertThrows(ClassNotFoundException.class, () -> new ShopkeepersAdapter().validate(plugin));
        assertThrows(
                NoSuchMethodException.class,
                () -> NpcShopReadSupport.method(CitizenApi.class, "hasImplementation", String.class, true));
        assertThrows(
                NoSuchMethodException.class,
                () -> NpcShopReadSupport.method(CitizenApi.class, "getNPCRegistry", CitizenRegistry.class, false));
    }

    private static String call(AgentTool tool, Player player, JsonObject args) throws Exception {
        return tool.call(new ToolContext(player, null, "en_US"), args).get().content();
    }

    private static Player player(String pluginName) {
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(player.hasPermission("shopkeeper.trade")).thenReturn(true);
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(player.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        when(manager.isPluginEnabled(pluginName)).thenReturn(true);
        when(manager.getPlugin(pluginName)).thenReturn(mock(Plugin.class));
        return player;
    }

    private static Entity entity(Player player, Location location) {
        Entity entity = mock(Entity.class);
        when(entity.isValid()).thenReturn(true);
        when(entity.getLocation()).thenReturn(location);
        when(player.canSee(entity)).thenReturn(true);
        when(player.hasLineOfSight(entity)).thenReturn(true);
        return entity;
    }

    private static ItemStack item(Material material, int amount) {
        ItemStack item = mock(ItemStack.class);
        // Paper's Material.isAir() now queries the server-backed block registry. Keep the
        // headless item's material contract local instead of installing a fake registry.
        Material displayedMaterial = mock(Material.class);
        when(displayedMaterial.isAir()).thenReturn(false);
        when(displayedMaterial.name()).thenReturn(material.name());
        when(item.getType()).thenReturn(displayedMaterial);
        when(item.getAmount()).thenReturn(amount);
        return item;
    }

    private static NpcShopReadSupport.ApiClasses citizenClasses() {
        Map<String, Class<?>> classes = Map.of(
                "net.citizensnpcs.api.CitizensAPI", CitizenApi.class,
                "net.citizensnpcs.api.npc.NPCRegistry", CitizenRegistry.class,
                "net.citizensnpcs.api.npc.NPC", CitizenNpc.class,
                "net.citizensnpcs.api.trait.Trait", CitizenTrait.class,
                "net.citizensnpcs.api.trait.trait.PlayerFilter", CitizenFilter.class);
        return name -> {
            Class<?> type = classes.get(name);
            if (type == null) throw new ClassNotFoundException(name);
            return type;
        };
    }

    private static NpcShopReadSupport.ApiClasses shopClasses() {
        String base = "com.nisovin.shopkeepers.api.";
        Map<String, Class<?>> classes = Map.of(
                base + "ShopkeepersAPI", ShopApi.class,
                base + "shopkeeper.ShopkeeperRegistry", ShopRegistry.class,
                base + "shopkeeper.Shopkeeper", Shop.class,
                base + "shopkeeper.admin.AdminShopkeeper", AdminShop.class,
                base + "ui.UIRegistry", ShopUiRegistry.class,
                base + "ui.UISession", ShopSession.class,
                base + "ui.UIType", ShopUiType.class,
                base + "ui.DefaultUITypes", ShopDefaultUiTypes.class);
        return name -> {
            Class<?> type = classes.get(name);
            if (type == null) throw new ClassNotFoundException(name);
            return type;
        };
    }

    // Public API contract fixtures allow testing the real reflective path without shipping plugin jars.
    public static class CitizenApi {
        public static boolean hasImplementation() {
            return false;
        }

        public static CitizenRegistry getNPCRegistry() {
            return null;
        }
    }

    public interface CitizenRegistry {
        CitizenNpc getNPC(Entity entity);
    }

    public interface CitizenNpc {
        boolean isSpawned();

        int getId();

        String getFullName();

        CitizenTrait getTraitNullable(Class<?> trait);
    }

    public interface CitizenTrait {}

    public interface CitizenFilter extends CitizenTrait {
        boolean isHidden(Player player);
    }

    public static class ShopApi {
        public static boolean isEnabled() {
            return false;
        }

        public static ShopRegistry getShopkeeperRegistry() {
            return null;
        }

        public static ShopUiRegistry getUIRegistry() {
            return null;
        }

        public static ShopDefaultUiTypes getDefaultUITypes() {
            return null;
        }
    }

    public interface ShopRegistry {
        Shop getShopkeeperByEntity(Entity entity);
    }

    public interface Shop {
        boolean isValid();

        int getId();

        String getName();
    }

    public interface AdminShop extends Shop {
        String getTradePermission();
    }

    public interface ShopUiRegistry {
        ShopSession getUISession(Player player);
    }

    public interface ShopDefaultUiTypes {
        ShopUiType getTradingUIType();
    }

    public interface ShopUiType {}

    public interface ShopSession {
        boolean isValid();

        boolean isUIActive();

        Player getPlayer();

        Shop getShopkeeper();

        ShopUiType getUIType();
    }
}
