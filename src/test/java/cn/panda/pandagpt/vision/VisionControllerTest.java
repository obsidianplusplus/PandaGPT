package cn.panda.pandagpt.vision;

import cn.panda.pandagpt.*;
import cn.panda.pandagpt.config.Settings;
import cn.panda.pandagpt.service.AssistantService;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VisionControllerTest {
    @TempDir Path folder;
    private record Fixture(ChatGPTIntegration plugin, Player player, World world, Settings settings) {}
    private Fixture fixture() {
        ChatGPTIntegration plugin = mock(ChatGPTIntegration.class); Server server = mock(Server.class);
        Player player = mock(Player.class); World world = mock(World.class);
        var config = TestSupport.config();
        config.set("panda.vision.width", 16); config.set("panda.vision.height", 16);
        config.set("panda.vision.rays_per_tick", 16); config.set("panda.vision.time_budget_ms", 5);
        config.set("panda.vision.textures.enabled", false); // These lifecycle tests exercise the explicit map-color mode.
        Settings settings = Settings.load(config);
        when(plugin.settings()).thenReturn(settings); when(plugin.getServer()).thenReturn(server);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.getWorld()).thenReturn(world);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(server.getPlayer(player.getUniqueId())).thenReturn(player);
        when(player.isOnline()).thenReturn(true); when(player.hasPermission(anyString())).thenReturn(true);
        when(player.getEyeLocation()).thenReturn(new Location(world, .5, 64.5, .5));
        when(player.getClientViewDistance()).thenReturn(8); when(player.getSendViewDistance()).thenReturn(8);
        when(player.getSentChunkKeys()).thenReturn(Set.of()); // Every ray terminates unknown before world reads.
        return new Fixture(plugin, player, world, settings);
    }
    @Test void defaultTextureModeRefusesCaptureWhenAssetsAreMissing() {
        Fixture f = fixture();
        var config = TestSupport.config();
        config.set("panda.vision.textures.enabled", true);
        var settings = Settings.load(config);
        try (var vision = new VisionController(f.plugin, settings.vision(), mock(AssistantService.class), folder)) {
            vision.capture(f.player, null, false);
            assertTrue(vision.status().contains("captures=0"));
            assertTrue(vision.status().contains("assets_present=false"));
            verify(f.player, never()).getEyeLocation();
            verify(f.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        }
    }
    @Test void captureNeedsPermissionAndMovementCancelsTheJob() {
        Fixture f = fixture();
        try (var vision = new VisionController(f.plugin, f.settings.vision(), mock(AssistantService.class), folder)) {
            when(f.player.hasPermission("pandagpt.vision")).thenReturn(false);
            vision.capture(f.player, null, false);
            assertTrue(vision.status().contains("captures=0"));
            when(f.player.hasPermission("pandagpt.vision")).thenReturn(true);
            vision.capture(f.player, null, false); assertTrue(vision.status().contains("captures=1"));
            when(f.player.getEyeLocation()).thenReturn(new Location(f.world, 10, 64.5, .5));
            vision.tick(); assertTrue(vision.status().contains("captures=0"));
            verify(f.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        }
    }
    @Test void perTickQuotaIsSharedAndCancelPreventsFutureWorldReads() {
        Fixture f = fixture();
        when(f.player.getSentChunkKeys()).thenReturn(Set.of(BlockRaycaster.chunkKey(0, 0)));
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID()); when(other.getWorld()).thenReturn(f.world);
        when(other.isOnline()).thenReturn(true); when(other.hasPermission(anyString())).thenReturn(true);
        when(other.getEyeLocation()).thenReturn(new Location(f.world, .5, 64.5, .5));
        when(other.getClientViewDistance()).thenReturn(8); when(other.getSendViewDistance()).thenReturn(8);
        when(other.getSentChunkKeys()).thenReturn(Set.of(BlockRaycaster.chunkKey(0, 0)));
        when(f.plugin.getServer().getPlayer(other.getUniqueId())).thenReturn(other);
        try (var vision = new VisionController(f.plugin, f.settings.vision(), mock(AssistantService.class), folder)) {
            vision.capture(f.player, null, false); vision.capture(other, null, false); vision.tick();
            assertTrue(vision.status().contains("captures=2"));
            verify(f.world, atMost(16)).isChunkLoaded(0, 0); // Shared 16-ray budget, not 16 per player.
            vision.cancel(f.player.getUniqueId()); vision.cancel(other.getUniqueId()); vision.tick();
            assertTrue(vision.status().contains("captures=0"));
            verify(f.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        }
    }
    @Test void offlineSnapshotCompletesAndSavesAlignedImagesWithoutCallingTheModel() throws Exception {
        Fixture f = fixture(); var assistant = mock(AssistantService.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(f.plugin.getServer().getScheduler()).thenReturn(scheduler);
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        when(scheduler.runTask(eq(f.plugin), any(Runnable.class))).thenAnswer(invocation -> {
            callbacks.add(invocation.getArgument(1)); return mock(BukkitTask.class);
        });
        try (var vision = new VisionController(f.plugin, f.settings.vision(), assistant, folder)) {
            vision.capture(f.player, null, false);
            for (int i = 0; i < 60 && callbacks.isEmpty(); i++) vision.tick();
            Runnable complete = callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(complete);
            complete.run();
            assertTrue(vision.status().contains("captures=0"));
            var capture = folder.resolve("captures").resolve(f.player.getUniqueId().toString());
            for (String name : List.of("color.png", "depth.png", "labels.png", "metadata.json"))
                assertTrue(java.nio.file.Files.size(capture.resolve(name)) > 0);
            assertEquals(16, javax.imageio.ImageIO.read(capture.resolve("color.png").toFile()).getWidth());
            verify(assistant, never()).ask(any(Player.class), anyString(), anyString(), any());
        }
    }
}
