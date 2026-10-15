<!-- nav:top -->
<div align="right">

🌐 **简体中文** · [English](../en/Plugin-Integrations.md) · 📖 [README](../../../README.md) · 🏠 [Wiki 首页](Home.md)

</div>
<!-- /nav:top -->

# 🔌 插件集成

PandaGPT 以**软依赖**方式对接常见插件：服务器装了哪个，Agent 就多一项能力；没装时相关工具与技能自动隐藏，**不需要任何配置**。

## 支持列表

| 插件 | 集成编号 | Agent 能做什么 | 示例 |
|---|---|---|---|
| 💰 **Vault**（及 EssentialsX、CMI 等经济插件） | `economy` | 余额、能否买得起、权限组与聊天前缀 | “我买得起钻石剑吗？” |
| 🧩 **PlaceholderAPI** | `placeholders` | 解析变量，读取任务、职业、商店、排行榜、统计等数千个扩展 | “我的每日任务做完了吗？” |
| 🎖️ **LuckPerms** | `ranks` | 权限组、继承、前后缀、到期时间、晋升路线、检查权限 | “我怎么升到 VIP？” |
| 🛡️ **WorldGuard** | `regions` | 所在区域、是否主人/成员、区域标志、能否建造与 PvP | “这里能 PvP 吗？” |
| 🏡 **GriefPrevention** | `claims` | 领地归属、信任等级、自己的领地与剩余领地块 | “我还能圈多大？” |
| 🏰 **Towny** | `towny` | 城镇与国家、职位、城镇银行、脚下地块与售价 | “这块地多少钱？” |
| 🗺️ **Lands** | `lands` | 领地归属、是否受信任与角色、自己的领地 | “这是谁的地盘？” |
| 🏠 **EssentialsX** | `essentials` | 家、传送点（含坐标与距离）、AFK、邮件、禁言、监禁、/back 位置 | “我设了哪些家？” |
| 🕵️ **CoreProtect** | `block-history` | 方块最近被谁放置或破坏（需 `pandagpt.blockhistory`） | “谁拆了我的箱子？” |
| ⚔️ **mcMMO** | `mcmmo` | 技能等级、升级所需经验、总战力 | “挖掘还差多少升级？” |
| ✨ **AuraSkills** | `auraskills` | 技能等级、属性、法力 | “我该加什么属性？” |

| 🧑 **Citizens** | `citizens` | 附近可见 NPC 的名称、ID、位置和距离，遵守视线与 PlayerFilter | “我附近有哪些 NPC？” |
| 🛒 **Shopkeepers** | `shopkeepers` | 附近可见实体商店；本人当前有效交易界面的实际报价与附魔 | “这个商店的经验修补卖多少钱？” |
| 📜 **Quests（PikaMug）** | `quests` | 本人已加载的活动任务、阶段和原生目标计数 | “我的任务做到哪一步了？” |
| 🏝️ **BentoBox** | `bentobox` | 本人的空岛、保护范围、队伍角色；当前位置的岛屿信息 | “我的空岛多大？我有哪些空岛？” |
| 🎨 **ItemsAdder** | `itemsadder` | 本人手持/装备物品的命名空间 ID、说明、自定义耐久与次数 | “这把自定义武器还能用几次？” |
| 🧰 **Oraxen** | `oraxen` | 本人手持/装备物品的 Oraxen ID、实际名称、lore 与损耗 | “我手里的物品是哪个自定义道具？” |
| ⛏️ **Jobs Reborn** | `jobs` | 本人的职业等级、经验，以及有原生加入权限的职业目录 | “我的矿工职业还差多少经验？” |
| 🌐 **Multiverse-Core 5** | `multiverse` | 可见世界、原生访问范围、模式、PvP、飞行与进入费用 | “有哪些世界？这里能飞吗？” |

## 新增适配的范围与权限

- **Citizens**：仅查询默认 NPC 注册表中的已生成实体，范围 1–32 格，最多检查 256 个附近实体、输出 12 项。必须在同世界、可见且有视线，PlayerFilter 隐藏的 NPC 不返回。结果不代表 NPC 的交互条件已经满足。
- **Shopkeepers**：查询需要 `shopkeeper.trade`，管理员商店额外检查原生交易权限。附近查询只覆盖实体商店，不枚举告示牌、方块或虚拟商店。报价工具要求玩家已打开自己的有效交易会话，读取当前界面实际显示的报价与附魔；不会打开商店、读取库存容器或执行交易。
- **Quests**：适配 PikaMug Quests 5.x API，需要 `quests.quest`。只查已有玩家记录，查找最多 2048 条、输出最多 10 个任务和每任务 24 个目标。缺失数据为 unknown；原生计数不覆盖所有地点、自定义条件或等待限制，完整任务描述仍以 `/quest` 为准。
- **BentoBox**：读取 BentoBox 核心数据，逐个游戏模式检查 `<游戏模式前缀>island.info`。个人查询限定本人的 UUID，最多检查 128 条、输出 12 个岛；原生 API 可能读取本人的冷缓存。访客视图隐藏岛中心与成员身份。BSkyBlock、AOneBlock 等模式使用相同核心接口；Level、Challenges、OneBlock 专属阶段数据未包含。
- **ItemsAdder / Oraxen**：仅识别本人主手、副手或四个盔甲槽位的物品，传给 API 的是物品副本。ItemsAdder 返回命名空间 ID；Oraxen 保留原始物品 ID，不编造命名空间。名称和 lore 有固定输出限制；不发放、创建物品或重载资源包。
- **Jobs Reborn**：进度查询需要 `jobs.command.stats`，目录需要 `jobs.command.browse`，目录条目同时检查 `jobs.use` 与 `jobs.join.<小写职业名>`。读取已缓存的本人档案；目录最多检查 256 项、输出 32 项。加入权限不证明满足职业数量、冷却、收益等其他条件。
- **Multiverse-Core 5**：目录需要 `multiverse.core.list.worlds`，当前位置设置需要 `multiverse.core.info`。遵守原生访问开关、访问权限、隐藏标志，以及目的世界对来源世界的黑名单。最多检查 256 个世界、输出 32 个；不加载世界或区块，不显示未加载世界的出生坐标。世界 PvP/飞行设置不覆盖区域或其他插件限制，目录不保证可以传送。

新增适配通过实际插件的 ClassLoader 校验固定公共 API 的签名，再按需调用。缺少类、方法或不兼容返回类型时跳过适配并记录警告；查询失败返回明确错误。所有新增工具为独立只读工具，适用受控并发规则。单元测试与官方接口核验已覆盖以上边界，尚未进行安装全部插件的真实 Paper 服务器联调。

接口核验依据：[Citizens API](https://wiki.citizensnpcs.co/API)、[Shopkeepers API](https://github.com/Shopkeepers/Shopkeepers/tree/master/modules/api)、[Quests 公共接口](https://github.com/PikaMug/Quests/tree/master/api)、[BentoBox 开发文档](https://docs.bentobox.world/en/latest/BentoBox/Developer-Documentation/)、[ItemsAdder CustomStack](https://lonedev6.github.io/API-ItemsAdder/dev/lone/itemsadder/api/CustomStack.html)、[Oraxen API](https://docs.oraxen.com/developers/api)、[Jobs API](https://github.com/Zrips/Jobs/wiki/API)、[Multiverse API](https://mvplugins.org/core/developers/developer-api-starter/)。

## 没有专门适配器的插件

**BetonQuest、其他商店、拍卖行、每日奖励、排行榜……** 也能用：

1. **PlaceholderAPI**：几乎所有插件都提供 PAPI 变量，Agent 可以通过 `resolve_placeholders` 读取；
2. **`server_plugins`**：了解服务器安装了什么；
3. **`list_server_commands`**：了解玩家可用的命令；
4. **`knowledge.md`**：服主写下的玩法说明。

`quests-and-jobs` 与 `teleport` 技能会指导 Agent 组合这些手段。

## 设计原则

| 原则 | 说明 |
|---|---|
| 🔒 **只读** | 集成只读取提问玩家本人的数据或公开信息 |
| ✋ **改变状态需确认** | 付款、传送、圈地等操作通过[命令提案](Command-Proposals.md)完成，必须玩家确认 |
| 🧩 **延迟加载** | 适配器按类名加载，只在插件启用时才触碰其 API |
| 🩹 **版本不兼容不崩溃** | 加载失败只在控制台警告并跳过该集成，不影响插件启动 |
| 📦 **不打包外部插件** | 已有编译期接口保持 provided；新增适配校验原生公共 API 后反射读取，均使用实际安装的插件 |

## 配置

```yaml
panda:
  integrations:
    disabled: []                  # 例如 [economy, block-history]
    placeholder_denylist: ['ip', 'address', 'password', 'token', 'email', 'uuid']
    block_history_days: 7         # CoreProtect 最多查询几天
```

- **`disabled`**：可选值 `economy placeholders ranks regions claims towny lands essentials block-history mcmmo auraskills citizens shopkeepers quests bentobox itemsadder oraxen jobs multiverse`。
- **`placeholder_denylist`**：变量名包含这些片段时一律不解析，防止泄露 IP、邮箱等隐私。
- **CoreProtect** 查询在后台线程执行，且需要 `pandagpt.blockhistory` 权限（默认仅 OP）。

## 让技能依赖插件

自定义技能可以用 `requires` 声明依赖，未安装时自动隐藏：

```markdown
---
name: shop-guide
description: 本服商店插件的使用方法与常见商品价格。
tools: resolve_placeholders, search_server_knowledge
requires: placeholders, ShopGUIPlus
---
```

`requires` 可以写集成编号，也可以写插件名（按 `plugin.yml` 中的名字判断是否启用）。

<!-- nav:bottom -->
---

<div align="center">

⬅️ [✨ 创意功能](Creative-Features.md) &nbsp;·&nbsp; [📚 目录](Home.md) &nbsp;·&nbsp; [⚡ 命令提案](Command-Proposals.md) ➡️

</div>
<!-- /nav:bottom -->
