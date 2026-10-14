package cn.panda.pandagpt.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.Server;

/**
 * 外部插件集成的登记与探测。
 *
 * <p>只有插件已启用、未被服主关闭、且适配器能正常加载（API 版本兼容）时才启用；不兼容的版本只记录警告，不影响启动。
 */
public final class Integrations {
    /**
     * 一个可选集成。
     *
     * @param plugin 服务器上的插件名
     * @param adapter 适配器的完整类名，按需加载
     */
    public record Spec(String id, String plugin, String adapter, String summary) {}

    /** 支持的外部插件。 */
    public static final List<Spec> SPECS = List.of(
            new Spec(
                    "economy",
                    "Vault",
                    "cn.panda.pandagpt.integration.VaultAdapter",
                    "经济余额、权限组与聊天前缀（Vault 接口，兼容 EssentialsX、CMI 等经济插件）"),
            new Spec(
                    "placeholders",
                    "PlaceholderAPI",
                    "cn.panda.pandagpt.integration.PlaceholderAdapter",
                    "解析 PlaceholderAPI 变量，覆盖数千个扩展插件的数据"),
            new Spec("ranks", "LuckPerms", "cn.panda.pandagpt.integration.LuckPermsAdapter", "权限组、继承、前后缀、临时权限与晋升路线"),
            new Spec("regions", "WorldGuard", "cn.panda.pandagpt.integration.WorldGuardAdapter", "所在区域、区域标志与建造权限"),
            new Spec(
                    "claims",
                    "GriefPrevention",
                    "cn.panda.pandagpt.integration.GriefPreventionAdapter",
                    "领地归属、信任等级与可用领地块"),
            new Spec("towny", "Towny", "cn.panda.pandagpt.integration.TownyAdapter", "城镇、国家、地块与城镇银行"),
            new Spec("lands", "Lands", "cn.panda.pandagpt.integration.LandsAdapter", "领地、信任与角色"),
            new Spec(
                    "essentials",
                    "Essentials",
                    "cn.panda.pandagpt.integration.EssentialsAdapter",
                    "家、传送点、AFK、邮件、禁言与监禁状态"),
            new Spec("block-history", "CoreProtect", "cn.panda.pandagpt.integration.CoreProtectAdapter", "方块的放置与破坏记录"),
            new Spec("mcmmo", "mcMMO", "cn.panda.pandagpt.integration.McMMOAdapter", "mcMMO 技能等级与经验"),
            new Spec(
                    "auraskills",
                    "AuraSkills",
                    "cn.panda.pandagpt.integration.AuraSkillsAdapter",
                    "AuraSkills 技能、属性与法力"));

    /** 探测结果：已启用的适配器与加载失败的集成。 */
    public record Detected(List<Integration> active, List<String> failed) {
        public Set<String> ids() {
            return Set.copyOf(active.stream().map(Integration::id).toList());
        }
    }

    public static Detected detect(Server server, Set<String> disabled, Consumer<String> warn) {
        List<Integration> active = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Spec spec : SPECS) {
            if (disabled.contains(spec.id()) || !server.getPluginManager().isPluginEnabled(spec.plugin())) {
                continue;
            }
            try {
                active.add(load(spec));
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                failed.add(spec.id());
                warn.accept(spec.plugin() + ": " + e.getClass().getSimpleName());
            }
        }
        return new Detected(List.copyOf(active), List.copyOf(failed));
    }

    /** 按类名加载适配器；测试中也用它在类路径上实例化全部适配器。 */
    public static Integration load(Spec spec) throws ReflectiveOperationException {
        return (Integration)
                Class.forName(spec.adapter()).getDeclaredConstructor().newInstance();
    }

    private Integrations() {}
}
