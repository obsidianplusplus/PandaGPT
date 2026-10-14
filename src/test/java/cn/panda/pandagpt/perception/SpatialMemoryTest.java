package cn.panda.pandagpt.perception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpatialMemoryTest {
    @TempDir Path folder;
    private SpatialMemory.Landmark landmark(UUID world, String name) {
        return new SpatialMemory.Landmark(name, world, new SceneObjects.Pos(-1, 64, 5), "minecraft:crafting_table", "explicit_player_mark", 1000, 1000);
    }
    @Test void blockEventStormDoesNotFillTheIoQueueOrInvalidateAnotherWorld() throws Exception {
        UUID player = UUID.randomUUID(), world = UUID.randomUUID(); List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (var memory = new SpatialMemory(folder, 2, errors::add)) {
            memory.remember(player, landmark(world, "table")); assertTrue(memory.drain(5000));
            var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
            memory.edit(player, data -> { entered.countDown(); try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { throw new AssertionError(e); } return data; });
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            memory.invalidate(UUID.randomUUID(), new SceneObjects.Pos(-1, 64, 5));
            assertEquals(1000, memory.view(player).landmarks().getFirst().verifiedAt());
            for (int i = 0; i < 10000; i++) memory.invalidate(world, new SceneObjects.Pos(-1, 64, 5));
            assertEquals(0, memory.view(player).landmarks().getFirst().verifiedAt());
            release.countDown(); assertTrue(memory.drain(5000)); assertTrue(errors.isEmpty(), errors.toString());
            assertSame(memory.view(player), memory.view(player)); // Clean reads reuse the immutable snapshot.
        }
    }
    @Test void aNewQueuedLandmarkIsInvalidatedBeforeItsFirstDiskWrite() throws Exception {
        UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            memory.view(player); assertTrue(memory.drain(5000));
            var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
            memory.edit(player, data -> { entered.countDown(); try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { throw new AssertionError(e); } return data; });
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(memory.remember(player, landmark(world, "new-table")));
            memory.invalidate(world, new SceneObjects.Pos(-1, 64, 5)); release.countDown(); assertTrue(memory.drain(5000));
            assertEquals(0, memory.view(player).landmarks().getFirst().verifiedAt());
        }
    }
    @Test void automaticDiscoveriesDoNotEvictExplicitPlayerLandmarks() throws Exception {
        UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            for (int i = 0; i < 32; i++) { memory.remember(player, landmark(world, "manual" + i)); assertTrue(memory.drain(5000)); }
            memory.remember(player, new SpatialMemory.Landmark("auto", world, new SceneObjects.Pos(3, 64, 3), "minecraft:furnace", "raycast_visible_surface", 2000, 2000));
            assertTrue(memory.drain(5000)); assertEquals(32, memory.view(player).landmarks().size());
            assertTrue(memory.view(player).landmarks().stream().allMatch(l -> l.source().equals("explicit_player_mark")));
        }
    }
    @Test void invalidationWinsOverAnInFlightMemoryEditAndReleasedSlotsCanBeReused() throws Exception {
        UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        try (var memory = new SpatialMemory(folder, 1, s -> fail(s))) {
            memory.remember(player, landmark(world, "table")); assertTrue(memory.drain(5000));
            var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
            memory.edit(player, data -> {
                entered.countDown();
                try { assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); }
                return new SpatialMemory.Data(data.landmarks(), "quiet", "", 1);
            });
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            memory.invalidate(world, new SceneObjects.Pos(-1, 64, 5)); assertEquals(0, memory.view(player).landmarks().getFirst().verifiedAt());
            release.countDown(); assertTrue(memory.drain(5000)); assertEquals(0, memory.view(player).landmarks().getFirst().verifiedAt());
            memory.release(player); assertTrue(memory.drain(5000));
            UUID other = UUID.randomUUID(); assertTrue(memory.remember(other, landmark(world, "other"))); assertTrue(memory.drain(5000));
            assertEquals(1, memory.view(other).landmarks().size());
        }
    }
    @Test void persistsPrivateLandmarksModesGoalsAndInvalidationAcrossRestarts() throws Exception {
        UUID player = UUID.randomUUID(), other = UUID.randomUUID(), world = UUID.randomUUID();
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            assertTrue(memory.remember(player, landmark(world, "工作台")));
            assertTrue(memory.edit(player, d -> new SpatialMemory.Data(d.landmarks(), "quiet", "IRON_PICKAXE", 2)));
            assertTrue(memory.drain(5000));
            assertEquals(1, memory.view(player).landmarks().size()); assertTrue(memory.view(other).landmarks().isEmpty());
            memory.invalidate(world, new SceneObjects.Pos(-1, 64, 5));
            assertEquals(0, memory.view(player).landmarks().getFirst().verifiedAt());
            assertTrue(memory.drain(5000));
        }
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            memory.view(player); assertTrue(memory.drain(5000));
            assertEquals("quiet", memory.view(player).mode()); assertEquals("IRON_PICKAXE", memory.view(player).goal());
            assertEquals(2, memory.view(player).quantity()); assertEquals(0, memory.view(player).landmarks().getFirst().verifiedAt());
            assertTrue(memory.context(player, UUID.randomUUID(), 3000).endsWith("[]"));
        }
    }
    @Test void corruptExistingFilesAreNeverOverwrittenAndPlayerCapacityIsBounded() throws Exception {
        UUID player = UUID.randomUUID(); Path file = folder.resolve(player + ".json"); Files.writeString(file, "broken-json");
        List<String> errors = new ArrayList<>();
        try (var memory = new SpatialMemory(folder, 1, errors::add)) {
            memory.remember(player, landmark(UUID.randomUUID(), "新地点")); assertTrue(memory.drain(5000));
            assertFalse(memory.ready(player)); assertEquals("broken-json", Files.readString(file));
            assertFalse(memory.remember(UUID.randomUUID(), landmark(UUID.randomUUID(), "overflow"))); assertFalse(errors.isEmpty());
        }
    }
    @Test void landmarkCountIsBoundedAndNamesReplaceOnlyWithinTheSameWorld() throws Exception {
        UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            for (int i = 0; i < 35; i++) { memory.remember(player, landmark(world, "p" + i)); assertTrue(memory.drain(5000)); }
            assertEquals(32, memory.view(player).landmarks().size());
            memory.remember(player, landmark(world, "p34")); assertTrue(memory.drain(5000)); assertEquals(32, memory.view(player).landmarks().size());
        }
    }
}
