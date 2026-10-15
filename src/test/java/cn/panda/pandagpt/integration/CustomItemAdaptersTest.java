package cn.panda.pandagpt.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.AgentTurn;
import cn.panda.pandagpt.agent.ToolContext;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import javax.tools.ToolProvider;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CustomItemAdaptersTest {
    private static final String ITEMSADDER_CLASS = "dev.lone.itemsadder.api.CustomStack";
    private static final String ORAXEN_CLASS = "io.th0rgal.oraxen.api.OraxenItems";

    // Compile only documented signatures into isolated plugin class loaders: no vendor API class
    // is placed on the application class path, so missing/incompatible APIs are testable too.
    private static final String ITEMSADDER_API = """
            package dev.lone.itemsadder.api;
            import org.bukkit.inventory.ItemStack;
            public class CustomStack {
                public static ItemStack lastStack;
                public static boolean vanilla, fail, noAttributes;
                public static CustomStack byItemStack(ItemStack stack) {
                    lastStack = stack;
                    if (fail) throw new IllegalStateException("content is loading");
                    return vanilla ? null : new CustomStack();
                }
                public String getNamespacedID() { return "survival:ruby_boots"; }
                public String getDisplayName() { return "Ruby Boots"; }
                public boolean isBlock() { return false; }
                public boolean hasCustomDurability() { return !noAttributes; }
                public int getDurability() { return 73; }
                public int getMaxDurability() { return 100; }
                public boolean hasUsagesAttribute() { return !noAttributes; }
                public int getUsages() { return 5; }
            }
            """;

    private static final String ORAXEN_API = """
            package io.th0rgal.oraxen.api;
            import org.bukkit.inventory.ItemStack;
            public class OraxenItems {
                public static ItemStack lastStack;
                public static boolean vanilla, fail;
                public static String getIdByItem(ItemStack stack) {
                    lastStack = stack;
                    if (fail) throw new IllegalStateException("content is loading");
                    return vanilla ? null : "ruby_sword";
                }
            }
            """;

    @TempDir
    Path temp;

    @Test
    void toolsAreIndependentCurrentPlayerReadsAndCanBeDeclaredWithoutVendorApis() {
        for (Integration adapter : List.of(new ItemsAdderAdapter(), new OraxenAdapter())) {
            AgentTool tool = adapter.tools(mock(ChatGPTIntegration.class)).getFirst();
            assertTrue(tool.independentRead());
            assertFalse(tool.parameters().get("additionalProperties").getAsBoolean());
            assertEquals(
                    List.of("slot"),
                    tool.parameters().getAsJsonObject("properties").keySet().stream()
                            .toList());
            assertEquals(
                    6,
                    tool.parameters()
                            .getAsJsonObject("properties")
                            .getAsJsonObject("slot")
                            .getAsJsonArray("enum")
                            .size());
        }
    }

    @Test
    void itemsAdderReadsOnlySelectedEquipmentSnapshotAndDeclaredAttributes() throws Exception {
        try (Fixture fixture = compile(ITEMSADDER_CLASS, ITEMSADDER_API)) {
            ItemsAdderAdapter adapter = new ItemsAdderAdapter();
            adapter.validate(fixture.plugin());
            Player player = player("ItemsAdder", fixture.plugin());
            ItemStack original = mock(ItemStack.class);
            ItemStack snapshot = stack(Material.DIAMOND_BOOTS);
            Material type = snapshot.getType();
            when(original.getType()).thenReturn(type);
            when(original.getAmount()).thenReturn(1);
            when(original.clone()).thenReturn(snapshot);
            when(player.getInventory().getBoots()).thenReturn(original);

            AgentTool tool = tool(adapter);
            String result = call(tool, player, "boots");
            assertTrue(result.contains("own boots"));
            assertTrue(result.contains("namespaced_id: survival:ruby_boots"));
            assertTrue(result.contains("configured display name: Ruby Boots"));
            assertTrue(result.contains("custom durability: 73/100"));
            assertTrue(result.contains("remaining uses: 5"));
            assertSame(snapshot, fixture.api().getField("lastStack").get(null));
            verify(player.getInventory(), never()).getItemInMainHand();
            verify(original, never()).setAmount(anyInt());
            verify(original).clone();

            fixture.api().getField("noAttributes").set(null, true);
            String withoutAttributes = call(tool, player, "boots");
            assertFalse(withoutAttributes.contains("custom durability:"));
            assertFalse(withoutAttributes.contains("remaining uses:"));
        }
    }

    @Test
    void oraxenReturnsExactIdAndBoundsMetadataFromActualOwnHand() throws Exception {
        try (Fixture fixture = compile(ORAXEN_CLASS, ORAXEN_API)) {
            OraxenAdapter adapter = new OraxenAdapter();
            adapter.validate(fixture.plugin());
            Player player = player("Oraxen", fixture.plugin());
            ItemStack original = mock(ItemStack.class);
            ItemStack snapshot = stack(Material.DIAMOND_SWORD);
            Material type = snapshot.getType();
            when(original.getType()).thenReturn(type);
            when(original.getAmount()).thenReturn(1);
            when(original.clone()).thenReturn(snapshot);
            when(player.getInventory().getItemInOffHand()).thenReturn(original);
            ItemMeta meta = mock(ItemMeta.class);
            when(snapshot.getItemMeta()).thenReturn(meta);
            when(meta.hasDisplayName()).thenReturn(true);
            when(meta.displayName()).thenReturn(Component.text("Player-renamed sword"));
            when(meta.hasItemName()).thenReturn(true);
            when(meta.itemName()).thenReturn(Component.text("Ruby Sword"));
            when(meta.lore())
                    .thenReturn(List.of(
                            Component.text("x".repeat(10000)),
                            Component.text("second\nline"),
                            Component.text("3"),
                            Component.text("4"),
                            Component.text("5"),
                            Component.text("6"),
                            Component.text("private seventh lore")));

            String result = call(tool(adapter), player, "offhand");
            assertTrue(result.contains("item_id: ruby_sword"));
            assertFalse(result.contains("oraxen:ruby_sword"));
            assertTrue(result.contains("stack display name: Player-renamed sword"));
            assertTrue(result.contains("stack item name: Ruby Sword"));
            assertTrue(result.contains("lore: second line"));
            assertTrue(result.contains("[additional lore omitted]"));
            assertFalse(result.contains("private seventh lore"));
            assertTrue(result.length() < 2500);
            assertSame(snapshot, fixture.api().getField("lastStack").get(null));
            verify(player.getInventory(), never()).getItemInMainHand();
            verify(original, never()).setItemMeta(any());
        }
    }

    @Test
    void emptyAndUnrecognizedSlotsDoNotInventCustomItems() throws Exception {
        for (boolean itemsAdder : List.of(true, false)) {
            String pluginName = itemsAdder ? "ItemsAdder" : "Oraxen";
            try (Fixture fixture =
                    compile(itemsAdder ? ITEMSADDER_CLASS : ORAXEN_CLASS, itemsAdder ? ITEMSADDER_API : ORAXEN_API)) {
                Integration adapter = itemsAdder ? new ItemsAdderAdapter() : new OraxenAdapter();
                adapter.validate(fixture.plugin());
                Player player = player(pluginName, fixture.plugin());
                AgentTool tool = tool(adapter);
                assertTrue(call(tool, player, "helmet").contains("empty"));
                assertNull(fixture.api().getField("lastStack").get(null));

                ItemStack held = stack(Material.STONE);
                when(held.clone()).thenReturn(held);
                when(player.getInventory().getItemInMainHand()).thenReturn(held);
                fixture.api().getField("vanilla").set(null, true);
                assertTrue(call(tool, player, null).contains("not identified by " + pluginName));
            }
        }
    }

    @Test
    void invalidSlotsAndDisabledPluginsCannotReadEquipmentOrInvokeVendorApis() throws Exception {
        for (Integration adapter : List.of(new ItemsAdderAdapter(), new OraxenAdapter())) {
            Player player = player(adapter.id().equals("itemsadder") ? "ItemsAdder" : "Oraxen", mock(Plugin.class));
            AgentTool tool = tool(adapter);
            assertFalse(tool.available(player));
            assertTrue(call(tool, player, "mainhand").startsWith("ERROR:"));
            verify(player, never()).getInventory();
        }
        try (Fixture fixture = compile(ORAXEN_CLASS, ORAXEN_API)) {
            Player player = player("Oraxen", fixture.plugin());
            AgentTool tool = tool(new OraxenAdapter());
            assertTrue(call(tool, player, "inventory_of_another_player").startsWith("ERROR:"));
            JsonObject args = new JsonObject();
            args.add("slot", new JsonObject());
            assertTrue(tool.call(context(player), args).join().content().startsWith("ERROR:"));
            verify(player, never()).getInventory();
            assertNull(fixture.api().getField("lastStack").get(null));
        }
    }

    @Test
    void validationRejectsMissingAndIncorrectApiSignatures() throws Exception {
        try (Fixture missing = compile("fixture.EmptyApi", "package fixture; public class EmptyApi {}")) {
            assertThrows(ClassNotFoundException.class, () -> new ItemsAdderAdapter().validate(missing.plugin()));
            assertThrows(ClassNotFoundException.class, () -> new OraxenAdapter().validate(missing.plugin()));
        }
        try (Fixture changed =
                compile(ORAXEN_CLASS, ORAXEN_API.replace("static String getIdByItem", "String getIdByItem"))) {
            assertThrows(NoSuchMethodException.class, () -> new OraxenAdapter().validate(changed.plugin()));
        }
        try (Fixture changed = compile(
                ITEMSADDER_CLASS,
                ITEMSADDER_API.replace(
                        "String getNamespacedID() { return \"survival:ruby_boots\"; }",
                        "int getNamespacedID() { return 1; }"))) {
            assertThrows(NoSuchMethodException.class, () -> new ItemsAdderAdapter().validate(changed.plugin()));
        }
    }

    @Test
    void capabilityValidationDoesNotInitializeVendorApiAndRuntimeFailureIsContained() throws Exception {
        String loadingApi = ORAXEN_API.replace(
                "public class OraxenItems {",
                "public class OraxenItems { static { if (System.nanoTime() != 0) throw new IllegalStateException(); }");
        try (Fixture fixture = compile(ORAXEN_CLASS, loadingApi)) {
            OraxenAdapter adapter = new OraxenAdapter();
            assertDoesNotThrow(() -> adapter.validate(fixture.plugin()));
            Player player = player("Oraxen", fixture.plugin());
            ItemStack held = stack(Material.STONE);
            when(held.clone()).thenReturn(held);
            when(player.getInventory().getItemInMainHand()).thenReturn(held);
            assertTrue(call(tool(adapter), player, null).startsWith("ERROR:"));
        }
        try (Fixture fixture = compile(ITEMSADDER_CLASS, ITEMSADDER_API)) {
            Player player = player("ItemsAdder", fixture.plugin());
            ItemStack held = stack(Material.STONE);
            when(held.clone()).thenReturn(held);
            when(player.getInventory().getItemInMainHand()).thenReturn(held);
            fixture.api().getField("fail").set(null, true);
            assertTrue(call(tool(new ItemsAdderAdapter()), player, null).startsWith("ERROR:"));
        }
    }

    private AgentTool tool(Integration adapter) {
        return adapter.tools(mock(ChatGPTIntegration.class)).getFirst();
    }

    private String call(AgentTool tool, Player player, String slot) {
        JsonObject args = new JsonObject();
        if (slot != null) {
            args.addProperty("slot", slot);
        }
        return tool.call(context(player), args).join().content();
    }

    private ToolContext context(Player player) {
        return new ToolContext(player, new AgentTurn(player.getUniqueId(), AgentTurn.Kind.CONVERSATION), "zh_CN");
    }

    private Player player(String name, Plugin installed) {
        Player player = mock(Player.class);
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(player.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        when(manager.getPlugin(name)).thenReturn(installed);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getInventory()).thenReturn(mock(PlayerInventory.class));
        return player;
    }

    private ItemStack stack(Material material) {
        ItemStack stack = mock(ItemStack.class);
        Material type = spy(material);
        doReturn(false).when(type).isAir();
        when(stack.getType()).thenReturn(type);
        when(stack.getAmount()).thenReturn(1);
        return stack;
    }

    private Fixture compile(String name, String source) throws Exception {
        Path directory = Files.createTempDirectory(temp, "plugin-api-");
        Path sourceFile = directory.resolve(name.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Fixture compilation requires the project JDK");
        assertEquals(
                0,
                compiler.run(
                        null,
                        null,
                        null,
                        "-classpath",
                        System.getProperty("java.class.path"),
                        "-d",
                        directory.toString(),
                        sourceFile.toString()));
        URLClassLoader loader = new URLClassLoader(
                new java.net.URL[] {directory.toUri().toURL()}, getClass().getClassLoader());
        Plugin plugin =
                (Plugin) Proxy.newProxyInstance(loader, new Class<?>[] {Plugin.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "isEnabled" -> true;
                        case "toString", "getName" -> "fixture";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
        return new Fixture(loader, plugin, Class.forName(name, false, loader));
    }

    private record Fixture(URLClassLoader loader, Plugin plugin, Class<?> api) implements AutoCloseable {
        public void close() throws IOException {
            loader.close();
        }
    }
}
