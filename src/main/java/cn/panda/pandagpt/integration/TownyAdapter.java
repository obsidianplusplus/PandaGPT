package cn.panda.pandagpt.integration;

import static cn.panda.pandagpt.agent.Schema.object;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.ToolResult;
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.TownBlock;
import java.util.List;

/** Towny：玩家所属城镇与国家、职位、城镇银行，以及脚下地块的归属与出售状态。 */
public final class TownyAdapter implements Integration {
    @Override
    public String id() {
        return "towny";
    }

    @Override
    public List<AgentTool> tools(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.of(
                        "towny_status",
                        "The player's Towny town and nation, ranks, mayor/king status, town size, PvP and bank balance.",
                        object(),
                        p -> true,
                        null,
                        (context, args) -> {
                            Resident resident = TownyAPI.getInstance().getResident(context.player());
                            if (resident == null) {
                                return ToolResult.error("Towny has no resident record for this player yet.");
                            }
                            Town town = resident.getTownOrNull();
                            if (town == null) {
                                return ToolResult.ok(
                                        "The player is not in a town. They can join one or found a new town.");
                            }
                            var nation = resident.getNationOrNull();
                            String bank;
                            try {
                                bank = town.getAccount().getHoldingFormattedBalance();
                            } catch (RuntimeException e) {
                                bank = "unavailable";
                            }
                            return ToolResult.ok("town: " + town.getName() + " (mayor "
                                    + town.getMayor().getName() + ", "
                                    + town.getNumResidents() + " residents, "
                                    + town.getTownBlocks().size() + "/"
                                    + town.getMaxTownBlocksAsAString() + " plots, pvp " + town.isPVP() + ", bank "
                                    + bank + ")"
                                    + "\nnation: " + (nation == null ? "none" : nation.getName())
                                    + "\nranks: " + resident.getTownRanks() + " " + resident.getNationRanks()
                                    + (resident.isMayor() ? "\nThe player is the mayor." : "")
                                    + (resident.isKing() ? "\nThe player is the nation leader." : ""));
                        }),
                AgentTool.of(
                        "towny_plot_here",
                        "Who owns the Towny plot under the player, its type and whether it is for sale.",
                        object(),
                        p -> true,
                        null,
                        (context, args) -> {
                            var api = TownyAPI.getInstance();
                            var location = context.player().getLocation();
                            if (api.isWilderness(location)) {
                                return ToolResult.ok("Wilderness: not part of any town.");
                            }
                            TownBlock block = api.getTownBlock(location);
                            if (block == null) {
                                return ToolResult.ok("Wilderness: not part of any town.");
                            }
                            Town town = block.getTownOrNull();
                            Resident owner = block.getResidentOrNull();
                            return ToolResult.ok(
                                    "town: " + (town == null ? "unknown" : town.getName()) + ", plot type: "
                                            + block.getType().getName() + ", owner: "
                                            + (owner == null ? "the town" : owner.getName())
                                            + (block.isForSale() ? ", FOR SALE at " + block.getPlotPrice() : ""));
                        }));
    }
}
