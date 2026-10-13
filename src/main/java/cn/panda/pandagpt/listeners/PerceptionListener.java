package cn.panda.pandagpt.listeners;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.perception.SceneObjects;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import java.util.List;

/** Observe confirmed events. Coordinates only invalidate known observations; no world-wide event memory. */
public final class PerceptionListener implements Listener {
    private final ChatGPTIntegration plugin;
    public PerceptionListener(ChatGPTIntegration plugin) { this.plugin = plugin; }
    private void changed(Block b) {
        if (plugin.perception() != null) plugin.perception().changed(b.getWorld().getUID(), new SceneObjects.Pos(b.getX(), b.getY(), b.getZ()));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void place(BlockPlaceEvent e) { changed(e.getBlock()); plugin.perception().busy(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void breakBlock(BlockBreakEvent e) { changed(e.getBlock()); plugin.perception().busy(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void interact(PlayerInteractEvent e) {
        if (e.getClickedBlock() != null && e.getAction() == Action.RIGHT_CLICK_BLOCK && e.useInteractedBlock() != Event.Result.DENY) {
            changed(e.getClickedBlock());
            plugin.perception().interacted(e.getPlayer(), e.getClickedBlock());
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void damage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p) plugin.perception().busy(p); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void entityExplosion(EntityExplodeEvent e) { e.blockList().forEach(this::changed); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void blockExplosion(BlockExplodeEvent e) { e.blockList().forEach(this::changed); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void extend(BlockPistonExtendEvent e) { moved(e.getBlocks(), e.getDirection()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void retract(BlockPistonRetractEvent e) { moved(e.getBlocks(), e.getDirection().getOppositeFace()); }
    @EventHandler(priority = EventPriority.MONITOR) public void redstone(BlockRedstoneEvent e) { if (e.getOldCurrent() != e.getNewCurrent()) changed(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void flow(BlockFromToEvent e) {
        Block b = e.getBlock(); var d = e.getFace();
        plugin.perception().changed(b.getWorld().getUID(), new SceneObjects.Pos(b.getX() + d.getModX(), b.getY() + d.getModY(), b.getZ() + d.getModZ()));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void burn(BlockBurnEvent e) { changed(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void entityBlock(EntityChangeBlockEvent e) { changed(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void furnaceBurn(FurnaceBurnEvent e) { changed(e.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void furnaceSmelt(FurnaceSmeltEvent e) { changed(e.getBlock()); }
    private void moved(List<Block> blocks, org.bukkit.block.BlockFace direction) {
        for (Block b : blocks) { changed(b);
            plugin.perception().changed(b.getWorld().getUID(), new SceneObjects.Pos(b.getX() + direction.getModX(), b.getY() + direction.getModY(), b.getZ() + direction.getModZ()));
        }
    }
}
