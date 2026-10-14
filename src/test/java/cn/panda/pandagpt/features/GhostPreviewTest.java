package cn.panda.pandagpt.features;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

class GhostPreviewTest {
    private static GhostPreview.Part part(String shape, int[] from, int[] to, int radius, boolean hollow) {
        return new GhostPreview.Part(shape, Material.STONE, from, to, radius, 3, hollow);
    }

    @Test
    void shapesProduceTheExpectedNumberOfBlocks() {
        int[] o = {0, 0, 0}, c = {2, 2, 2};
        assertEquals(27, GhostPreview.shape(part("fill", o, c, 0, false), 1000).size());
        assertEquals(
                26, GhostPreview.shape(part("hollow", o, c, 0, false), 1000).size());
        assertEquals(24, GhostPreview.shape(part("walls", o, c, 0, false), 1000).size());
        assertEquals(
                5,
                GhostPreview.shape(part("line", o, new int[] {4, 0, 0}, 0, false), 1000)
                        .size());
        assertEquals(
                1 + 9,
                GhostPreview.shape(part("pyramid", o, null, 1, false), 1000).size());
        assertTrue(GhostPreview.shape(part("sphere", o, null, 3, true), 1000).size()
                < GhostPreview.shape(part("sphere", o, null, 3, false), 1000).size());
        assertEquals(
                10,
                GhostPreview.shape(part("fill", o, new int[] {50, 50, 50}, 0, false), 10)
                        .size(),
                "the budget caps huge shapes");
        assertThrows(IllegalArgumentException.class, () -> GhostPreview.shape(part("spiral", o, c, 0, false), 10));
    }

    @Test
    void previewIsSentOnlyToThePlayerFollowsTheirFacingAndIsRestored() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        Block block = mock(Block.class);
        when(block.getBlockData()).thenReturn(mock(BlockData.class));
        when(world.getBlockAt(any(Location.class))).thenReturn(block);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        // 面朝西（偏航 90）：前方是 -X。
        when(player.getLocation()).thenReturn(new Location(world, 10.5, 64, 20.5, 90, 0));
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack stone = mock(ItemStack.class);
        when(stone.getType()).thenReturn(Material.STONE);
        when(stone.getAmount()).thenReturn(1);
        when(inventory.getStorageContents()).thenReturn(new ItemStack[] {stone});
        when(player.getInventory()).thenReturn(inventory);

        var preview = new GhostPreview(material -> mock(BlockData.class));
        var result = preview.show(
                player,
                List.of(
                        new GhostPreview.Part("block", Material.STONE, new int[] {0, 0, 1}, null, 0, 1, false),
                        new GhostPreview.Part("block", Material.GLASS, new int[] {0, 1, 1}, null, 0, 1, false)),
                false,
                100,
                Long.MAX_VALUE);
        assertEquals(2, result.shown());
        assertEquals(java.util.Map.of("GLASS", 1), result.missing());
        // 前方 2 格的原点再往前 1 格：x = 10 - 3 = 7。
        verify(player).sendBlockChange(eq(new Location(world, 7, 64, 20)), any(BlockData.class));
        verify(player, never()).sendBlockChange(eq(new Location(world, 13, 64, 20)), any(BlockData.class));
        assertEquals(1, preview.active());
        assertTrue(preview.clear(player));
        verify(player, times(4)).sendBlockChange(any(Location.class), any(BlockData.class));
        assertFalse(preview.clear(player));
    }
}
