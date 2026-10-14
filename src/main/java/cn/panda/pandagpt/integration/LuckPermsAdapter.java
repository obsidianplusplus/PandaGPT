package cn.panda.pandagpt.integration;

import static cn.panda.pandagpt.agent.Schema.object;
import static cn.panda.pandagpt.agent.Schema.string;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.Schema;
import cn.panda.pandagpt.agent.ToolResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.track.Track;

/** LuckPerms：玩家自己的权限组、继承、前后缀、元数据、临时权限与晋升路线。 */
public final class LuckPermsAdapter implements Integration {
    @Override
    public String id() {
        return "ranks";
    }

    @Override
    public List<AgentTool> tools(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.of(
                        "rank_info",
                        "The player's LuckPerms groups (direct and inherited, with expiry), prefix, suffix, meta values "
                                + "and temporary permissions.",
                        object(),
                        p -> true,
                        null,
                        (context, args) -> {
                            User user = LuckPermsProvider.get()
                                    .getUserManager()
                                    .getUser(context.player().getUniqueId());
                            if (user == null) {
                                return ToolResult.error("LuckPerms has not loaded this player yet.");
                            }
                            var meta = user.getCachedData().getMetaData();
                            String direct = user.getNodes(NodeType.INHERITANCE).stream()
                                    .map(node -> node.getGroupName() + expiry(node))
                                    .collect(Collectors.joining(", "));
                            String inherited = user.getInheritedGroups(user.getQueryOptions()).stream()
                                    .map(Group::getName)
                                    .collect(Collectors.joining(", "));
                            String temporary = user.getNodes(NodeType.PERMISSION).stream()
                                    .filter(Node::hasExpiry)
                                    .limit(15)
                                    .map(node -> node.getPermission() + expiry(node))
                                    .collect(Collectors.joining(", "));
                            String metaValues = meta.getMeta().entrySet().stream()
                                    .limit(20)
                                    .map(e -> e.getKey() + "=" + e.getValue())
                                    .collect(Collectors.joining(", "));
                            return ToolResult.ok("primary group: " + user.getPrimaryGroup()
                                    + "\ndirect groups: " + direct
                                    + "\nall inherited groups: " + inherited
                                    + "\nprefix: " + meta.getPrefix() + "\nsuffix: " + meta.getSuffix()
                                    + "\nmeta: " + (metaValues.isEmpty() ? "none" : metaValues)
                                    + "\ntemporary permissions: " + (temporary.isEmpty() ? "none" : temporary));
                        }),
                AgentTool.of(
                        "rank_tracks",
                        "Promotion tracks containing the player's groups and the next group on each track.",
                        object(),
                        p -> true,
                        null,
                        (context, args) -> {
                            var luckPerms = LuckPermsProvider.get();
                            User user = luckPerms
                                    .getUserManager()
                                    .getUser(context.player().getUniqueId());
                            if (user == null) {
                                return ToolResult.error("LuckPerms has not loaded this player yet.");
                            }
                            var groups = user.getNodes(NodeType.INHERITANCE).stream()
                                    .map(InheritanceNode::getGroupName)
                                    .collect(Collectors.toSet());
                            StringBuilder out = new StringBuilder();
                            for (Track track : luckPerms.getTrackManager().getLoadedTracks()) {
                                List<String> ladder = track.getGroups();
                                for (int i = 0; i < ladder.size(); i++) {
                                    if (groups.contains(ladder.get(i))) {
                                        out.append(track.getName())
                                                .append(": ")
                                                .append(String.join(" > ", ladder))
                                                .append("; current ")
                                                .append(ladder.get(i))
                                                .append(", next ")
                                                .append(i + 1 < ladder.size() ? ladder.get(i + 1) : "none (top)")
                                                .append('\n');
                                    }
                                }
                            }
                            return ToolResult.ok(
                                    out.isEmpty() ? "The player is on no promotion track." : out.toString());
                        }),
                AgentTool.of(
                        "check_permission",
                        "Whether the player has a specific permission node, e.g. to explain why a command is denied.",
                        object(string("node", "Permission node such as essentials.home.multiple", true)),
                        p -> true,
                        null,
                        (context, args) -> {
                            String node = Schema.text(args, "node");
                            if (node.isEmpty() || node.length() > 128) {
                                return ToolResult.error("Give a permission node.");
                            }
                            return ToolResult.ok(
                                    node + ": " + (context.player().hasPermission(node) ? "granted" : "not granted"));
                        }));
    }

    private static String expiry(Node node) {
        if (!node.hasExpiry() || node.getExpiry() == null) {
            return "";
        }
        Duration left = Duration.between(Instant.now(), node.getExpiry());
        return " (expires in " + Math.max(0, left.toHours()) + "h " + Math.max(0, left.toMinutesPart()) + "m)";
    }
}
