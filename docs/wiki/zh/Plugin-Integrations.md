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

## 没有专门适配器的插件

**Jobs、Quests、BetonQuest、商店、拍卖行、每日奖励、排行榜……** 也能用：

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
| 📦 **不打包外部插件** | API 只用于编译，运行时使用服务器上实际安装的插件 |

## 配置

```yaml
panda:
  integrations:
    disabled: []                  # 例如 [economy, block-history]
    placeholder_denylist: ['ip', 'address', 'password', 'token', 'email', 'uuid']
    block_history_days: 7         # CoreProtect 最多查询几天
```

- **`disabled`**：可选值 `economy placeholders ranks regions claims towny lands essentials block-history mcmmo auraskills`。
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
