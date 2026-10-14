package cn.panda.pandagpt.agent.tools;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.ToolResult;
import cn.panda.pandagpt.i18n.Msg;
import cn.panda.pandagpt.perception.PerceptionController;
import java.util.Set;
import org.bukkit.entity.Player;

/** 工具实现共用的小工具。 */
final class ToolSupport {
    /** 感知模块是否可用。 */
    static boolean perception(ChatGPTIntegration plugin) {
        PerceptionController p = plugin.perception();
        return p != null && p.intentsAvailable();
    }

    static boolean vision(ChatGPTIntegration plugin, Player player) {
        return plugin.settings().vision().enabled()
                && plugin.vision() != null
                && player.hasPermission("pandagpt.vision");
    }

    // 表示操作成功的结果键；其余键都是失败原因。
    private static final Set<String> SUCCESS = Set.of(
            "intent.remembered",
            "intent.remembered_home",
            "intent.forgot",
            "intent.goal_set",
            "intent.goal_mine",
            "intent.goal_cleared",
            "intent.quiet",
            "intent.assist");

    /** 把感知模块返回的本地化结果渲染为英文交给模型；模型会用玩家的语言转述。 */
    static ToolResult result(Msg message) {
        String text = message.render("en_US");
        return SUCCESS.contains(message.key()) ? ToolResult.ok(text) : ToolResult.error(text);
    }

    static Msg activity(String name) {
        return Msg.of("agent.activity." + name);
    }

    private ToolSupport() {}
}
