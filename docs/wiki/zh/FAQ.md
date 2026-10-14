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
- 后台观察不调用模型；主动建议有严格的频率上限，并且可以关闭（`panda.agent.proactive: false`）；
- 选择 DeepSeek、通义千问、GLM 等低价模型，或各家的 mini / flash 系列。

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

<!-- nav:bottom -->
---

<div align="center">

⬅️ [🛡️ 安全与隐私](Privacy-and-Security.md) &nbsp;·&nbsp; [📚 目录](Home.md) &nbsp;·&nbsp; [🛠️ 开发指南](Development.md) ➡️

</div>
<!-- /nav:bottom -->
