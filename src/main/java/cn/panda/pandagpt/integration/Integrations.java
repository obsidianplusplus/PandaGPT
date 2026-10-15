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
                    "AuraSkills 技能、属性与法力"),
            new Spec("citizens", "Citizens", "cn.panda.pandagpt.integration.CitizensAdapter", "附近可见 NPC 的名称、ID 与位置"),
            new Spec(
                    "shopkeepers",
                    "Shopkeepers",
                    "cn.panda.pandagpt.integration.ShopkeepersAdapter",
                    "附近可见商店与玩家当前交易界面的报价"),
            new Spec("quests", "Quests", "cn.panda.pandagpt.integration.QuestsAdapter", "已加载的玩家任务与当前阶段进度"),
            new Spec("bentobox", "BentoBox", "cn.panda.pandagpt.integration.BentoBoxAdapter", "自己的空岛与当前岛屿信息"),
            new Spec(
                    "itemsadder",
                    "ItemsAdder",
                    "cn.panda.pandagpt.integration.ItemsAdderAdapter",
                    "当前玩家物品的命名空间 ID、自定义耐久与用途"),
            new Spec("oraxen", "Oraxen", "cn.panda.pandagpt.integration.OraxenAdapter", "当前玩家的自定义物品 ID 与实际物品描述"),
            new Spec("jobs", "Jobs", "cn.panda.pandagpt.integration.JobsAdapter", "Jobs Reborn 职业等级、经验与有加入权限的职业目录"),
            new Spec(
                    "multiverse",
                    "Multiverse-Core",
                    "cn.panda.pandagpt.integration.MultiverseAdapter",
                    "Multiverse-Core 5 可见世界与访问范围、模式、PvP 和费用"));

    /** 探测结果：已启用的适配器与加载失败的集成。 */
    public record Detected(List<Integration> active, List<String> failed) {
        public Set<String> ids() {
            return Set.copyOf(active.stream().map(Integration::id).toList());
        }
    }

    /** Known integration IDs require a successfully validated adapter, even if plugin-name lookup is case-insensitive. */
    public static boolean requirementAvailable(
            String requirement, Set<String> active, java.util.function.Predicate<String> pluginEnabled) {
        return SPECS.stream().anyMatch(spec -> spec.id().equals(requirement))
                ? active.contains(requirement)
                : pluginEnabled.test(requirement);
    }

    public static Detected detect(Server server, Set<String> disabled, Consumer<String> warn) {
        List<Integration> active = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Spec spec : SPECS) {
            if (disabled.contains(spec.id()) || !server.getPluginManager().isPluginEnabled(spec.plugin())) {
                continue;
            }
            try {
                Integration adapter = load(spec);
                adapter.validate(server.getPluginManager().getPlugin(spec.plugin()));
                active.add(adapter);
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
