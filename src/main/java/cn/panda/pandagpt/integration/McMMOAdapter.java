package cn.panda.pandagpt.integration;

import static cn.panda.pandagpt.agent.Schema.object;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.ToolResult;
import com.gmail.nossr50.api.ExperienceAPI;
import com.gmail.nossr50.datatypes.skills.PrimarySkillType;
import java.util.List;

/** mcMMO：玩家各项技能的等级、升级所需经验与总战力。 */
public final class McMMOAdapter implements Integration {
    @Override
    public String id() {
        return "mcmmo";
    }

    @Override
    public List<AgentTool> tools(ChatGPTIntegration plugin) {
        return List.of(AgentTool.of(
                "mcmmo_skills",
                "The player's mcMMO skill levels, XP needed for the next level, and power level.",
                object(),
                p -> true,
                null,
                (context, args) -> {
                    var player = context.player();
                    try {
                        StringBuilder out =
                                new StringBuilder("power level: " + ExperienceAPI.getPowerLevel(player) + "\n");
                        for (PrimarySkillType skill : PrimarySkillType.values()) {
                            out.append(skill.name()).append(": level ").append(ExperienceAPI.getLevel(player, skill));
                            if (!skill.isChildSkill()) {
                                out.append(", next in ")
                                        .append(ExperienceAPI.getXPToNextLevel(player, skill.name()))
                                        .append(" xp");
                            }
                            out.append('\n');
                        }
                        return ToolResult.ok(out.toString());
                    } catch (RuntimeException e) {
                        return ToolResult.error("mcMMO has not loaded this player's profile yet.");
                    }
                }));
    }
}
