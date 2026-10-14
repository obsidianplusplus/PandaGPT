package cn.panda.pandagpt.agent.tools;

import static cn.panda.pandagpt.agent.Schema.object;
import static cn.panda.pandagpt.agent.Schema.string;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.actions.ProposedCommand;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.Schema;
import cn.panda.pandagpt.agent.ToolResult;
import java.util.List;

/**
 * “commands” 技能的工具：提出由玩家本人执行的命令。
 *
 * <p>提案只在回合结束后展示，必须由玩家点击或明确同意才会执行；这里只做校验与收集。
 */
public final class CommandTools {
    public static List<AgentTool> create(ChatGPTIntegration plugin) {
        return List.of(AgentTool.of(
                "propose_command",
                "Propose a server command to run AS THE PLAYER when they explicitly ask you to do something that a "
                        + "command can do. It is shown with confirm/cancel buttons and runs only after the player "
                        + "agrees. Never claim it has been executed.",
                object(
                        string("command", "Command without the leading slash", true),
                        string("reason", "One short sentence shown to the player", true)),
                p -> plugin.service() != null && plugin.service().actions().enabled(p),
                null,
                (context, args) -> {
                    var proposals = context.turn().proposals();
                    if (proposals.size() >= plugin.settings().maxActions()) {
                        return ToolResult.error("Too many commands in one reply.");
                    }
                    String reason = Schema.text(args, "reason");
                    if (reason.length() > 500) {
                        return ToolResult.error("Reason is too long.");
                    }
                    try {
                        String command =
                                plugin.service().actions().validate(context.player(), Schema.text(args, "command"));
                        proposals.add(new ProposedCommand(command, reason));
                        return ToolResult.ok("Queued /" + command
                                + " for the player's confirmation after your reply. It has NOT run.");
                    } catch (IllegalArgumentException e) {
                        return ToolResult.error("Not allowed: the command root is not allowlisted or the player "
                                + "lacks permission.");
                    }
                }));
    }

    private CommandTools() {}
}
