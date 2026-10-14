package cn.panda.pandagpt.integration;

import static cn.panda.pandagpt.agent.Schema.object;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.ToolResult;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.skill.Skills;
import dev.aurelium.auraskills.api.stat.Stats;
import java.util.List;

/** AuraSkills：玩家的技能等级、属性、法力与总战力。 */
public final class AuraSkillsAdapter implements Integration {
    @Override
    public String id() {
        return "auraskills";
    }

    @Override
    public List<AgentTool> tools(ChatGPTIntegration plugin) {
        return List.of(AgentTool.of(
                "auraskills_profile",
                "The player's AuraSkills skill levels, stat values, mana and power level.",
                object(),
                p -> true,
                null,
                (context, args) -> {
                    var user = AuraSkillsApi.get().getUser(context.player().getUniqueId());
                    if (user == null || !user.isLoaded()) {
                        return ToolResult.error("AuraSkills has not loaded this player yet.");
                    }
                    StringBuilder out = new StringBuilder("power level: " + user.getPowerLevel() + ", mana: "
                            + Math.round(user.getMana()) + "/" + Math.round(user.getMaxMana()) + "\nskills: ");
                    for (Skills skill : Skills.values()) {
                        if (skill.isEnabled()) {
                            out.append(skill.name())
                                    .append(' ')
                                    .append(user.getSkillLevel(skill))
                                    .append(", ");
                        }
                    }
                    out.append("\nstats: ");
                    for (Stats stat : Stats.values()) {
                        if (stat.isEnabled()) {
                            out.append(stat.name())
                                    .append(' ')
                                    .append(Math.round(user.getStatLevel(stat) * 10) / 10.0)
                                    .append(", ");
                        }
                    }
                    return ToolResult.ok(out.toString());
                }));
    }
}
