package cn.panda.pandagpt.agent.tools;

import static cn.panda.pandagpt.agent.Schema.choice;
import static cn.panda.pandagpt.agent.Schema.integer;
import static cn.panda.pandagpt.agent.Schema.object;
import static cn.panda.pandagpt.agent.Schema.string;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.Schema;
import cn.panda.pandagpt.agent.ToolResult;
import cn.panda.pandagpt.features.Reminders;
import java.util.List;
import java.util.stream.Collectors;

/** “reminders” 技能的工具：按时间或游戏事件提醒玩家。 */
public final class ReminderTools {
    public static List<AgentTool> create(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.of(
                        "set_reminder",
                        "Remind the player later: after N real minutes, or at the next sunset, nightfall or sunrise in "
                                + "game. Write the reminder text in the player's language.",
                        object(
                                string("text", "What to remind, short, in the player's language", true),
                                choice("when", "Trigger", false, "minutes", "sunset", "night", "sunrise"),
                                integer("minutes", "Minutes from now when when=minutes (1 to 1440)", false)),
                        p -> plugin.features() != null
                                && plugin.settings().features().maxReminders() > 0,
                        null,
                        (context, args) -> {
                            String text = Schema.text(args, "text");
                            if (text.isEmpty() || text.length() > 120) {
                                return ToolResult.error("Reminder text must be 1 to 120 characters.");
                            }
                            var trigger = Reminders.Trigger.parse(Schema.text(args, "when"));
                            int minutes = Schema.number(args, "minutes", 5);
                            if (trigger == Reminders.Trigger.TIME && (minutes < 1 || minutes > 1440)) {
                                return ToolResult.error("minutes must be between 1 and 1440.");
                            }
                            var features = plugin.features();
                            long now = features.clock().millis();
                            int id = features.reminders()
                                    .add(
                                            context.player().getUniqueId(),
                                            text,
                                            trigger,
                                            now + minutes * 60_000L,
                                            now,
                                            features.settings().maxReminders());
                            if (id < 0) {
                                return ToolResult.error("The player already has the maximum number of reminders.");
                            }
                            return ToolResult.ok("Reminder #" + id + " set ("
                                    + (trigger == Reminders.Trigger.TIME
                                            ? "in " + minutes + " minutes"
                                            : "at " + trigger)
                                    + "). Reminders are kept until the server restarts.");
                        }),
                AgentTool.readOnlyOf(
                        "list_reminders",
                        "The player's pending reminders.",
                        object(),
                        p -> plugin.features() != null,
                        null,
                        (context, args) -> {
                            var list = plugin.features()
                                    .reminders()
                                    .list(context.player().getUniqueId());
                            long now = plugin.features().clock().millis();
                            return ToolResult.ok(
                                    list.isEmpty()
                                            ? "No pending reminders."
                                            : list.stream()
                                                    .map(r -> "#" + r.id() + " " + r.text() + " ("
                                                            + (r.trigger() == Reminders.Trigger.TIME
                                                                    ? "in " + Math.max(0, (r.due() - now) / 60_000)
                                                                            + " min"
                                                                    : "at " + r.trigger())
                                                            + ")")
                                                    .collect(Collectors.joining("\n")));
                        }),
                AgentTool.of(
                        "cancel_reminder",
                        "Cancel a pending reminder by its number.",
                        object(integer("id", "Reminder number from list_reminders", true)),
                        p -> plugin.features() != null,
                        null,
                        (context, args) -> plugin.features()
                                        .reminders()
                                        .cancel(context.player().getUniqueId(), Schema.number(args, "id", -1))
                                ? ToolResult.ok("Reminder cancelled.")
                                : ToolResult.error("No such reminder.")));
    }

    private ReminderTools() {}
}
