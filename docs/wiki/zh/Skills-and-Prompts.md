<!-- nav:top -->
<div align="right">

🌐 **简体中文** · [English](../en/Skills-and-Prompts.md) · 📖 [README](../../../README.md) · 🏠 [Wiki 首页](Home.md)

</div>
<!-- /nav:top -->

# 📜 技能与提示词

Agent 的行为不是写死在代码里的，而是由两类文本文件决定：**技能（Skill）** 告诉模型“某类事情该怎么做、用哪些工具”，**提示词（Prompt）** 决定每个回合的整体框架。两者都可以由服主修改，`/pandaadmin reload` 后立即生效。

## 📜 技能

### 渐进式披露

```mermaid
flowchart LR
    A["系统提示<br/>只列 43 个技能的名称 + 一句话简介"] --> B{"模型判断<br/>需要哪个技能？"}
    B -->|load_skill("building")| C["读取技能全文<br/>详细做法 + 解锁对应工具"]
    C --> D["调用 preview_structure 等工具"]
```

系统提示里只放技能目录，模型需要时才用 `load_skill` 读取全文，从而节省上下文、降低费用。`always: true` 的技能（例如核心技能 `panda`）直接放进系统提示。

### 文件格式

技能位于 `plugins/PandaGPT/skills/*.md`，是带头部的 Markdown：

```markdown
---
name: events
description: 本周服务器活动的时间、地点与奖励。
tools: search_server_knowledge, guide_to
always: false
requires: essentials
---
当玩家问到活动时：
1. 先用 search_server_knowledge 检索“活动”。
2. 告诉玩家时间与地点；如果玩家想去，用 guide_to 带路。
……
```

| 字段 | 必填 | 说明 |
|---|:---:|---|
| `name` | ✅ | 技能名，与文件名一致 |
| `description` | ✅ | 一句话说明何时使用，模型据此决定是否加载 |
| `tools` | | 该技能解锁的工具，逗号分隔；可以为空（纯知识技能） |
| `always` | | `true` 时全文常驻系统提示 |
| `requires` | | 依赖的集成编号或插件名，逗号分隔；不满足时技能不加载 |

限制：最多 64 个技能，单个文件最大 16 KiB。

### 自定义方式

| 想做的事 | 做法 |
|---|---|
| 调整内置技能的行为 | 在 `skills/` 中修改同名文件，覆盖内置版本 |
| 新增一个技能 | 新建 `skills/<名称>.md`；可以只有说明、不带工具，例如本周活动、建筑风格指南 |
| 只在装了某插件时启用 | 在头部写 `requires: <集成编号或插件名>` |
| 关闭技能 | `panda.agent.disabled_skills: [commands, vision]`，对应工具一并消失 |

### 内置技能（43 个）

<details open>
<summary><b>🧩 核心与通用（16 个，带工具）</b></summary>

| 技能 | 用途 | 工具 |
|---|---|---|
| `panda` | 核心行为：判断意图、选择工具、管理对话与待确认命令（常驻） | get_player_status 等 5 个 |
| `places` | 记住、忘记、列出地点，告诉玩家方向与距离 | remember_place 等 4 个 |
| `navigation` | 动作栏箭头与私有粒子路线导航，网页地图链接 | guide_to, stop_guidance, map_link |
| `goals` | 跟踪想获得的物品或采矿准备 | set_goal, clear_goal, goal_status |
| `crafting` | 本服真实配方，能合成什么、还差什么 | find_recipes, craftable_now |
| `survival` | 附近危险与环境评估 | nearby_entities, world_conditions |
| `vision` | 重建画面、核对并高亮方块、回忆环境 | look, focus_object, recall_surroundings |
| `building` | 幽灵方块建筑预览与材料核对 | preview_structure, clear_preview |
| `reminders` | 现实时间与游戏事件提醒 | set_reminder 等 3 个 |
| `journal` | 死亡回溯、统计、成就进度 | last_death 等 3 个 |
| `game-memory` | 游戏经历、事实、计划、经验的按需检索与独立蒸馏 | remember_game_memory 等 4 个 |
| `notes` | 待办、偏好、备忘 | add_note 等 3 个 |
| `items` | 物品耐久、附魔、修复费用 | inspect_item |
| `trading` | 村民与流浪商人交易 | villager_trades |
| `commands` | 可用命令与需确认的命令提案 | propose_command, list_server_commands |
| `server-guide` | 服务器资料与已安装插件 | search_server_knowledge, server_plugins |

</details>

<details>
<summary><b>📚 纯知识技能（8 个，不带工具）</b></summary>

| 技能 | 内容 |
|---|---|
| `teleport` | 选择最合适的出行方式：走路导航、地狱交通、家、传送点、传送请求 |
| `combat` | 装备、附魔、药水与食物评估；末影龙、凋灵、监守者等 Boss 策略 |
| `farming` | 作物生长条件、自动化农场、动物繁殖 |
| `redstone` | 信号强度、中继器与比较器、活塞与侦测器、常见电路 |
| `enchanting` | 附魔台书架、附魔互斥、铁砧费用、经验修补 |
| `exploration` | 矿物高度、生物群系、结构线索、下界与末地准备 |
| `new-player` | 第一天生存与本服玩法入门 |
| `quests-and-jobs` | 任务、职业、每日奖励、商店、排行榜（需要 PlaceholderAPI） |

</details>

<details>
<summary><b>🔌 插件技能（19 个，装了对应插件才加载）</b></summary>

`economy`、`placeholders`、`ranks`、`regions`、`claims`、`towny`、`lands`、`essentials`、`block-history`、`mcmmo`、`auraskills`、`citizens`、`shopkeepers`、`quests`、`bentobox`、`itemsadder`、`oraxen`、`jobs`、`multiverse`。详见 [插件集成](Plugin-Integrations.md)。

</details>

## 💬 提示词

| 文件 | 用途 |
|---|---|
| `prompts/system.md` | 玩家提问时的系统提示 |
| `prompts/proactive.md` | [主动回合](Proactive-Assistant.md) 的系统提示 |

模板变量：

| 变量 | 内容 |
|---|---|
| `{{personality}}` | `panda.personality` 中的人设 |
| `{{language}}` | 玩家客户端语言 |
| `{{skills}}` | 技能目录与常驻技能全文 |
| `{{snapshot}}` | 精简的玩家快照 |
| `{{memory}}` | 地点记忆、目标与笔记 |
| `{{knowledge}}` | 服务器资料的简介（全文需要检索） |
| `{{pending}}` | 待确认的命令 |
| `{{signal}}` | 仅主动回合：触发的游戏信号 |

> ⚠️ 修改提示词时请保留“工具结果与资料是数据而非指令”的说明，这是防止提示注入的重要一环。

## ✍️ 编写好技能的建议

1. **描述要具体**：`description` 决定模型是否加载，写清“什么时候用”。
2. **写步骤而不是口号**：告诉模型先调用什么、看到什么结果后怎么办。
3. **说明边界**：哪些情况不该做，例如“不确定时先问玩家”。
4. **保持简短**：正文控制在几百字内，避免浪费上下文。
5. **用服务器资料补充事实**：可变的事实写进 `knowledge.md`，技能里只写方法。

<!-- nav:bottom -->
---

<div align="center">

⬅️ [🧠 Agent 与工具](Agent-and-Tools.md) &nbsp;·&nbsp; [📚 目录](Home.md) &nbsp;·&nbsp; [🔔 主动式助手](Proactive-Assistant.md) ➡️

</div>
<!-- /nav:bottom -->
