package cn.panda.pandagpt.vision;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import java.util.Set;

/** Runs only on the server thread. Never asks World.rayTraceBlocks to load distant chunks. */
public final class BlockRaycaster {
    private final World world;
    private final Set<Long> sentChunks;
    public BlockRaycaster(World world, Set<Long> sentChunks) { this.world = world; this.sentChunks = Set.copyOf(sentChunks); }
    public RaySample trace(Camera camera, Camera.Vec3 direction, int range) {
        Location start = new Location(world, camera.origin().x(), camera.origin().y(), camera.origin().z());
        Vector vector = new Vector(direction.x(), direction.y(), direction.z());
        RaySample[] sample = {RaySample.empty(RaySample.Kind.NO_HIT_WITHIN_RANGE)};
        VoxelMarcher.walk(camera.origin(), direction, range, (x, y, z, entry, exit) -> {
            int chunkX = x >> 4, chunkZ = z >> 4;
            if (!sentChunks.contains(chunkKey(chunkX, chunkZ)) || !world.isChunkLoaded(chunkX, chunkZ)) {
                sample[0] = RaySample.empty(RaySample.Kind.UNSENT_OR_UNLOADED); return false;
            }
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) return true;
            Block block = world.getBlockAt(x, y, z);
            if (block.getType().isAir()) return true;
            var hit = block.rayTrace(start, vector, range, FluidCollisionMode.ALWAYS);
            if (hit == null) return true;
            var point = hit.getHitPosition();
            Camera.Vec3 position = new Camera.Vec3(point.getX(), point.getY(), point.getZ());
            double distance = camera.origin().distance(position);
            // Only the hit within this traversed cell counts; partial shapes can be missed.
            if (distance + 1e-6 < entry || distance - 1e-6 > exit) return true;
            var data = block.getBlockData();
            var biome = block.getBiome();
            int light = block.getLightLevel();
            var face = hit.getHitBlockFace();
            if (face != null) {
                int nx = x + face.getModX(), ny = y + face.getModY(), nz = z + face.getModZ();
                if (ny >= world.getMinHeight() && ny < world.getMaxHeight()
                        && sentChunks.contains(chunkKey(nx >> 4, nz >> 4)) && world.isChunkLoaded(nx >> 4, nz >> 4))
                    light = Math.max(light, world.getBlockAt(nx, ny, nz).getLightLevel());
            }
            sample[0] = new RaySample(RaySample.Kind.HIT, distance, block.getType().name(), data.getAsString(),
                    hit.getHitBlockFace() == null ? "UNKNOWN" : hit.getHitBlockFace().name(), data.getMapColor().asRGB(),
                    light, position, x, y, z, new RaySample.Tint(block.getTemperature(), block.getHumidity(),
                    biome == null ? "minecraft:plains" : biome.getKey().toString()));
            return false;
        });
        return sample[0];
    }
    public static long chunkKey(int x, int z) { return (x & 0xffffffffL) | ((z & 0xffffffffL) << 32); }
}
