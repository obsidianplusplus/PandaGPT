package cn.panda.pandagpt.agent;

import org.bukkit.entity.Player;

/**
 * 工具调用时的上下文。只在服务器主线程创建与使用，因此可以安全访问 Player。
 *
 * @param language 玩家语言，用于需要渲染文本的结果
 */
public record ToolContext(Player player, AgentTurn turn, String language) {}
