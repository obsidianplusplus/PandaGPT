package cn.panda.pandagpt.integration;

import static cn.panda.pandagpt.agent.Schema.object;
import static cn.panda.pandagpt.agent.Schema.string;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.Schema;
import cn.panda.pandagpt.agent.ToolResult;
import cn.panda.pandagpt.i18n.Msg;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.clip.placeholderapi.PlaceholderAPI;

/**
 * PlaceholderAPI：为当前玩家解析变量，借此读取任务、职业、商店、排行榜等数千个扩展插件的数据。
 *
 * <p>包含拒绝列表片段（IP、地址、令牌等）的变量一律不解析；变量只针对提问的玩家本人。
 */
public final class PlaceholderAdapter implements Integration {
    private static final Pattern PLACEHOLDER = Pattern.compile("%[A-Za-z0-9_:.\\-]{2,96}%");
    private static final int MAX_PLACEHOLDERS = 20;

    @Override
    public String id() {
        return "placeholders";
    }

    @Override
    public List<AgentTool> tools(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.of(
                        "resolve_placeholders",
                        "Resolve PlaceholderAPI placeholders for this player, e.g. '%vault_eco_balance% "
                                + "%jobsr_user_jobs%'. Use list_placeholder_expansions to see which identifiers exist. "
                                + "Unknown placeholders come back unchanged.",
                        object(string("placeholders", "One or more %identifier_param% tokens", true)),
                        p -> true,
                        Msg.of("agent.activity.knowledge"),
                        (context, args) -> {
                            Set<String> tokens = new LinkedHashSet<>();
                            Matcher matcher = PLACEHOLDER.matcher(Schema.text(args, "placeholders"));
                            while (matcher.find() && tokens.size() < MAX_PLACEHOLDERS) {
                                tokens.add(matcher.group());
                            }
                            if (tokens.isEmpty()) {
                                return ToolResult.error("No %placeholder% tokens found.");
                            }
                            List<String> deny = plugin.settings().features().placeholderDenylist();
                            List<String> lines = new ArrayList<>();
                            for (String token : tokens) {
                                String lower = token.toLowerCase(Locale.ROOT);
                                if (deny.stream().anyMatch(lower::contains)) {
                                    lines.add(token + " = [blocked by server privacy settings]");
                                    continue;
                                }
                                String value = PlaceholderAPI.setPlaceholders(context.player(), token);
                                lines.add(token + " = " + (value.equals(token) ? "[not registered]" : value));
                            }
                            return ToolResult.ok(String.join("\n", lines));
                        }),
                AgentTool.of(
                        "list_placeholder_expansions",
                        "Installed PlaceholderAPI expansion identifiers (the part before the first underscore).",
                        object(),
                        p -> true,
                        null,
                        (context, args) -> ToolResult.ok(
                                String.join(", ", new TreeSet<>(PlaceholderAPI.getRegisteredIdentifiers())))));
    }
}
