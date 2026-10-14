package cn.panda.pandagpt.integration;

import static cn.panda.pandagpt.agent.Schema.object;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.ToolResult;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import java.util.List;
import java.util.stream.Collectors;

/** WorldGuard：玩家所在区域、成员身份、区域标志以及是否能在此建造。 */
public final class WorldGuardAdapter implements Integration {
    @Override
    public String id() {
        return "regions";
    }

    @Override
    public List<AgentTool> tools(ChatGPTIntegration plugin) {
        return List.of(AgentTool.of(
                "regions_here",
                "WorldGuard regions at the player's position: ids, priority, whether the player is owner or member, "
                        + "notable flags, and whether they can build, use PvP or be attacked by mobs here.",
                object(),
                p -> true,
                null,
                (context, args) -> {
                    var player = context.player();
                    var local = WorldGuardPlugin.inst().wrapPlayer(player);
                    var platform = WorldGuard.getInstance().getPlatform();
                    var query = platform.getRegionContainer().createQuery();
                    var location = BukkitAdapter.adapt(player.getLocation());
                    ApplicableRegionSet set = query.getApplicableRegions(location);
                    StringBuilder out = new StringBuilder();
                    for (ProtectedRegion region : set) {
                        out.append(region.getId())
                                .append(" priority ")
                                .append(region.getPriority())
                                .append(
                                        region.isOwner(local)
                                                ? ", you are OWNER"
                                                : region.isMember(local) ? ", you are MEMBER" : "")
                                .append(", flags: ")
                                .append(region.getFlags().entrySet().stream()
                                        .limit(12)
                                        .map(e -> e.getKey().getName() + "=" + e.getValue())
                                        .collect(Collectors.joining(", ")))
                                .append('\n');
                    }
                    boolean bypass = platform.getSessionManager().hasBypass(local, local.getWorld());
                    out.append(set.size() == 0 ? "No region here (global rules apply).\n" : "")
                            .append("can build: ")
                            .append(bypass || query.testState(location, local, Flags.BUILD))
                            .append(", pvp allowed: ")
                            .append(query.testState(location, local, Flags.PVP))
                            .append(", mob spawning: ")
                            .append(query.testState(location, local, Flags.MOB_SPAWNING))
                            .append(bypass ? " (player has region bypass)" : "");
                    return ToolResult.ok(out.toString());
                }));
    }
}
