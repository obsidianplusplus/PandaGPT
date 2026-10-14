package cn.panda.pandagpt.agent.tools;

import static cn.panda.pandagpt.agent.Schema.array;
import static cn.panda.pandagpt.agent.Schema.bool;
import static cn.panda.pandagpt.agent.Schema.choice;
import static cn.panda.pandagpt.agent.Schema.integer;
import static cn.panda.pandagpt.agent.Schema.integers;
import static cn.panda.pandagpt.agent.Schema.object;
import static cn.panda.pandagpt.agent.Schema.shape;
import static cn.panda.pandagpt.agent.Schema.string;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.Schema;
import cn.panda.pandagpt.agent.ToolResult;
import cn.panda.pandagpt.features.GhostPreview;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;

/** “building” 技能的工具：用只有玩家能看到的幽灵方块预览建筑设计，并核对材料。 */
public final class BuildTools {
    public static List<AgentTool> create(ChatGPTIntegration plugin) {
        JsonObject part = shape(
                choice(
                        "shape",
                        "Shape of this part",
                        true,
                        "block",
                        "fill",
                        "hollow",
                        "walls",
                        "line",
                        "cylinder",
                        "sphere",
                        "pyramid"),
                string("block", "Minecraft block ID, e.g. OAK_PLANKS, STONE_BRICKS, GLASS, AIR to carve", true),
                integers(
                        "from",
                        "[x, y, z] in the player's frame: x right, y up, z forward. Centre for "
                                + "cylinder/sphere/pyramid",
                        true),
                integers("to", "[x, y, z] opposite corner for fill/hollow/walls/line", false),
                integer("radius", "Radius for cylinder/sphere, half base for pyramid", false),
                integer("height", "Height for cylinder", false),
                bool("hollow", "Only the shell for cylinder/sphere/pyramid"));
        return List.of(
                AgentTool.of(
                        "preview_structure",
                        "Show a building design as ghost blocks only this player can see, without changing the world. "
                                + "Compose it from parts. Returns block counts and what is missing from the inventory. "
                                + "Replaces the previous preview; it disappears automatically after a while.",
                        object(
                                array("parts", "Parts drawn in order; later parts overwrite earlier ones", part, true),
                                choice("anchor", "Origin of the frame", false, "front", "feet")),
                        p -> plugin.features() != null,
                        ToolSupport.activity("build"),
                        (context, args) -> {
                            List<GhostPreview.Part> parts;
                            try {
                                parts = parts(args.getAsJsonArray("parts"));
                            } catch (IllegalArgumentException | IllegalStateException | NullPointerException e) {
                                return ToolResult.error("Invalid parts: " + e.getMessage());
                            }
                            var features = plugin.features();
                            var result = features.preview()
                                    .show(
                                            context.player(),
                                            parts,
                                            "feet".equals(Schema.text(args, "anchor")),
                                            features.settings().previewMaxBlocks(),
                                            features.clock().millis()
                                                    + features.settings().previewSeconds() * 1000L);
                            return ToolResult.ok("Preview shown: " + result.shown() + " blocks"
                                    + (result.skipped() > 0 ? ", " + result.skipped() + " outside loaded area" : "")
                                    + (result.truncated() ? ", TRUNCATED at the configured block limit" : "")
                                    + ". Materials needed: " + result.needed()
                                    + ". Missing from inventory: "
                                    + (result.missing().isEmpty() ? "nothing" : result.missing())
                                    + ". It disappears after "
                                    + features.settings().previewSeconds() + " seconds.");
                        }),
                AgentTool.of(
                        "clear_preview",
                        "Remove the current ghost-block preview.",
                        object(),
                        p -> plugin.features() != null,
                        null,
                        (context, args) -> ToolResult.ok(
                                plugin.features().preview().clear(context.player())
                                        ? "Preview cleared."
                                        : "There was no preview.")));
    }

    static List<GhostPreview.Part> parts(JsonArray array) {
        if (array == null || array.isEmpty() || array.size() > 64) {
            throw new IllegalArgumentException("provide 1 to 64 parts");
        }
        List<GhostPreview.Part> parts = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject p = element.getAsJsonObject();
            Material material = Material.matchMaterial(Schema.text(p, "block"));
            if (material == null || !material.isBlock()) {
                throw new IllegalArgumentException("unknown block " + Schema.text(p, "block"));
            }
            parts.add(new GhostPreview.Part(
                    Schema.text(p, "shape"),
                    material,
                    vector(p.getAsJsonArray("from")),
                    p.has("to") ? vector(p.getAsJsonArray("to")) : null,
                    Schema.number(p, "radius", 2),
                    Schema.number(p, "height", 3),
                    Schema.flag(p, "hollow")));
            GhostPreview.shape(parts.getLast(), 1);
        }
        return parts;
    }

    private static int[] vector(JsonArray array) {
        if (array == null || array.size() != 3) {
            throw new IllegalArgumentException("coordinates must be [x, y, z]");
        }
        return new int[] {
            array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()
        };
    }

    private BuildTools() {}
}
