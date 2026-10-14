package cn.panda.pandagpt.vision;

import java.util.*;
import java.util.function.BooleanSupplier;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

/** 方块射线检测。线程：仅限服务器主线程；只读取玩家已接收且已加载的区块，绝不触发远处区块加载。 */
public final class BlockRaycaster {
    private final World world;
    private final Set<Long> sentChunks;
    private final int minHeight, maxHeight;

    private record Pos(int x, int y, int z) {}

    private static final class Cell {
        final Block block;
        final Material material;
        final boolean air;
        Surface surface;

        Cell(Block block) {
            this.block = block;
            material = block.getType();
            air = material.isAir();
        }
    }

    private record Surface(String state, int color, int light, RaySample.Tint tint) {}

    private Surface surface(Cell cell) {
        if (cell.surface == null) {
            var block = cell.block;
            var data = block.getBlockData();
            var biome = block.getBiome();
            cell.surface = new Surface(
                    data.getAsString(),
                    data.getMapColor().asRGB(),
                    block.getLightLevel(),
                    new RaySample.Tint(
                            block.getTemperature(),
                            block.getHumidity(),
                            biome == null ? "minecraft:plains" : biome.getKey().toString()));
        }
        return cell.surface;
    }

    private final Map<Pos, Cell> cells = new HashMap<>();
    private final Map<Long, Boolean> chunks = new HashMap<>();
    private long cacheTick = Long.MIN_VALUE;
    private long reads, cacheHits;

    public BlockRaycaster(World world, Set<Long> sentChunks) {
        this.world = world;
        this.sentChunks = Set.copyOf(sentChunks);
        minHeight = world.getMinHeight();
        maxHeight = world.getMaxHeight();
    }
    /** 缓存寿命恰好一个 tick，绝不跨 tick 保留可变的方块状态。 */
    public void beginTick(long tick) {
        if (cacheTick != tick) {
            cells.clear();
            chunks.clear();
            cacheTick = tick;
        }
    }

    public long reads() {
        return reads;
    }

    public long cacheHits() {
        return cacheHits;
    }

    private boolean accessible(int x, int z) {
        long key = chunkKey(x >> 4, z >> 4);
        return sentChunks.contains(key) && chunks.computeIfAbsent(key, k -> world.isChunkLoaded(x >> 4, z >> 4));
    }

    private Cell cell(int x, int y, int z) {
        Pos pos = new Pos(x, y, z);
        Cell cached = cells.get(pos);
        if (cached != null) {
            cacheHits++;
            return cached;
        }
        Block block = world.getBlockAt(x, y, z);
        reads++;
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
            this.camera = camera;
            this.range = range;
            start = new Location(
                    world,
                    camera.origin().x(),
                    camera.origin().y(),
                    camera.origin().z());
            vector = new Vector(direction.x(), direction.y(), direction.z());
            cursor = new VoxelMarcher.Cursor(camera.origin(), direction, range);
        }
        /** 返回 null 表示因预算暂停，而不是未知或天空；Bukkit 访问始终发生在调用方所在的主线程。 */
        public RaySample advance(BooleanSupplier permitCell) {
            return cursor.advance(permitCell, this::visit) ? sample : null;
        }

        private boolean visit(int x, int y, int z, double entry, double exit) {
            if (!accessible(x, z)) {
                sample = RaySample.empty(RaySample.Kind.UNSENT_OR_UNLOADED);
                return false;
            }
            if (y < minHeight || y >= maxHeight) return true;
            Cell cell = cell(x, y, z);
            Block block = cell.block;
            if (cell.air) return true;
            var hit = block.rayTrace(start, vector, range, FluidCollisionMode.ALWAYS);
            if (hit == null) return true;
            var point = hit.getHitPosition();
            Camera.Vec3 position = new Camera.Vec3(point.getX(), point.getY(), point.getZ());
            double distance = camera.origin().distance(position);
            // 只接受落在当前遍历格子内的命中；部分形状的方块可能因此漏检。
            if (distance + 1e-6 < entry || distance - 1e-6 > exit) return true;
            Surface surface = surface(cell);
            int light = surface.light();
            var face = hit.getHitBlockFace();
            if (face != null) {
                int nx = x + face.getModX(), ny = y + face.getModY(), nz = z + face.getModZ();
                if (ny >= minHeight && ny < maxHeight && accessible(nx, nz))
                    light = Math.max(light, cell(nx, ny, nz).block.getLightLevel());
            }
            sample = new RaySample(
                    RaySample.Kind.HIT,
                    distance,
                    cell.material.name(),
                    surface.state(),
                    hit.getHitBlockFace() == null
                            ? "UNKNOWN"
                            : hit.getHitBlockFace().name(),
                    surface.color(),
                    light,
                    position,
                    x,
                    y,
                    z,
                    surface.tint());
            return false;
        }
    }

    public Trace begin(Camera camera, Camera.Vec3 direction, int range) {
        return new Trace(camera, direction, range);
    }
    /** 不受预算限制的便捷方法，仅供小型测试使用；正式采样使用 Trace.advance。 */
    public RaySample trace(Camera camera, Camera.Vec3 direction, int range) {
        cells.clear();
        chunks.clear();
        return begin(camera, direction, range).advance(() -> true);
    }

    public static long chunkKey(int x, int z) {
        return (x & 0xffffffffL) | ((z & 0xffffffffL) << 32);
    }
}
