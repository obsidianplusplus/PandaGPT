package cn.panda.pandagpt.integration;

import static cn.panda.pandagpt.agent.Schema.object;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.ToolResult;
import java.util.List;
import java.util.stream.Collectors;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/** GriefPrevention：脚下领地的归属与玩家的信任等级，以及玩家自己的领地与可用领地块。 */
public final class GriefPreventionAdapter implements Integration {
    private static final List<ClaimPermission> LEVELS = List.of(
            ClaimPermission.Edit,
            ClaimPermission.Manage,
            ClaimPermission.Build,
            ClaimPermission.Inventory,
            ClaimPermission.Access);

    @Override
    public String id() {
        return "claims";
    }

    @Override
    public List<AgentTool> tools(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.of(
                        "claim_here",
                        "The GriefPrevention claim at the player's position: owner, size, corners and the player's "
                                + "highest permission there (Edit > Manage > Build > Inventory > Access).",
                        object(),
                        p -> true,
                        null,
                        (context, args) -> {
                            Player player = context.player();
                            Claim claim =
                                    GriefPrevention.instance.dataStore.getClaimAt(player.getLocation(), true, null);
                            if (claim == null) {
                                return ToolResult.ok("Wilderness: no claim here. The player can claim it with a golden "
                                        + "shovel if they have enough claim blocks.");
                            }
                            String level = LEVELS.stream()
                                    .filter(permission -> claim.checkPermission(player, permission, null) == null)
                                    .findFirst()
                                    .map(Enum::name)
                                    .orElse("none");
                            return ToolResult.ok(
                                    (claim.isAdminClaim() ? "Admin claim" : "Claim owned by " + claim.getOwnerName())
                                            + ", " + claim.getWidth() + "x" + claim.getHeight() + " (" + claim.getArea()
                                            + " blocks), "
                                            + corners(claim) + ", your permission: " + level);
                        }),
                AgentTool.of(
                        "my_claims",
                        "The player's own GriefPrevention claims and their accrued, bonus and remaining claim blocks.",
                        object(),
                        p -> true,
                        null,
                        (context, args) -> {
                            var data = GriefPrevention.instance.dataStore.getPlayerData(
                                    context.player().getUniqueId());
                            String claims = data.getClaims().stream()
                                    .limit(20)
                                    .map(c -> c.getLesserBoundaryCorner()
                                                    .getWorld()
                                                    .getName() + " " + corners(c) + " (" + c.getArea() + " blocks)")
                                    .collect(Collectors.joining("\n"));
                            return ToolResult.ok("claim blocks: accrued " + data.getAccruedClaimBlocks() + ", bonus "
                                    + data.getBonusClaimBlocks() + ", remaining " + data.getRemainingClaimBlocks()
                                    + "\nclaims:\n" + (claims.isEmpty() ? "none" : claims));
                        }));
    }

    private static String corners(Claim claim) {
        Location a = claim.getLesserBoundaryCorner();
        Location b = claim.getGreaterBoundaryCorner();
        return "from " + a.getBlockX() + "," + a.getBlockZ() + " to " + b.getBlockX() + "," + b.getBlockZ();
    }
}
