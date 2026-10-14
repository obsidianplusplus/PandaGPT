package cn.panda.pandagpt.agent.tools;

import static cn.panda.pandagpt.agent.Schema.choice;
import static cn.panda.pandagpt.agent.Schema.object;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.AgentTurn;
import cn.panda.pandagpt.agent.Schema;
import cn.panda.pandagpt.agent.ToolResult;
import cn.panda.pandagpt.util.PlayerContext;
import java.util.List;

/** “panda” 核心技能的工具：玩家状态、助手模式、对话管理与待确认动作。 */
public final class AssistantTools {
    public static List<AgentTool> create(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.readOnlyOf(
                        "get_player_status",
                        "Full status of the player: inventory summary, armor, offhand, potion effects, level.",
                        object(),
                        p -> true,
                        ToolSupport.activity("status"),
                        (context, args) -> ToolResult.ok(PlayerContext.status(context.player()))),
                AgentTool.of(
                        "set_assistance",
                        "Switch proactive assistance: quiet when the player does not want to be disturbed, assist when "
                                + "they want tips again. Only on a clear request.",
                        object(choice("mode", "Assistance mode", true, "assist", "quiet")),
                        p -> ToolSupport.perception(plugin),
                        null,
                        (context, args) -> ToolSupport.result(plugin.perception()
                                .setAssistance(context.player(), "quiet".equals(Schema.text(args, "mode"))))),
                AgentTool.of(
                        "forget_conversation",
                        "Forget the conversation history when the player asks you to. Places and goals are kept.",
                        object(),
                        p -> true,
                        null,
                        (context, args) -> {
                            String request = context.turn()
                                    .playerEvidence()
                                    .strip()
                                    .toLowerCase(java.util.Locale.ROOT)
                                    .replaceAll("[。.!！]$", "");
                            if (context.turn().kind() != AgentTurn.Kind.CONVERSATION
                                    || !java.util.Set.of(
                                                    "忘掉吧",
                                                    "忘掉刚才的对话",
                                                    "忘记刚才的对话",
                                                    "清空对话",
                                                    "清除对话历史",
                                                    "忘掉对话",
                                                    "forget this conversation",
                                                    "clear conversation history")
                                            .contains(request))
                                return ToolResult.error(
                                        "Explicit player authorization required. Ask the player to send: 清空对话 / clear conversation history.");
                            context.turn().requestForget();
                            return ToolResult.ok("Conversation history will be cleared after this reply.");
                        }),
                AgentTool.of(
                        "respond_to_pending_action",
                        "The player has commands awaiting confirmation (see the system prompt). Call ONLY when the "
                                + "player's latest message clearly agrees (confirm) or declines (cancel). Never confirm "
                                + "on your own initiative or when the reply is ambiguous; ask instead.",
                        object(choice("decision", "The player's decision", true, "confirm", "cancel")),
                        p -> plugin.service() != null
                                && plugin.service().actions().hasPending(p.getUniqueId()),
                        null,
                        (context, args) -> {
                            if (!context.turn().claimPendingAnswer()) {
                                return ToolResult.error("The pending action was already handled in this reply.");
                            }
                            var actions = plugin.service().actions();
                            var commands =
                                    actions.pendingCommands(context.player().getUniqueId());
                            if ("confirm".equals(Schema.text(args, "decision"))) {
                                actions.confirmPending(context.player());
                                return ToolResult.ok("Dispatched as the player: " + commands
                                        + ". Dispatch acceptance is not proof the game action succeeded.");
                            }
                            actions.cancel(context.player().getUniqueId());
                            return ToolResult.ok("Cancelled: " + commands);
                        }),
                AgentTool.of(
                        "stay_silent",
                        "Proactive turns only: decide that nothing is worth saying right now. Prefer silence over "
                                + "filler.",
                        object(),
                        p -> true,
                        null,
                        (context, args) -> {
                            if (context.turn().kind() != AgentTurn.Kind.PROACTIVE) {
                                return ToolResult.error("Only available for proactive turns.");
                            }
                            context.turn().stayQuiet();
                            return ToolResult.ok("Staying silent.");
                        }));
    }

    private AssistantTools() {}
}
