package cn.panda.pandagpt.integration;

import static cn.panda.pandagpt.agent.Schema.object;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.ToolResult;
import java.util.List;
import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.land.Area;
import me.angeschossen.lands.api.land.Land;

/** Lands：脚下的领地、玩家是否受信任及其角色，以及玩家拥有或可编辑的领地。 */
public final class LandsAdapter implements Integration {
    private LandsIntegration api;

    @Override
    public String id() {
        return "lands";
    }

    // Lands 要求以调用方插件身份创建接口实例，只创建一次。
    private LandsIntegration api(ChatGPTIntegration plugin) {
        if (api == null) {
            api = LandsIntegration.of(plugin);
        }
        return api;
    }

    @Override
    public List<AgentTool> tools(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.of(
                        "lands_here",
                        "The Lands land at the player's position, its owner, and whether the player is trusted there "
                                + "and with which role.",
                        object(),
                        p -> true,
                        null,
                        (context, args) -> {
                            Area area = api(plugin).getArea(context.player().getLocation());
                            if (area == null) {
                                return ToolResult.ok("Wilderness: no land here.");
                            }
                            Land land = area.getLand();
                            var id = context.player().getUniqueId();
                            var role = area.getRole(id);
                            return ToolResult.ok("land: " + land.getName() + ", area: " + area.getName() + ", owner: "
                                    + nameOf(context.player().getServer(), land.getOwnerUID())
                                    + ", you are "
                                    + (land.getOwnerUID().equals(id)
                                            ? "the OWNER"
                                            : area.isTrusted(id) ? "trusted" : "not trusted")
                                    + (role == null ? "" : ", role " + role.getName()));
                        }),
                AgentTool.of(
                        "my_lands",
                        "The land the player owns and the land they currently edit.",
                        object(),
                        p -> true,
                        null,
                        (context, args) -> {
                            var landPlayer =
                                    api(plugin).getLandPlayer(context.player().getUniqueId());
                            if (landPlayer == null) {
                                return ToolResult.error("Lands has not loaded this player yet.");
                            }
                            Land owning = landPlayer.getOwningLand();
                            Land editing = landPlayer.getEditLand(false);
                            return ToolResult.ok("owns: "
                                    + (owning == null
                                            ? "none"
                                            : owning.getName() + " (" + owning.getSize() + " chunks)")
                                    + "\nediting: " + (editing == null ? "none" : editing.getName()));
                        }));
    }

    private static String nameOf(org.bukkit.Server server, java.util.UUID id) {
        var player = server.getOfflinePlayer(id);
        return player.getName() == null ? id.toString() : player.getName();
    }
}
