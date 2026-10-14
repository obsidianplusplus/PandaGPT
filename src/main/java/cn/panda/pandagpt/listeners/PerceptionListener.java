package cn.panda.pandagpt.listeners;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.perception.PerceptionController;
import cn.panda.pandagpt.perception.SceneObjects;
import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * 监听已确定发生的方块变化与玩家活动。
 *
 * <p>坐标只用于使已知的观察与记忆失效，不记录全世界的事件历史。线程：服务器主线程，MONITOR 优先级只读不改。
 */
public final class PerceptionListener implements Listener {
    private final ChatGPTIntegration plugin;

    public PerceptionListener(ChatGPTIntegration plugin) {
        this.plugin = plugin;
    }

    // 重载交接或停用期间感知模块可能为 null，所有入口统一判空。
    private PerceptionController perception() {
        return plugin.perception();
    }

    private void changed(Block b) {
        changed(b, 0, 0, 0);
    }

    private void changed(Block b, int dx, int dy, int dz) {
        PerceptionController p = perception();
        if (p != null) {
            p.changed(b.getWorld().getUID(), new SceneObjects.Pos(b.getX() + dx, b.getY() + dy, b.getZ() + dz));
        }
    }

    private void busy(Player player) {
        PerceptionController p = perception();
        if (p != null) {
            p.busy(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        changed(e.getBlock());
        busy(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent e) {
        changed(e.getBlock());
        busy(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void interact(PlayerInteractEvent e) {
        PerceptionController p = perception();
        if (p != null
                && e.getClickedBlock() != null
                && e.getAction() == Action.RIGHT_CLICK_BLOCK
                && e.useInteractedBlock() != Event.Result.DENY) {
            changed(e.getClickedBlock());
            p.interacted(e.getPlayer(), e.getClickedBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void damage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player player) {
            busy(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityExplosion(EntityExplodeEvent e) {
        e.blockList().forEach(this::changed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void blockExplosion(BlockExplodeEvent e) {
        e.blockList().forEach(this::changed);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void extend(BlockPistonExtendEvent e) {
        moved(e.getBlocks(), e.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void retract(BlockPistonRetractEvent e) {
        moved(e.getBlocks(), e.getDirection().getOppositeFace());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void redstone(BlockRedstoneEvent e) {
        if (e.getOldCurrent() != e.getNewCurrent()) {
            changed(e.getBlock());
        }
    }

    // 流体事件报告的是源方块，实际变化发生在流向的相邻格子。
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void flow(BlockFromToEvent e) {
        BlockFace d = e.getFace();
        changed(e.getBlock(), d.getModX(), d.getModY(), d.getModZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void burn(BlockBurnEvent e) {
        changed(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityBlock(EntityChangeBlockEvent e) {
        changed(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void furnaceBurn(FurnaceBurnEvent e) {
        changed(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void furnaceSmelt(FurnaceSmeltEvent e) {
        changed(e.getBlock());
    }

    // 睡床或使用重生锚时自动记住家，玩家不需要输入任何命令。
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void spawnSet(PlayerSetSpawnEvent e) {
        PerceptionController p = perception();
        Location location = e.getLocation();
        if (p == null
                || location == null
                || location.getWorld() == null
                || (e.getCause() != PlayerSetSpawnEvent.Cause.BED
                        && e.getCause() != PlayerSetSpawnEvent.Cause.RESPAWN_ANCHOR)) {
            return;
        }
        p.respawnSet(
                e.getPlayer(),
                location.getWorld().getUID(),
                new SceneObjects.Pos(location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    // 活塞移动同时改变原位置与目标位置。
    private void moved(List<Block> blocks, BlockFace direction) {
        for (Block b : blocks) {
            changed(b);
            changed(b, direction.getModX(), direction.getModY(), direction.getModZ());
        }
    }
}
