<!-- nav:top -->
<div align="right">

🌐 **简体中文** · [English](../en/FAQ.md) · 📖 [README](../../../README.md) · 🏠 [Wiki 首页](Home.md)

</div>
<!-- /nav:top -->

# ❓ 常见问题

<details>
<summary><b>玩家说了 panda 但没有反应？</b></summary>

1. 触发词必须在聊天**开头**：`panda 你好` ✅，`你好 panda` ❌；
2. 检查玩家是否有 `pandagpt.use`；
3. 执行 `/pandaadmin status`，确认服务商已配置密钥；
4. 是否在冷却中（默认 5 秒），或上一个回合还没结束；
5. 是否有其他插件（例如禁言、聊天格式化插件）取消了聊天事件。

</details>

<details>
<summary><b>回答说“模型服务认证失败 / 找不到模型”？</b></summary>

- 认证失败（401/403）：密钥错误或没有权限；
- 找不到（404）：`model` 名称不对，或该账号不能使用此模型；请到服务商控制台确认可用模型名；
- 也可能是 `api_url` 写错了。修改后 `/pandaadmin reload`。

</details>

<details>
<summary><b>我的模型不支持图片怎么办？</b></summary>

设置 `vision: false`。Agent 仍会调用 `look`，只是拿到文字形式的结果（对象编号、方块、距离、实体），大部分“这是什么”“那个熔炉”类问题依然能回答。

</details>

<details>
<summary><b>视觉提示“纹理资源尚未就绪”？</b></summary>

插件正在后台下载官方纹理（查看控制台），或下载失败。常见原因是服务器无法访问 Mojang：可以配置 `panda.proxy`，或在其他机器上让插件下载一次后复制 `assets/vanilla-26.3.zip`。见 [安装与升级](Installation.md#textures)。

</details>

<details>
<summary><b>费用会不会很高？</b></summary>

- 一个回合可能请求模型多次（默认最多 8 步），可以降低 `panda.agent.max_steps`；
- 技能采用渐进式披露，系统提示只放目录；
- 后台几何观察和未命中的本地复查不调用模型；事件评估、AI 安排的复查回合与语义记忆蒸馏会调用模型；
- 自动复查默认每项请求最多 3 个回合，每回合可有多个模型步骤。用 `panda.agent.runtime.max_follow_ups: 0` 关闭任务复查，或 `panda.agent.proactive: false` 同时关闭普通 AI 主动消息；
- 对照服务商当前定价和实际使用量选择模型；次数限制不等于金额上限。

</details>

<details>
<summary><b>Panda 会不会乱执行命令？</b></summary>

不会。命令执行默认关闭，开启后也只能执行白名单中的命令，必须由玩家本人确认，并以玩家身份执行。见 [命令提案](Command-Proposals.md)。

</details>

<details>
<summary><b>怎么让 Panda 了解我的服务器？</b></summary>

在 `knowledge.md` 中用 Markdown 标题分段写下规则、经济、领地、传送、活动等。Agent 会按需检索相关段落。也可以新建纯知识技能，见 [技能与提示词](Skills-and-Prompts.md)。

</details>

<details>
<summary><b>支持 Folia / Spigot / 旧版本吗？</b></summary>

3.x 只支持 Paper 26.3 与 Java 25，未声明 Folia 兼容，不支持 Spigot 1.12.2。

</details>

<details>
<summary><b>玩家能关掉主动提醒吗？</b></summary>

可以，对 Panda 说“别打扰我”即可，恢复时说“可以提醒我了”。

</details>

<details>
<summary><b>重载失败了怎么办？</b></summary>

重载失败时旧服务继续运行，聊天与控制台会说明具体哪一项配置有问题（例如超出范围、地址不是 HTTPS）。修正后再次 `/pandaadmin reload`。

</details>

<details>
<summary><b>AI 说有计划，但为什么没有稍后提醒？</b></summary>

建立计划不代表安排复查。AI 需要调用 `schedule_follow_up`，最小延迟默认 60 秒；`state_change` 还要求当前观察快照与安排时不同。新问题、安静模式、离线、会话过期、重载、预算用尽或条件失效都可能取消复查，AI 也可能最终选择安静。需要准点提醒时使用提醒功能。见[运行时排障](Agent-Runtime.md#检查为什么没有继续跟进)。

</details>

<details>
<summary><b>运行时计划会永久保存、自动执行或代替玩家确认吗？</b></summary>

不会。计划是按 UUID 隔离的会话工作状态，不写入长期记忆文件，重载或重启后不恢复。主动回合只查询游戏数据并维护既有任务的临时状态，不能执行命令或遗忘长期记忆。要长期保存计划，明确请求使用游戏记忆；命令与遗忘各自保留玩家确认流程。

</details>

<details>
<summary><b>如何保留聊天计划，但不产生自动跟进费用？</b></summary>

设 `panda.agent.runtime.max_follow_ups: 0` 可保留计划工具、停止自动任务复查。事件触发的普通 AI 建议和语义记忆蒸馏是独立功能；若还要关闭它们，分别检查 `panda.agent.proactive` 和 `panda.memory.distillation`。`routine_per_half_hour: 0` 仅关闭普通事件评估，不单独关闭任务复查。见[配置与费用](Agent-Runtime.md#配置与费用)。

</details>

<!-- nav:bottom -->
---

<div align="center">

⬅️ [🛡️ 安全与隐私](Privacy-and-Security.md) &nbsp;·&nbsp; [📚 目录](Home.md) &nbsp;·&nbsp; [🛠️ 开发指南](Development.md) ➡️

</div>
<!-- /nav:bottom -->
