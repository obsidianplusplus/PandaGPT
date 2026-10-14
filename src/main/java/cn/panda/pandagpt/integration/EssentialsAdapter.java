package cn.panda.pandagpt.integration;

import static cn.panda.pandagpt.agent.Schema.object;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.ToolResult;
import com.earth2me.essentials.Essentials;
import com.earth2me.essentials.User;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * EssentialsX：玩家的家、服务器传送点以及 AFK、邮件、禁言、监禁、无敌、隐身等状态。
 *
 * <p>传送本身不由插件直接执行：Agent 通过需确认的命令提案（/home、/warp、/back、/spawn）完成，遵守玩家权限与冷却。
 */
public final class EssentialsAdapter implements Integration {
    @Override
    public String id() {
        return "essentials";
    }

    private static Essentials essentials(Player player) {
        return (Essentials) player.getServer().getPluginManager().getPlugin("Essentials");
    }

    @Override
    public List<AgentTool> tools(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.of(
                        "essentials_homes",
                        "The player's EssentialsX homes with world, coordinates and distance. To travel, propose "
                                + "'home <name>' (commands skill) or use guide_to with the coordinates.",
                        object(),
                        p -> true,
                        null,
                        (context, args) -> {
                            Player player = context.player();
                            User user = essentials(player).getUser(player);
                            List<String> lines = new ArrayList<>();
                            for (String name : user.getHomes()) {
                                Location home = user.getHome(name);
                                lines.add(name + ": " + describe(player, home));
                            }
                            return ToolResult.ok(
                                    lines.isEmpty()
                                            ? "The player has no homes. They can set one with /sethome."
                                            : String.join("\n", lines));
                        }),
                AgentTool.of(
                        "essentials_warps",
                        "Public EssentialsX warps with world, coordinates and distance. To travel, propose 'warp <name>'.",
                        object(),
                        p -> true,
                        null,
                        (context, args) -> {
                            Player player = context.player();
                            var warps = essentials(player).getWarps();
                            List<String> lines = new ArrayList<>();
                            for (String name : warps.getList()) {
                                if (lines.size() >= 50) {
                                    break;
                                }
                                try {
                                    lines.add(name + ": " + describe(player, warps.getWarp(name)));
                                } catch (Exception e) {
                                    lines.add(name + ": location unavailable");
                                }
                            }
                            return ToolResult.ok(lines.isEmpty() ? "No warps are defined." : String.join("\n", lines));
                        }),
                AgentTool.of(
                        "essentials_status",
                        "The player's EssentialsX status: nickname, AFK, unread mail, mute/jail with remaining time, "
                                + "god mode, vanish and the /back location.",
                        object(),
                        p -> true,
                        null,
                        (context, args) -> {
                            Player player = context.player();
                            User user = essentials(player).getUser(player);
                            long now = System.currentTimeMillis();
                            return ToolResult.ok(
                                    "nickname: " + (user.getNickname() == null ? "none" : user.getNickname())
                                            + "\nafk: " + user.isAfk()
                                            + "\nunread mail: " + user.getUnreadMailAmount()
                                            + "\nmuted: " + user.isMuted() + remaining(user.getMuteTimeout(), now)
                                            + "\njailed: " + user.isJailed() + remaining(user.getJailTimeout(), now)
                                            + "\ngod mode: " + user.isGodModeEnabled()
                                            + "\nvanished: " + user.isVanished()
                                            + "\n/back location: " + describe(player, user.getLastLocation()));
                        }));
    }

    private static String remaining(long timeout, long now) {
        return timeout > now ? " (" + Duration.ofMillis(timeout - now).toMinutes() + " minutes left)" : "";
    }

    private static String describe(Player player, Location location) {
        if (location == null || location.getWorld() == null) {
            return "unknown";
        }
        String text = location.getWorld().getName() + " " + location.getBlockX() + "," + location.getBlockY() + ","
                + location.getBlockZ();
        if (location.getWorld().equals(player.getWorld())) {
            text += " (" + Math.round(location.distance(player.getLocation())) + " blocks away)";
        }
        return text;
    }
}
