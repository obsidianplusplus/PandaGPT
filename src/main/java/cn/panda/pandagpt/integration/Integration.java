package cn.panda.pandagpt.integration;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import java.util.List;

/**
 * 一个外部插件的适配器。
 *
 * <p>实现类会直接引用该插件的 API，因此只有在服务器上检测到该插件后才会通过类名加载；未安装时既不加载类也不暴露工具。
 * 工具在调用时才访问插件 API，构造时不得调用。
 */
public interface Integration {
    /** 集成编号，也是对应技能头部 requires 中使用的名字。 */
    String id();

    List<AgentTool> tools(ChatGPTIntegration plugin);
}
