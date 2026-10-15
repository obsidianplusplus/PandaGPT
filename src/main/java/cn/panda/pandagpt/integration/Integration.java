package cn.panda.pandagpt.integration;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import java.util.List;

/**
 * 一个外部插件的适配器。
 *
 * <p>实现类按需访问该插件的公共 API（编译期接口或签名检查后的反射），只有检测到插件后才通过类名加载；未安装时既不加载类也不暴露工具。
 * 工具在调用时才访问插件 API，构造时不得调用。
 */
public interface Integration {
    /** 集成编号，也是对应技能头部 requires 中使用的名字。 */
    String id();

    /** Verify optional public API signatures before exposing this integration; no player/world calls here. */
    default void validate(org.bukkit.plugin.Plugin installed) throws ReflectiveOperationException {}

    List<AgentTool> tools(ChatGPTIntegration plugin);
}
