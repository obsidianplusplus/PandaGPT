package cn.panda.pandagpt.agent.tools;

import static cn.panda.pandagpt.agent.Schema.bool;
import static cn.panda.pandagpt.agent.Schema.choice;
import static cn.panda.pandagpt.agent.Schema.object;
import static cn.panda.pandagpt.agent.Schema.string;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.Schema;
import cn.panda.pandagpt.agent.ToolResult;
import java.util.List;

/** “places” 技能的工具：记住、忘记、列出与定位地点。 */
public final class PlaceTools {
    public static List<AgentTool> create(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.of(
                        "remember_place",
                        "Remember a named place for this player. Use target=looking_at for the object at the centre of "
                                + "the latest observation (call look first if needed), here for the player's feet. "
                                + "is_home marks the player's home and replaces any previous home.",
                        object(
                                string("name", "Short place name in the player's language", true),
                                choice("target", "What to record", false, "here", "looking_at"),
                                bool("is_home", "True when the player says this is their home or base")),
                        p -> ToolSupport.perception(plugin),
                        ToolSupport.activity("memory"),
                        (context, args) -> ToolSupport.result(plugin.perception()
                                .rememberPlace(
                                        context.player(),
                                        Schema.text(args, "name"),
                                        "looking_at".equals(Schema.text(args, "target")),
                                        Schema.flag(args, "is_home")))),
                AgentTool.async(
                        "forget_place",
                        "Preview forgetting a place by exact name. Show affected evidence and confirmation phrase. "
                                + "Use token only when the current player's complete message equals that phrase; preview alone never deletes.",
                        object(
                                string("name", "Exact name from list_places", true),
                                string("token", "One-use preview token; omit to preview", false)),
                        p -> ToolSupport.perception(plugin),
                        null,
                        (context, args) -> MemoryTools.forgetLegacy(plugin, context, args, true)),
                AgentTool.readOnlyOf(
                        "list_places",
                        "List the player's remembered places in the current world with distances and whether they "
                                + "changed since being recorded.",
                        object(),
                        p -> ToolSupport.perception(plugin),
                        ToolSupport.activity("memory"),
                        (context, args) -> ToolResult.ok(plugin.perception().listPlaces(context.player()))),
                AgentTool.readOnlyOf(
                        "locate_place",
                        "Direction and distance from the player to a remembered place, including which way to turn. "
                                + "Use name 'home' for the player's home.",
                        object(string("name", "Place name, or 'home'", true)),
                        p -> ToolSupport.perception(plugin),
                        ToolSupport.activity("locate"),
                        (context, args) -> ToolResult.ok(
                                plugin.perception().locatePlace(context.player(), Schema.text(args, "name")))));
    }

    private PlaceTools() {}
}
