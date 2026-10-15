package cn.panda.pandagpt.agent.tools;

import static cn.panda.pandagpt.agent.Schema.*;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentRuntime;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.AgentTurn;
import cn.panda.pandagpt.agent.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Planning is working state, never authority to execute commands or alter persistent memories. */
public final class RuntimeTools {
    public static final Set<String> NAMES = Set.of("runtime_status", "update_plan", "schedule_follow_up");

    public static List<AgentTool> create(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.readOnlyOf(
                        "runtime_status",
                        "Read your current player-scoped task, steps and remaining follow-up budget.",
                        object(),
                        p -> plugin.service().runtime(p.getUniqueId()) != null,
                        null,
                        (c, a) -> ToolResult.ok(plugin.service()
                                .runtime(c.player().getUniqueId())
                                .context())),
                AgentTool.of(
                        "update_plan",
                        "Maintain a short task plan for an actual multi-step player request. Do not create tasks for simple questions or invent requests. "
                                + "Activity is your provisional interpretation, not a fact. During proactive turns only revise the existing objective. "
                                + "Progress needs current evidence; a completed step does not execute any game action. Updating cancels an older scheduled check.",
                        object(
                                string("objective", "Short player objective (up to 160 characters)", true),
                                string(
                                        "activity",
                                        "Your current activity interpretation, e.g. preparing, exploring, recovering, unknown (up to 80 characters)",
                                        true),
                                string(
                                        "request_evidence",
                                        "Conversation turns: the complete current player message verbatim; proactive turns: omit",
                                        false),
                                array(
                                        "steps",
                                        "1–8 concrete steps, at most one in_progress",
                                        shape(
                                                string("description", "Short step (up to 160 characters)", true),
                                                choice(
                                                        "status",
                                                        "Evidence-based progress",
                                                        true,
                                                        "pending",
                                                        "in_progress",
                                                        "completed",
                                                        "blocked")),
                                        true)),
                        p -> plugin.service().runtime(p.getUniqueId()) != null,
                        null,
                        (c, a) -> {
                            try {
                                boolean conversation = c.turn().kind() == AgentTurn.Kind.CONVERSATION;
                                if (conversation
                                        && (!a.has("request_evidence")
                                                || !a.get("request_evidence").isJsonPrimitive()
                                                || !a.getAsJsonPrimitive("request_evidence")
                                                        .isString()
                                                || !c.turn()
                                                        .playerEvidence()
                                                        .equals(a.get("request_evidence")
                                                                .getAsString()))) {
                                    return ToolResult.error(
                                            "request_evidence must match the complete current player message verbatim.");
                                }
                                List<AgentRuntime.Step> steps = new ArrayList<>();
                                if (!a.has("steps")
                                        || !a.get("steps").isJsonArray()
                                        || a.getAsJsonArray("steps").size() > 8) {
                                    return ToolResult.error("Provide 1–8 plan steps.");
                                }
                                for (var e : a.getAsJsonArray("steps")) {
                                    var step = e.getAsJsonObject();
                                    steps.add(new AgentRuntime.Step(
                                            text(step, "description"),
                                            AgentRuntime.Status.valueOf(text(step, "status"))));
                                }
                                var runtime =
                                        plugin.service().runtime(c.player().getUniqueId());
                                runtime.update(
                                        text(a, "objective"),
                                        text(a, "activity"),
                                        steps,
                                        c.turn().playerEvidence(),
                                        conversation);
                                return ToolResult.ok(runtime.context());
                            } catch (IllegalArgumentException
                                    | IllegalStateException
                                    | UnsupportedOperationException e) {
                                return ToolResult.error(
                                        "Invalid plan. Use the documented objective/activity limits, step statuses and current request evidence.");
                            }
                        }),
                AgentTool.of(
                        "schedule_follow_up",
                        "Choose one bounded future read-only recheck for an unfinished player plan. "
                                + "Use state_change to wait for changed observed facts, or timer for a time-based recheck. "
                                + "Each plan has a finite allowance, and silence/quiet/offline/new questions can cancel it. "
                                + "This schedules a model evaluation, not a message, command execution or long-term reminder.",
                        object(
                                integer(
                                        "delay_seconds",
                                        "Minimum delay before checking (configured minimum through 1800 seconds)",
                                        true),
                                choice("trigger", "What should cause reevaluation", true, "state_change", "timer"),
                                string("reason", "What evidence to recheck and why (up to 240 characters)", true)),
                        p -> plugin.service().runtime(p.getUniqueId()) != null,
                        null,
                        (c, a) -> {
                            try {
                                return plugin.service()
                                        .scheduleFollowUp(
                                                c.player(),
                                                number(a, "delay_seconds", -1),
                                                AgentRuntime.Trigger.valueOf(text(a, "trigger")),
                                                text(a, "reason"));
                            } catch (IllegalArgumentException
                                    | IllegalStateException
                                    | UnsupportedOperationException e) {
                                return ToolResult.error(
                                        "Invalid follow-up arguments or no follow-up allowance remains.");
                            }
                        }));
    }

    private RuntimeTools() {}
}
