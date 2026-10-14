package cn.panda.pandagpt.perception;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.panda.pandagpt.*;
import cn.panda.pandagpt.awareness.RecipeKnowledge;
import cn.panda.pandagpt.service.AssistantService;
import cn.panda.pandagpt.vision.*;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerceptionControllerTest {
    @TempDir
    Path folder;

    private record Fixture(
            ChatGPTIntegration plugin,
            Player player,
            World world,
            VisionController vision,
            SpatialMemory memory,
            PerceptionController perception)
            implements AutoCloseable {
        public void close() {
            perception.close();
            memory.close();
        }
    }

    private Fixture fixture() throws Exception {
        var plugin = mock(ChatGPTIntegration.class);
        var server = mock(Server.class);
        var player = mock(Player.class);
        var world = mock(World.class);
        var vision = mock(VisionController.class);
        var settings = TestSupport.settings();
        var inventory = mock(PlayerInventory.class);
        when(plugin.settings()).thenReturn(settings);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.service()).thenReturn(mock(AssistantService.class));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(server.getPlayer(player.getUniqueId())).thenReturn(player);
        doReturn(List.of(player)).when(server).getOnlinePlayers();
        when(player.getLocation()).thenReturn(new Location(world, .5, 64, .5));
        when(player.getEyeLocation()).thenReturn(new Location(world, .5, 65.6, .5));
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[0]);
        when(player.getSentChunkKeys()).thenReturn(Set.of(BlockRaycaster.chunkKey(0, 0)));
        var memory = new SpatialMemory(folder, 4, s -> fail(s));
        memory.view(player.getUniqueId());
        assertTrue(memory.drain(5000));
        var perception = new PerceptionController(plugin, settings.perception(), memory, mock(RecipeKnowledge.class));
        perception.attach(vision);
        return new Fixture(plugin, player, world, vision, memory, perception);
    }

    @Test
    void playerRotationDoesNotCopyOnlineCollectionAndPermissionCanBeRestored() throws Exception {
        try (Fixture f = fixture()) {
            clearInvocations(f.plugin.getServer());
            when(f.player.hasPermission("pandagpt.use")).thenReturn(false);
            for (int i = 0; i < 20; i++) f.perception.tick();
            verify(f.vision, never()).observe(any(Player.class));
            when(f.player.hasPermission("pandagpt.use")).thenReturn(true);
            for (int i = 0; i < 20; i++) f.perception.tick();
            assertTrue(f.memory.drain(5000));
            for (int i = 0; i < 20; i++) f.perception.tick();
            verify(f.vision, atLeastOnce()).observe(f.player);
            verify(f.plugin.getServer(), never()).getOnlinePlayers();
        }
    }

    @Test
    void quietModeStopsAmbientCaptureAndSurvivesControllerReload() throws Exception {
        try (Fixture f = fixture()) {
            f.perception.setAssistance(f.player, true);
            assertTrue(f.memory.drain(5000));
            for (int i = 0; i < 100; i++) f.perception.tick();
            verify(f.vision, never()).observe(any(Player.class));
            assertEquals("quiet", f.memory.view(f.player.getUniqueId()).mode());
            try (var replacement = new PerceptionController(
                    f.plugin, TestSupport.settings().perception(), f.memory, mock(RecipeKnowledge.class))) {
                replacement.attach(f.vision);
                for (int i = 0; i < 100; i++) replacement.tick();
                verify(f.vision, never()).observe(any(Player.class));
            }
        }
    }

    @Test
    void toolSelectionChecksCurrentWorldStateAndRejectsChangedCameraOrCoordinates() throws Exception {
        try (Fixture f = fixture()) {
            var camera = new Camera(new Camera.Vec3(.5, 65.6, .5), 0, 0, 1, 1, 70);
            RaySample sample = PerceptionFixtures.hit(0, 64, 4, "FURNACE");
            var observation = PerceptionFixtures.scene(
                    f.world.getUID(), System.currentTimeMillis(), camera, List.of(sample), List.of());
            f.perception.accept(f.player, observation);
            Block block = mock(Block.class);
            BlockData data = mock(BlockData.class);
            when(f.world.isChunkLoaded(0, 0)).thenReturn(true);
            when(f.world.getBlockAt(0, 64, 4)).thenReturn(block);
            when(block.getBlockData()).thenReturn(data);
            when(data.getAsString()).thenReturn("minecraft:furnace");
            assertTrue(f.perception.focusLatest(f.player, 1).startsWith("Verified #1 FURNACE"));
            verify(f.vision).mark(f.player, observation.surfaces().get(1), observation);
            assertTrue(f.perception.focusLatest(f.player, 2).startsWith("ERROR"));
            when(data.getAsString()).thenReturn("minecraft:stone");
            assertTrue(f.perception.focusLatest(f.player, 1).contains("block changed"));
            when(f.player.getEyeLocation()).thenReturn(new Location(f.world, .5, 65.6, .5, 90, 0));
            assertTrue(f.perception.focusLatest(f.player, 1).contains("moved or turned"));
            f.perception.changed(f.world.getUID(), new SceneObjects.Pos(0, 64, 4));
            assertNull(f.perception.latest(f.player));
        }
    }

    @Test
    void goalsUpdateOneStatusAndCompleteOnlyWhenInventoryContainsTarget() throws Exception {
        try (Fixture f = fixture()) {
            f.perception.setGoal(f.player, "DIAMOND", 2);
            verify(f.player, times(1)).showBossBar(any());
            for (int i = 0; i < 40; i++) f.perception.tick();
            verify(f.player, times(1)).showBossBar(any());
            ItemStack diamonds = mock(ItemStack.class);
            when(diamonds.getType()).thenReturn(Material.DIAMOND);
            when(diamonds.getAmount()).thenReturn(2);
            when(f.player.getInventory().getStorageContents()).thenReturn(new ItemStack[] {diamonds});
            for (int i = 0; i < 20; i++) f.perception.tick();
            assertTrue(f.memory.drain(5000));
            assertEquals("", f.memory.view(f.player.getUniqueId()).goal());
            assertTrue(f.perception.goalStatus(f.player).contains("持有目标数量"));
            verify(f.plugin.service(), never()).ask(any(Player.class), anyString());
        }
    }

    @Test
    void respawnPointBecomesHomeWithoutAnyCommandButNeverOverridesAnExplicitHome() throws Exception {
        try (Fixture f = fixture()) {
            UUID world = f.world.getUID();
            f.perception.respawnSet(f.player, world, new SceneObjects.Pos(10, 64, 10));
            assertTrue(f.memory.drain(5000));
            var homes = f.memory.view(f.player.getUniqueId()).landmarks();
            assertEquals(1, homes.size());
            assertEquals(SpatialMemory.AUTO_HOME_SOURCE, homes.getFirst().source());
            assertTrue(PerceptionController.isHome(homes.getFirst()));

            // 玩家亲口说“这里是我家”后替换自动记住的家，之后睡别的床也不再覆盖。
            f.perception.rememberPlace(f.player, "我的小屋", false, true);
            assertTrue(f.memory.drain(5000));
            f.perception.respawnSet(f.player, world, new SceneObjects.Pos(50, 70, 50));
            assertTrue(f.memory.drain(5000));
            homes = f.memory.view(f.player.getUniqueId()).landmarks();
            assertEquals(1, homes.size());
            assertEquals(SpatialMemory.HOME_SOURCE, homes.getFirst().source());
            assertEquals(new SceneObjects.Pos(0, 64, 0), homes.getFirst().position());
        }
    }

    @Test
    void intentApiSetsGoalsModesAndForgetsPlacesByName() throws Exception {
        try (Fixture f = fixture()) {
            assertEquals(
                    "intent.goal_invalid",
                    f.perception.setGoal(f.player, "not_an_item", 1).key());
            assertEquals(
                    "intent.goal_set", f.perception.setGoal(f.player, "铁镐", 1).key());
            assertTrue(f.memory.drain(5000));
            assertEquals("IRON_PICKAXE", f.memory.view(f.player.getUniqueId()).goal());
            assertEquals(
                    "intent.quiet", f.perception.setAssistance(f.player, true).key());
            assertTrue(f.memory.drain(5000));
            assertEquals("quiet", f.memory.view(f.player.getUniqueId()).mode());
            f.perception.rememberPlace(f.player, "Furnace", false, false);
            assertTrue(f.memory.drain(5000));
            assertEquals(
                    "intent.forgot",
                    f.perception.forgetPlace(f.player, "furnace").key());
            assertTrue(f.memory.drain(5000));
            assertTrue(f.memory.view(f.player.getUniqueId()).landmarks().isEmpty());
            assertEquals(
                    "intent.place_not_found",
                    f.perception.forgetPlace(f.player, "furnace").key());
        }
    }
}
