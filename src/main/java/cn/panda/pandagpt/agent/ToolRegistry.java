package cn.panda.pandagpt.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.entity.Player;

/** 全部工具的登记表；按技能开关与玩家权限筛选出本回合可用的工具。线程：注册完成后只读。 */
public final class ToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry register(Collection<AgentTool> list) {
        for (AgentTool tool : list) {
            if (tools.put(tool.name(), tool) != null) {
                throw new IllegalStateException("Duplicate tool " + tool.name());
            }
        }
        return this;
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    /** 主线程调用：返回已启用技能中、该玩家当前可用的工具。 */
    public List<AgentTool> available(Player player, Set<String> enabled) {
        List<AgentTool> result = new ArrayList<>();
        for (AgentTool tool : tools.values()) {
            if (enabled.contains(tool.name()) && tool.available(player)) {
                result.add(tool);
            }
        }
        return result;
    }

    public Collection<AgentTool> all() {
        return tools.values();
    }
}
