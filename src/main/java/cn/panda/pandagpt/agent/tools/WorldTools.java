package cn.panda.pandagpt.agent.tools;

import static cn.panda.pandagpt.agent.Schema.integer;
import static cn.panda.pandagpt.agent.Schema.object;
import static cn.panda.pandagpt.agent.Schema.string;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.Schema;
import cn.panda.pandagpt.agent.ToolResult;
import cn.panda.pandagpt.awareness.CommandAwareness;
import cn.panda.pandagpt.util.PlayerContext;
import java.util.List;
import java.util.stream.Collectors;

/** “survival”“server-guide”技能的工具：环境、附近生物与服务器资料检索。 */
public final class WorldTools {
    public static List<AgentTool> create(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.of(
                        "nearby_entities",
                        "Living entities near the player, monsters first then by distance.",
                        object(integer("radius", "Search radius in blocks, 4 to the configured maximum", false)),
                        p -> plugin.settings().environmentEnabled(),
                        ToolSupport.activity("nearby"),
                        (context, args) -> {
                            int max = plugin.settings().radius();
                            int radius = Math.clamp(Schema.number(args, "radius", max), 4, max);
                            return ToolResult.ok(PlayerContext.nearby(
                                    context.player(),
                                    radius,
                                    Math.max(1, plugin.settings().maxEntities())));
                        }),
                AgentTool.of(
                        "world_conditions",
                        "Time of day, weather, light level at the player's feet, biome and difficulty.",
                        object(),
                        p -> plugin.settings().environmentEnabled(),
                        null,
                        (context, args) -> ToolResult.ok(PlayerContext.conditions(context.player()))),
                AgentTool.of(
                        "search_server_knowledge",
                        "Search the server's own documentation (rules, economy, claims, teleports, plugins). Always "
                                + "use this before answering questions about this server; never guess server rules.",
                        object(string("query", "Keywords in the documentation's language", true)),
                        p -> plugin.knowledge() != null,
                        ToolSupport.activity("knowledge"),
                        (context, args) -> {
                            var hits = plugin.knowledge().search(Schema.text(args, "query"), 4, 4000);
                            return ToolResult.ok(
                                    hits.isEmpty()
                                            ? "No matching section. Available headings: "
                                                    + plugin.knowledge().outline()
                                            : hits.stream()
                                                    .map(h -> "## " + h.heading() + "\n" + h.text())
                                                    .collect(Collectors.joining("\n\n")));
                        }),
                AgentTool.of(
                        "list_server_commands",
                        "Plugin commands this player has permission to use, with descriptions.",
                        object(),
                        p -> plugin.settings().commandAwareness(),
                        null,
                        (context, args) ->
                                ToolResult.ok(CommandAwareness.context(context.player(), plugin.settings()))));
    }

    private WorldTools() {}
}
