package cn.panda.pandagpt.integration;

import static cn.panda.pandagpt.agent.Schema.object;
import static cn.panda.pandagpt.agent.Schema.string;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.Schema;
import cn.panda.pandagpt.agent.ToolResult;
import cn.panda.pandagpt.i18n.Msg;
import java.util.List;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Server;

/** Vault：读取玩家自己的余额、权限组与聊天前后缀。不提供任何转账能力，付款只能通过需确认的命令提案。 */
public final class VaultAdapter implements Integration {
    @Override
    public String id() {
        return "economy";
    }

    @Override
    public List<AgentTool> tools(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.of(
                        "economy_balance",
                        "The player's own money balance and the server currency name (Vault).",
                        object(),
                        p -> provider(p.getServer(), Economy.class) != null,
                        Msg.of("agent.activity.economy"),
                        (context, args) -> {
                            Economy economy = provider(context.player().getServer(), Economy.class);
                            double balance = economy.getBalance(context.player());
                            return ToolResult.ok("Balance: " + economy.format(balance) + " (raw " + balance
                                    + "), currency: " + economy.currencyNamePlural() + ", provider: "
                                    + economy.getName());
                        }),
                AgentTool.of(
                        "economy_can_afford",
                        "Whether the player has at least the given amount of money.",
                        object(string("amount", "Amount as a number", true)),
                        p -> provider(p.getServer(), Economy.class) != null,
                        null,
                        (context, args) -> {
                            double amount;
                            try {
                                amount = Double.parseDouble(Schema.text(args, "amount"));
                            } catch (NumberFormatException e) {
                                return ToolResult.error("amount must be a number");
                            }
                            Economy economy = provider(context.player().getServer(), Economy.class);
                            boolean has = economy.has(context.player(), amount);
                            double balance = economy.getBalance(context.player());
                            return ToolResult.ok((has ? "Yes" : "No") + ": balance " + economy.format(balance)
                                    + (has ? "" : ", short by " + economy.format(amount - balance)));
                        }),
                AgentTool.of(
                        "rank_display",
                        "The player's permission groups and chat prefix/suffix as reported by Vault.",
                        object(),
                        p -> provider(p.getServer(), Permission.class) != null
                                || provider(p.getServer(), Chat.class) != null,
                        null,
                        (context, args) -> {
                            var player = context.player();
                            Permission permission = provider(player.getServer(), Permission.class);
                            Chat chat = provider(player.getServer(), Chat.class);
                            StringBuilder out = new StringBuilder();
                            if (permission != null && permission.hasGroupSupport()) {
                                out.append("primary group: ")
                                        .append(permission.getPrimaryGroup(player))
                                        .append("\ngroups: ")
                                        .append(String.join(", ", permission.getPlayerGroups(player)));
                            }
                            if (chat != null) {
                                out.append("\nprefix: ")
                                        .append(chat.getPlayerPrefix(player))
                                        .append("\nsuffix: ")
                                        .append(chat.getPlayerSuffix(player));
                            }
                            return ToolResult.ok(
                                    out.isEmpty()
                                            ? "No rank data available."
                                            : out.toString().strip());
                        }));
    }

    private static <T> T provider(Server server, Class<T> type) {
        var registration = server.getServicesManager().getRegistration(type);
        return registration == null ? null : registration.getProvider();
    }
}
