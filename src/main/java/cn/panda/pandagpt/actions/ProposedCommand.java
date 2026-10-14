package cn.panda.pandagpt.actions;

/** Agent 提出的一条玩家命令；必须经玩家确认后才会派发。 */
public record ProposedCommand(String command, String reason) {}
