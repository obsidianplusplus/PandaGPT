package cn.panda.pandagpt.agent;

import java.util.List;

/**
 * 一项技能：一段可按需加载的说明，以及它启用的工具。
 *
 * <p>系统提示只列出名称与简介；模型需要时调用 load_skill 读取完整说明（渐进式披露），从而节省上下文。
 *
 * @param always 为 true 时说明直接放入系统提示，无需加载
 * @param requires 依赖的集成编号或插件名；任一不可用时整个技能不加载
 */
public record Skill(
        String name,
        String description,
        String instructions,
        List<String> tools,
        boolean always,
        List<String> requires) {
    public Skill {
        tools = List.copyOf(tools);
        requires = List.copyOf(requires);
    }
}
