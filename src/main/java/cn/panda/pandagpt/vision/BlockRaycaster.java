package cn.panda.pandagpt.vision;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Runs only on the server thread. Never asks World.rayTraceBlocks to load distant chunks. */
public final class BlockRaycaster {
    private final World world;
    private final Set<Long> sentChunks;
    private final int minHeight, maxHeight;
    private record Pos(int x, int y, int z) {}
    private static final class Cell {
        final Block block; final Material material; final boolean air;
        Surface surface;
        Cell(Block block) { this.block = block; material = block.getType(); air = material.isAir(); }
    }
    private record Surface(String state, int color, int light, RaySample.Tint tint) {}
    private Surface surface(Cell cell) {
        if (cell.surface == null) {
            var block = cell.block; var data = block.getBlockData(); var biome = block.getBiome();
            cell.surface = new Surface(data.getAsString(), data.getMapColor().asRGB(), block.getLightLevel(),
                    new RaySample.Tint(block.getTemperature(), block.getHumidity(), biome == null ? "minecraft:plains" : biome.getKey().toString()));
        }
        return cell.surface;
    }
    private final Map<Pos, Cell> cells = new HashMap<>();
    private final Map<Long, Boolean> chunks = new HashMap<>();
    private long cacheTick = Long.MIN_VALUE;
    private long reads, cacheHits;
    public BlockRaycaster(World world, Set<Long> sentChunks) {
        this.world = world; this.sentChunks = Set.copyOf(sentChunks);
        minHeight = world.getMinHeight(); maxHeight = world.getMaxHeight();
    }
    /** Cache lifetime is exactly one server tick; never retain mutable block state across ticks. */
    public void beginTick(long tick) {
        if (cacheTick != tick) { cells.clear(); chunks.clear(); cacheTick = tick; }
    }
    public long reads() { return reads; }
    public long cacheHits() { return cacheHits; }
    private boolean accessible(int x, int z) {
        long key = chunkKey(x >> 4, z >> 4);
        return sentChunks.contains(key) && chunks.computeIfAbsent(key, k -> world.isChunkLoaded(x >> 4, z >> 4));
    }
    private Cell cell(int x, int y, int z) {
        Pos pos = new Pos(x, y, z); Cell cached = cells.get(pos);
        if (cached != null) { cacheHits++; return cached; }
        Block block = world.getBlockAt(x, y, z); reads++;
        Cell value = new Cell(block);
        if (cells.size() < 32768) cells.put(pos, value);
        return value;
    }
    public final class Trace {
        private final Camera camera;
        private final Location start;
        private final Vector vector;
        private final int range;
        private final VoxelMarcher.Cursor cursor;
        private RaySample sample = RaySample.empty(RaySample.Kind.NO_HIT_WITHIN_RANGE);
        private Trace(Camera camera, Camera.Vec3 direction, int range) {
            this.camera = camera; this.range = range;
            start = new Location(world, camera.origin().x(), camera.origin().y(), camera.origin().z());
            vector = new Vector(direction.x(), direction.y(), direction.z());
            cursor = new VoxelMarcher.Cursor(camera.origin(), direction, range);
        }
        /** null means suspended, never unknown or empty sky. Bukkit access stays on the caller's main thread. */
        public RaySample advance(BooleanSupplier permitCell) {
            return cursor.advance(permitCell, this::visit) ? sample : null;
        }
        private boolean visit(int x, int y, int z, double entry, double exit) {
            if (!accessible(x, z)) {
                sample = RaySample.empty(RaySample.Kind.UNSENT_OR_UNLOADED); return false;
            }
            if (y < minHeight || y >= maxHeight) return true;
            Cell cell = cell(x, y, z); Block block = cell.block;
            if (cell.air) return true;
            var hit = block.rayTrace(start, vector, range, FluidCollisionMode.ALWAYS);
            if (hit == null) return true;
            var point = hit.getHitPosition();
            Camera.Vec3 position = new Camera.Vec3(point.getX(), point.getY(), point.getZ());
            double distance = camera.origin().distance(position);
            // Only the hit within this traversed cell counts; partial shapes can be missed.
            if (distance + 1e-6 < entry || distance - 1e-6 > exit) return true;
            Surface surface = surface(cell);
            int light = surface.light();
            var face = hit.getHitBlockFace();
            if (face != null) {
                int nx = x + face.getModX(), ny = y + face.getModY(), nz = z + face.getModZ();
                if (ny >= minHeight && ny < maxHeight
                        && accessible(nx, nz))
                    light = Math.max(light, cell(nx, ny, nz).block.getLightLevel());
            }
            sample = new RaySample(RaySample.Kind.HIT, distance, cell.material.name(), surface.state(),
                    hit.getHitBlockFace() == null ? "UNKNOWN" : hit.getHitBlockFace().name(), surface.color(),
                    light, position, x, y, z, surface.tint());
            return false;
        }
    }
    public Trace begin(Camera camera, Camera.Vec3 direction, int range) { return new Trace(camera, direction, range); }
    /** Unbudgeted convenience for small tests; production sampling uses Trace.advance. */
    public RaySample trace(Camera camera, Camera.Vec3 direction, int range) {
        cells.clear(); chunks.clear();
        return begin(camera, direction, range).advance(() -> true);
    }
    public static long chunkKey(int x, int z) { return (x & 0xffffffffL) | ((z & 0xffffffffL) << 32); }
}
