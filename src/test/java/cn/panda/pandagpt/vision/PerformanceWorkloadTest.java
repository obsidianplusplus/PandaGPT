package cn.panda.pandagpt.vision;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.panda.pandagpt.performance.TickBudget;
import com.google.gson.GsonBuilder;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

/** 确定性的 API 工作量基准；刻意不做虚假的 TPS 或挂钟时间基准。 */
class PerformanceWorkloadTest {
    record Fixture(World world, Set<Long> sent, AtomicInteger reads, AtomicInteger chunks) {}

    Fixture fixture() {
        World world = mock(World.class);
        Block air = mock(Block.class);
        Material type = spy(Material.AIR);
        doReturn(true).when(type).isAir();
        when(air.getType()).thenReturn(type);
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        var reads = new AtomicInteger();
        var chunks = new AtomicInteger();
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(i -> {
            reads.incrementAndGet();
            return air;
        });
        when(world.isChunkLoaded(anyInt(), anyInt())).thenAnswer(i -> {
            chunks.incrementAndGet();
            return true;
        });
        Set<Long> sent = new HashSet<>();
        for (int z = 0; z <= 6; z++) sent.add(BlockRaycaster.chunkKey(0, z));
        return new Fixture(world, sent, reads, chunks);
    }

    @Test
    void repeatedLongRaysReuseWorldReadsAndCannotExceedCellQuota() throws Exception {
        var camera = new Camera(new Camera.Vec3(.5, 64.5, .5), 0, 0, 160, 90, 70);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
                "workload",
                "256 identical forward rays, 96 blocks, all air, sent+loaded chunks; deterministic calls, not live TPS");
        String baseline = System.getProperty("pandagpt.benchmark.baseline");
        if (baseline != null) {
            var old = fixture();
            try (var loader = new URLClassLoader(
                    new java.net.URL[] {Path.of(baseline).toUri().toURL()},
                    getClass().getClassLoader())) {
                var type = loader.loadClass("cn.panda.pandagpt.vision.baseline.BlockRaycaster");
                Object caster = type.getConstructor(World.class, Set.class).newInstance(old.world(), old.sent());
                var trace = type.getMethod("trace", Camera.class, Camera.Vec3.class, int.class);
                for (int n = 0; n < 256; n++)
                    assertEquals(
                            RaySample.Kind.NO_HIT_WITHIN_RANGE,
                            ((RaySample) trace.invoke(caster, camera, camera.forward(), 96)).kind());
            }
            assertEquals(24832, old.reads().get());
            assertEquals(24832, old.chunks().get());
            result.put(
                    "baseline",
                    Map.of(
                            "block_reads",
                            old.reads().get(),
                            "chunk_checks",
                            old.chunks().get(),
                            "completed_rays",
                            256));
        }
        var f = fixture();
        var caster = new BlockRaycaster(f.world(), f.sent());
        caster.beginTick(1);
        var budget = new TickBudget(1, 32768, true, () -> 0L);
        for (int n = 0; n < 256; n++)
            assertEquals(
                    RaySample.Kind.NO_HIT_WITHIN_RANGE,
                    caster.begin(camera, camera.forward(), 96)
                            .advance(budget::cell)
                            .kind());
        assertEquals(97, f.reads().get());
        assertEquals(7, f.chunks().get());
        assertEquals(24832, budget.cells());
        result.put(
                "cached_same_work",
                Map.of(
                        "block_reads",
                        f.reads().get(),
                        "chunk_checks",
                        f.chunks().get(),
                        "visited_cells",
                        budget.cells(),
                        "completed_rays",
                        256));
        caster.beginTick(2);
        var limited = new TickBudget(1, 8192, true, () -> 0L);
        int completed = 0;
        while (limited.available()) {
            RaySample sample = caster.begin(camera, camera.forward(), 96).advance(limited::cell);
            if (sample != null) {
                completed++;
                assertEquals(RaySample.Kind.NO_HIT_WITHIN_RANGE, sample.kind());
            }
        }
        assertEquals(8192, limited.cells());
        assertEquals(84, completed);
        result.put(
                "default_cell_cap",
                Map.of(
                        "visited_cells",
                        limited.cells(),
                        "completed_rays",
                        completed,
                        "partial_ray_is_suspended",
                        true));
        // 发散的透视射线之间重叠远少于理想的重复射线情形。
        var wide = new Camera(camera.origin(), 0, 0, 16, 16, 70);
        Fixture perspective = fixture();
        Set<Long> grid = new HashSet<>();
        for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) grid.add(BlockRaycaster.chunkKey(x, z));
        var fresh = new BlockRaycaster(perspective.world(), grid);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) fresh.trace(wide, wide.ray(x, y), 96);
        int uncached = perspective.reads().get(),
                uncachedChunks = perspective.chunks().get();
        if (baseline != null) {
            Fixture old = fixture();
            try (var loader = new URLClassLoader(
                    new java.net.URL[] {Path.of(baseline).toUri().toURL()},
                    getClass().getClassLoader())) {
                var type = loader.loadClass("cn.panda.pandagpt.vision.baseline.BlockRaycaster");
                Object oldCaster = type.getConstructor(World.class, Set.class).newInstance(old.world(), grid);
                var trace = type.getMethod("trace", Camera.class, Camera.Vec3.class, int.class);
                for (int y = 0; y < 16; y++)
                    for (int x = 0; x < 16; x++) trace.invoke(oldCaster, wide, wide.ray(x, y), 96);
            }
            assertEquals(uncached, old.reads().get());
            uncachedChunks = old.chunks().get();
        }
        perspective.reads().set(0);
        perspective.chunks().set(0);
        var cached = new BlockRaycaster(perspective.world(), grid);
        cached.beginTick(3);
        var unlimited = new TickBudget(1, 131072, true, () -> 0L);
        var projection = wide.projection();
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++)
                assertEquals(
                        RaySample.Kind.NO_HIT_WITHIN_RANGE,
                        cached.begin(wide, projection.ray(x, y), 96)
                                .advance(unlimited::cell)
                                .kind());
        assertTrue(perspective.reads().get() < uncached);
        result.put(
                "perspective_16x16_open_air",
                Map.of(
                        "baseline_block_reads",
                        uncached,
                        "baseline_chunk_checks",
                        uncachedChunks,
                        "cached_block_reads",
                        perspective.reads().get(),
                        "cached_chunk_checks",
                        perspective.chunks().get(),
                        "visited_cells",
                        unlimited.cells(),
                        "completed_rays",
                        256,
                        "note",
                        "Unlimited cell quota for equal-work comparison; production still uses 8192 cells and a real deadline."));
        Path report = Path.of("target/performance-results.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, new GsonBuilder().setPrettyPrinting().create().toJson(result));
    }
}
