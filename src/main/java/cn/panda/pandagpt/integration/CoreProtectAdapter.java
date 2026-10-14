package cn.panda.pandagpt.integration;

import static cn.panda.pandagpt.agent.Schema.choice;
import static cn.panda.pandagpt.agent.Schema.object;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.Schema;
import cn.panda.pandagpt.agent.ToolResult;
import cn.panda.pandagpt.i18n.Msg;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.block.Block;

/**
 * CoreProtect：查询一个方块的放置、破坏与交互记录，回答“谁动了我的箱子”。
 *
 * <p>需要 pandagpt.blockhistory 权限（默认仅 OP）；数据库查询在后台线程执行，不阻塞主线程。
 */
public final class CoreProtectAdapter implements Integration {
    @Override
    public String id() {
        return "block-history";
    }

    private static CoreProtectAPI api(org.bukkit.Server server) {
        var plugin = server.getPluginManager().getPlugin("CoreProtect");
        if (!(plugin instanceof CoreProtect coreProtect)) {
            return null;
        }
        CoreProtectAPI api = coreProtect.getAPI();
        return api != null && api.isEnabled() && api.APIVersion() >= 9 ? api : null;
    }

    @Override
    public List<AgentTool> tools(ChatGPTIntegration plugin) {
        return List.of(AgentTool.async(
                "block_history",
                "Who placed, broke or used a block recently (CoreProtect), newest first: the block the player is "
                        + "looking at (within 6 blocks) or the block under their feet.",
                object(choice("target", "Which block", false, "looking_at", "under_feet")),
                p -> p.hasPermission("pandagpt.blockhistory") && api(p.getServer()) != null,
                Msg.of("agent.activity.knowledge"),
                (context, args) -> {
                    var player = context.player();
                    Block block = "under_feet".equals(Schema.text(args, "target"))
                            ? player.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN)
                            : player.getTargetBlockExact(6);
                    if (block == null) {
                        return CompletableFuture.completedFuture(
                                ToolResult.error("The player is not looking at a block " + "within 6 blocks."));
                    }
                    CoreProtectAPI api = api(player.getServer());
                    int seconds = plugin.settings().features().blockHistoryDays() * 86400;
                    String where =
                            block.getType().name() + " at " + block.getX() + "," + block.getY() + "," + block.getZ();
                    // CoreProtect 建议在异步线程查询数据库。
                    return CompletableFuture.supplyAsync(() -> {
                        List<String[]> rows = api.blockLookup(block, seconds);
                        if (rows == null || rows.isEmpty()) {
                            return ToolResult.ok("No recorded changes for " + where + " in the last "
                                    + plugin.settings().features().blockHistoryDays() + " days.");
                        }
                        StringBuilder out = new StringBuilder("History of " + where + ":\n");
                        long now = System.currentTimeMillis() / 1000;
                        for (String[] row : rows.stream().limit(12).toList()) {
                            var result = api.parseResult(row);
                            out.append(result.getPlayer())
                                    .append(" ")
                                    .append(result.getActionString())
                                    .append(" ")
                                    .append(result.getType())
                                    .append(" ")
                                    .append(Duration.ofSeconds(Math.max(0, now - result.getTime()))
                                            .toMinutes())
                                    .append(" minutes ago")
                                    .append(result.isRolledBack() ? " (rolled back)" : "")
                                    .append('\n');
                        }
                        return ToolResult.ok(out.toString());
                    });
                }));
    }
}
