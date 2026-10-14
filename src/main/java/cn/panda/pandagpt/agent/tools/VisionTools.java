package cn.panda.pandagpt.agent.tools;

import static cn.panda.pandagpt.agent.Schema.bool;
import static cn.panda.pandagpt.agent.Schema.integer;
import static cn.panda.pandagpt.agent.Schema.object;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.Schema;
import cn.panda.pandagpt.agent.ToolResult;
import java.util.List;

/** “vision” 技能的工具：重建视角、核对并标记对象、回忆周围环境。 */
public final class VisionTools {
    public static List<AgentTool> create(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.async(
                        "look",
                        "Reconstruct what the player is looking at right now: a server-side raycast image with real "
                                + "textures, numbered surface regions, local collision checks and visible entities. Takes "
                                + "a few seconds; the player must hold still. Use whenever the question depends on what "
                                + "is in view ('this', 'that furnace', 'in front of me').",
                        object(bool("show_hits", "Show sampled ray hits to the player as private particles")),
                        p -> ToolSupport.vision(plugin, p),
                        ToolSupport.activity("look"),
                        (context, args) -> plugin.vision()
                                .look(
                                        context.player(),
                                        Schema.flag(args, "show_hits")
                                                && context.player().hasPermission("pandagpt.vision.debug"))
                                .thenApply(capture -> new ToolResult(
                                        capture.summary()
                                                + (capture.observation() == null
                                                        ? ""
                                                        : "\n"
                                                                + capture.observation()
                                                                        .context())
                                                + "\nRegion IDs (#n) are valid only for this capture; pass one to "
                                                + "focus_object to verify and highlight it."
                                                + (capture.saved() ? " Images were saved for administrators." : ""),
                                        capture.image()))),
                AgentTool.of(
                        "focus_object",
                        "Verify a numbered region from the latest look against the live world and highlight its outline "
                                + "for the player. Use it to point at the exact object you are talking about.",
                        object(integer("id", "Region number from the latest look", true)),
                        p -> ToolSupport.vision(plugin, p) && ToolSupport.perception(plugin),
                        null,
                        (context, args) -> {
                            String result =
                                    plugin.perception().focusLatest(context.player(), Schema.number(args, "id", -1));
                            return result.startsWith("ERROR: ")
                                    ? ToolResult.error(result.substring(7))
                                    : ToolResult.ok(result);
                        }),
                AgentTool.of(
                        "recall_surroundings",
                        "The latest quiet background observation around the player (may be up to a minute old) and "
                                + "recent block or entity changes. Cheaper than look; no image.",
                        object(),
                        p -> ToolSupport.perception(plugin),
                        null,
                        (context, args) -> ToolResult.ok(plugin.perception().recall(context.player()))));
    }

    private VisionTools() {}
}
