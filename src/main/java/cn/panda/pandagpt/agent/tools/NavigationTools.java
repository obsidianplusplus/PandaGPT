package cn.panda.pandagpt.agent.tools;

import static cn.panda.pandagpt.agent.Schema.integer;
import static cn.panda.pandagpt.agent.Schema.object;
import static cn.panda.pandagpt.agent.Schema.string;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.Schema;
import cn.panda.pandagpt.agent.ToolContext;
import cn.panda.pandagpt.agent.ToolResult;
import cn.panda.pandagpt.features.Guidance;
import java.util.List;
import java.util.regex.Pattern;

/** “navigation” 技能的工具：粒子导航、停止导航与在线地图链接。 */
public final class NavigationTools {
    private static final Pattern COORDS = Pattern.compile("\\s*(-?\\d+)[ ,]+(-?\\d+)[ ,]+(-?\\d+)\\s*");

    public static List<AgentTool> create(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.of(
                        "guide_to",
                        "Start live guidance: an arrow and distance in the action bar plus a private particle trail "
                                + "toward the target, until the player arrives. Target: a remembered place name, 'home', "
                                + "'death' (last death point) or coordinates 'x y z' in the current world.",
                        object(
                                string("target", "Place name, 'home', 'death' or 'x y z'", true),
                                string("label", "Short label shown to the player (defaults to the target)", false)),
                        p -> plugin.features() != null,
                        ToolSupport.activity("locate"),
                        (context, args) ->
                                guide(plugin, context, Schema.text(args, "target"), Schema.text(args, "label"))),
                AgentTool.of(
                        "stop_guidance",
                        "Stop the current particle guidance.",
                        object(),
                        p -> plugin.features() != null,
                        null,
                        (context, args) -> ToolResult.ok(
                                plugin.features()
                                                .guidance()
                                                .stop(context.player().getUniqueId())
                                        ? "Guidance stopped."
                                        : "No guidance was active.")),
                AgentTool.of(
                        "map_link",
                        "A clickable web-map link for the player's position or given coordinates (only when the server "
                                + "configured an online map).",
                        object(
                                integer("x", "Block X (defaults to the player)", false),
                                integer("y", "Block Y", false),
                                integer("z", "Block Z", false)),
                        p -> plugin.settings().features().mapUrl().length() > 0,
                        null,
                        (context, args) -> {
                            var l = context.player().getLocation();
                            String url = plugin.settings()
                                    .features()
                                    .mapUrl()
                                    .replace(
                                            "{world}",
                                            context.player().getWorld().getName())
                                    .replace("{x}", String.valueOf(Schema.number(args, "x", l.getBlockX())))
                                    .replace("{y}", String.valueOf(Schema.number(args, "y", l.getBlockY())))
                                    .replace("{z}", String.valueOf(Schema.number(args, "z", l.getBlockZ())));
                            return ToolResult.ok("Map link: " + url + " (include it verbatim in your reply).");
                        }));
    }

    private static ToolResult guide(ChatGPTIntegration plugin, ToolContext context, String target, String label) {
        var player = context.player();
        var features = plugin.features();
        long until = features.clock().millis() + features.settings().guidanceSeconds() * 1000L;
        String shown = label.isBlank() ? target : label;
        var coords = COORDS.matcher(target);
        if (coords.matches()) {
            features.guidance()
                    .start(
                            player,
                            new Guidance.Target(
                                    shown,
                                    player.getWorld().getUID(),
                                    Integer.parseInt(coords.group(1)) + .5,
                                    Integer.parseInt(coords.group(2)),
                                    Integer.parseInt(coords.group(3)) + .5,
                                    until));
            return ToolResult.ok("Guiding the player to " + target + ".");
        }
        if (target.equalsIgnoreCase("death")) {
            var death = features.deaths().last(player.getUniqueId());
            if (death == null || !death.world().equals(player.getWorld().getUID())) {
                return ToolResult.error("No recorded death in this world.");
            }
            features.guidance()
                    .start(
                            player,
                            new Guidance.Target(
                                    shown,
                                    death.world(),
                                    death.position().x() + .5,
                                    death.position().y(),
                                    death.position().z() + .5,
                                    until));
            return ToolResult.ok("Guiding the player to their death point at " + death.position() + ".");
        }
        if (plugin.perception() == null) {
            return ToolResult.error("Place memory is disabled.");
        }
        var place = plugin.perception().findPlace(player, target);
        if (place == null) {
            return ToolResult.error("No place named " + target + " in this world. Use list_places.");
        }
        features.guidance()
                .start(
                        player,
                        new Guidance.Target(
                                shown,
                                place.world(),
                                place.position().x() + .5,
                                place.position().y(),
                                place.position().z() + .5,
                                until));
        return ToolResult.ok("Guiding the player to " + place.name() + " at " + place.position() + ".");
    }

    private NavigationTools() {}
}
