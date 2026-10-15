package cn.panda.pandagpt.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.AgentTurn;
import cn.panda.pandagpt.agent.ToolContext;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class BentoBoxAdapterTest {
    private final UUID caller = UUID.randomUUID();
    private final NativeBentoBox nativePlugin = mock(NativeBentoBox.class);
    private final NativeIslands manager = mock(NativeIslands.class);
    private final NativeWorlds worlds = mock(NativeWorlds.class);
    private final Player player = mock(Player.class);
    private final World world = mock(World.class);

    private List<AgentTool> tools() throws Exception {
        when(nativePlugin.isEnabled()).thenReturn(true);
        when(nativePlugin.getIslands()).thenReturn(manager);
        when(nativePlugin.getIWM()).thenReturn(worlds);
        when(player.getUniqueId()).thenReturn(caller);
        when(player.hasPermission("pandagpt.use")).thenReturn(true);
        when(player.hasPermission("bskyblock.island.info")).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, 10, 65, 10));
        when(world.getName()).thenReturn("skyworld");
        when(worlds.inWorld(world)).thenReturn(true);
        when(worlds.getPermissionPrefix(world)).thenReturn("bskyblock.");
        var api = new BentoBoxAdapter.Api(
                NativeBentoBox.class, NativeIslands.class, NativeWorlds.class, NativeIsland.class);
        return new BentoBoxAdapter(nativePlugin, api).tools(mock(ChatGPTIntegration.class));
    }

    private NativeIsland island(boolean team) {
        NativeIsland island = mock(NativeIsland.class);
        when(island.getWorld()).thenReturn(world);
        when(island.getName()).thenReturn("§aMy sky island");
        when(island.getGameMode()).thenReturn("BSkyBlock");
        when(island.getProtectionRange()).thenReturn(100);
        when(island.inTeam(caller)).thenReturn(team);
        when(island.getRank(caller)).thenReturn(team ? 1000 : 0);
        when(island.getOwner()).thenReturn(caller);
        when(island.getCenter()).thenReturn(new Location(world, 1234, 66, 5678));
        return island;
    }

    private String call(AgentTool tool) throws Exception {
        return tool.call(
                        new ToolContext(player, new AgentTurn(caller, AgentTurn.Kind.CONVERSATION), "zh_CN"),
                        new JsonObject())
                .get()
                .content();
    }

    @Test
    void ownIslandQueryIsUuidScopedAndAppliesPerWorldPermissionsAndMembership() throws Exception {
        AgentTool tool = tools().getFirst();
        NativeIsland own = island(true);
        NativeIsland foreign = island(false);
        NativeIsland deleted = island(true);
        when(deleted.isDeleted()).thenReturn(true);
        NativeIsland hidden = island(true);
        World privateWorld = mock(World.class);
        when(hidden.getWorld()).thenReturn(privateWorld);
        when(worlds.inWorld(privateWorld)).thenReturn(true);
        when(worlds.getPermissionPrefix(privateWorld)).thenReturn("aoneblock.");
        when(manager.getIslands(caller)).thenReturn(List.of(own, foreign, deleted, hidden));
        String result = call(tool);
        assertTrue(tool.independentRead());
        assertTrue(result.contains("My sky island"));
        assertTrue(result.contains("center: 1234,66,5678"));
        assertTrue(result.contains("protection range: 100"));
        assertTrue(result.contains("were withheld"));
        verify(manager).getIslands(caller);
        verify(manager, never()).getIslands();
        verify(foreign, never()).getCenter();
        verify(hidden, never()).getName();
        verify(deleted, never()).getName();
    }

    @Test
    void visitorGetsOnlyPublicContextWithoutTeamCenterOrOwnerDetails() throws Exception {
        AgentTool tool = tools().get(1);
        NativeIsland foreign = island(false);
        when(manager.getIslandAt(any())).thenReturn(Optional.of(foreign));
        String result = call(tool);
        assertTrue(result.contains("game mode: BSkyBlock"));
        assertTrue(result.contains("Visitor view"));
        assertFalse(result.contains("1234"));
        assertFalse(result.contains("owner:"));
        verify(foreign, never()).getCenter();
        verify(foreign, never()).getOwner();
        verify(foreign, never()).getMembers();
    }

    @Test
    void revokingNativePermissionOrDisablingPluginStopsReadsAfterToolExposure() throws Exception {
        AgentTool tool = tools().get(1);
        assertTrue(tool.available(player));
        when(player.hasPermission("bskyblock.island.info")).thenReturn(false);
        assertFalse(tool.available(player));
        assertTrue(call(tool).startsWith("ERROR:"));
        verify(manager, never()).getIslandAt(any());
        when(player.hasPermission("bskyblock.island.info")).thenReturn(true);
        when(nativePlugin.isEnabled()).thenReturn(false);
        assertFalse(tool.available(player));
        assertTrue(call(tool).startsWith("ERROR:"));
    }

    @Test
    void unmanagedWorldAndEmptyOrDeletedCurrentIslandAreHandled() throws Exception {
        AgentTool tool = tools().get(1);
        when(worlds.inWorld(world)).thenReturn(false);
        assertFalse(tool.available(player));
        assertTrue(call(tool).startsWith("ERROR:"));
        when(worlds.inWorld(world)).thenReturn(true);
        when(manager.getIslandAt(any())).thenReturn(Optional.empty());
        assertTrue(call(tool).contains("No active BentoBox island"));
        NativeIsland deleted = island(true);
        when(deleted.isDeleted()).thenReturn(true);
        when(manager.getIslandAt(any())).thenReturn(Optional.of(deleted));
        assertTrue(call(tool).contains("No active BentoBox island"));
        verify(deleted, never()).getCenter();
    }

    @Test
    void canonicalIslandWorldPermissionIsRechecked() throws Exception {
        AgentTool tool = tools().get(1);
        NativeIsland hidden = island(false);
        World canonical = mock(World.class);
        when(hidden.getWorld()).thenReturn(canonical);
        when(worlds.inWorld(canonical)).thenReturn(true);
        when(worlds.getPermissionPrefix(canonical)).thenReturn("aoneblock.");
        when(manager.getIslandAt(any())).thenReturn(Optional.of(hidden));
        assertTrue(call(tool).startsWith("ERROR:"));
        verify(hidden, never()).getName();
    }

    @Test
    void bothOwnIslandOutputAndMembershipScanAreBounded() throws Exception {
        AgentTool tool = tools().getFirst();
        NativeIsland own = island(true);
        when(manager.getIslands(caller)).thenReturn(Collections.nCopies(1000, own));
        assertTrue(call(tool).contains("More islands omitted"));
        verify(own, times(BentoBoxAdapter.MAX_ISLANDS)).getName();
        NativeIsland foreign = island(false);
        when(manager.getIslands(caller)).thenReturn(Collections.nCopies(1000, foreign));
        assertTrue(call(tool).contains("scan/output limit"));
        verify(foreign, times(BentoBoxAdapter.MAX_SCAN)).inTeam(caller);
        verify(foreign, never()).getName();
    }

    @Test
    void incompatibleMethodsFailActivationContract() {
        assertThrows(
                NoSuchMethodException.class,
                () -> new BentoBoxAdapter.Api(
                        Plugin.class, NativeIslands.class, NativeWorlds.class, NativeIsland.class));
    }

    // Fixtures use the exact native method shapes without defining upstream package classes.
    public interface NativeBentoBox extends Plugin {
        NativeIslands getIslands();

        NativeWorlds getIWM();
    }

    public interface NativeIslands {
        List<NativeIsland> getIslands(UUID uuid);

        Collection<NativeIsland> getIslands();

        Optional<NativeIsland> getIslandAt(Location location);
    }

    public interface NativeWorlds {
        boolean inWorld(World world);

        String getPermissionPrefix(World world);
    }

    public interface NativeIsland {
        World getWorld();

        String getName();

        String getGameMode();

        int getProtectionRange();

        int getRank(UUID uuid);

        boolean isSpawn();

        boolean isDeleted();

        boolean inTeam(UUID uuid);

        UUID getOwner();

        Location getCenter();

        java.util.Map<UUID, Integer> getMembers();
    }
}
