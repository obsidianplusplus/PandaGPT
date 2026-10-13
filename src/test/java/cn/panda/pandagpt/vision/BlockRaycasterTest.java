package cn.panda.pandagpt.vision;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlockRaycasterTest {
    private final Camera camera = new Camera(new Camera.Vec3(.5, 64.5, .5), 0, 0, 3, 3, 70);
    @Test void neverReadsOrLoadsUnsentOrUnloadedChunks() {
        World world = mock(World.class);
        RaySample result = new BlockRaycaster(world, Set.of()).trace(camera, camera.forward(), 96);
        assertEquals(RaySample.Kind.UNSENT_OR_UNLOADED, result.kind());
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        when(world.isChunkLoaded(0, 0)).thenReturn(false);
        result = new BlockRaycaster(world, Set.of(BlockRaycaster.chunkKey(0, 0))).trace(camera, camera.forward(), 96);
        assertEquals(RaySample.Kind.UNSENT_OR_UNLOADED, result.kind());
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }
    @Test void choosesTheNearestBlockAndCopiesSurfaceData() {
        World world = mock(World.class); Block air = mock(Block.class), wall = mock(Block.class); BlockData data = mock(BlockData.class);
        when(world.isChunkLoaded(0, 0)).thenReturn(true);
        when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        Material airType = spy(Material.AIR), stoneType = spy(Material.STONE);
        doReturn(true).when(airType).isAir(); doReturn(false).when(stoneType).isAir();
        when(air.getType()).thenReturn(airType); when(wall.getType()).thenReturn(stoneType);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(air);
        when(world.getBlockAt(0, 64, 2)).thenReturn(wall);
        when(wall.rayTrace(any(Location.class), any(Vector.class), anyDouble(), eq(FluidCollisionMode.ALWAYS)))
                .thenReturn(new RayTraceResult(new Vector(.5, 64.5, 2), wall, BlockFace.NORTH));
        when(wall.getBlockData()).thenReturn(data); when(data.getMapColor()).thenReturn(Color.fromRGB(0x888888));
        when(data.getAsString()).thenReturn("minecraft:stone"); when(wall.getLightLevel()).thenReturn((byte) 12);
        RaySample result = new BlockRaycaster(world, Set.of(BlockRaycaster.chunkKey(0, 0))).trace(camera, camera.forward(), 8);
        assertEquals(RaySample.Kind.HIT, result.kind()); assertEquals(1.5, result.distance(), 1e-12);
        assertEquals("STONE", result.material()); assertEquals("NORTH", result.face());
        verify(world, never()).getBlockAt(0, 64, 3);
    }
    @Test void chunkKeysMatchPaperForNegativeCoordinates() {
        for (int x : new int[]{-5, 0, 7}) for (int z : new int[]{-3, 0, 9})
            assertEquals(Chunk.getChunkKey(x, z), BlockRaycaster.chunkKey(x, z));
    }
}
