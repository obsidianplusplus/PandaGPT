<!-- nav:top -->
<div align="right">

🌐 **简体中文** · [English](../en/Configuration.md) · 📖 [README](../../../README.md) · 🏠 [Wiki 首页](Home.md)

</div>
<!-- /nav:top -->

# ⚙️ 配置参考

配置文件位于 `plugins/PandaGPT/config.yml`（当前版本 `config_version: 3`）。修改后执行 `/pandaadmin reload`：插件会**先完整校验并构建新的模块**，全部成功后才替换正在运行的服务；任何错误都会保留旧服务，并在聊天与控制台说明原因。

> 💡 升级时新增的配置项会自动补全到你的文件中，原有的值保持不变。

## 目录

[语言](#语言) · [Agent](#agent) · [对话](#对话) · [模型](#模型) · [请求](#请求) · [感知](#感知) · [视觉](#视觉) · [功能](#功能) · [集成](#集成) · [命令](#命令) · [日志](#日志) · [代理](#代理)

## 语言

| 键 | 默认 | 说明 |
|---|---|---|
| `panda.language.default` | `zh_CN` | 控制台与无法匹配的客户端使用的语言 |
| `panda.language.per_player` | `true` | 按玩家客户端语言显示插件消息 |

详见 [多语言](Languages.md)。

## Agent

| 键 | 默认 | 范围 | 说明 |
|---|---|---|---|
| `panda.agent.max_steps` | 8 | 1–16 | 每回合最多请求模型的次数 |
| `panda.agent.max_tool_calls` | 16 | 1–48 | 每回合最多执行的工具调用数 |
| `panda.agent.max_parallel_reads` | 3 | 1–8 | 独立只读工具同时等待上限；写入与未标记工具串行 |
| `panda.agent.turn_timeout_seconds` | 120 | 10–600 | 每回合总时长，含全部模型请求与工具执行 |
| `panda.agent.max_tool_result_chars` | 6000 | 500–32000 | 单个结果字符预算；记忆证据超限拒绝，其他截断标明缺失上下文 |
| `panda.agent.proactive` | `true` | | 主动建议由 Agent 判断与措辞；关闭后使用内置文本 |
| `panda.agent.disabled_skills` | `[]` | | 关闭的技能，例如 `[commands, vision]` |

## 对话

| 键 | 默认 | 说明 |
|---|---|---|
| `panda.trigger_word` | `panda` | 触发词，匹配聊天开头的 `panda` 或 `@panda`；不能包含空格或以 `@` 开头 |
| `panda.personality` | （中文人设） | 写入系统提示的人设 |
| `panda.cooldown` | 5000 | 每位玩家的冷却（毫秒） |
| `panda.cancel_chat_message` | `true` | 隐藏触发 Panda 的聊天消息 |
| `panda.history_rounds` | 5 | 保留的完整问答轮数（0–50） |
| `panda.max_sessions` | 1000 | 同时保存的会话数 |
| `panda.session_ttl_seconds` | 1800 | 会话空闲多久后清除 |
| `panda.max_prompt_chars` | 2000 | 单次提问的最大字符数 |
| `panda.context_char_budget` | 16000 | 上下文的字符预算（不是精确 token 数） |
| `panda.max_output_tokens` | 1000 | 模型单步输出上限 |

## 模型

```yaml
panda:
  model_selection:
    provider: 'gemini'
    <服务商>:
      api_key: ''
      api_url: '...'
      model: '...'
      vision: true
```

全部 15 家服务商见 [模型服务商](Model-Providers.md)。密钥也可以通过环境变量 `PANDAGPT_<服务商>_API_KEY` 提供。

## 请求

| 键 | 默认 | 范围 | 说明 |
|---|---|---|---|
| `panda.requests.workers` | 4 | 1–32 | AI 工作线程数 |
| `panda.requests.queue_size` | 32 | 1–1000 | 排队上限，满时直接拒绝 |
| `panda.requests.connect_timeout_seconds` | 10 | 1–60 | 连接超时 |
| `panda.requests.timeout_seconds` | 45 | 1–180 | 单次请求总时长，含重试 |
| `panda.requests.max_response_bytes` | 1048576 | 1 KiB–4 MiB | 响应体上限 |
| `panda.requests.retries` | 1 | 0–3 | 仅对 429/502/503/504 与连接失败重试 |

## 游戏记忆与记忆蒸馏

| 键 | 默认 | 说明 |
|---|---|---|
| `panda.memory.enabled` | `true` | 游戏记忆、原始证据记录与按需召回；独立于视觉/感知开关 |
| `panda.memory.distillation` | `true` | 独立索引与后台语义摘要/经历关系；增加模型请求 |
| `panda.memory.distill_output_tokens` | 4096 | 蒸馏输出预算，512–8192 token，独立于聊天预算 |
| `panda.memory.recall_limit` | 6 | 一次最多返回 1–12 条相关记忆 |
| `panda.memory.context_chars` | 1800 | 每次提问附带相关记忆的字符预算，256–4000 |

详见[游戏记忆与记忆蒸馏](Game-Memory.md)。

## 感知

| 键 | 默认 | 说明 |
|---|---|---|
| `panda.perception.enabled` | `true` | 感知、目标与地点记忆总开关 |
| `panda.perception.observe_seconds` | 30 | 后台 32×18 几何观察的间隔 |
| `panda.perception.scene_ttl_seconds` | 60 | 观察有效期 |
| `panda.perception.entity_radius` | 20 | 实体查询半径 |
| `panda.perception.max_entities` | 16 | 保留的实体数 |
| `panda.perception.max_players` | 128 | 同时维护记忆的玩家数 |
| `panda.perception.proactive` | `true` | 是否产生主动建议 |
| `panda.perception.suggestion_seconds` | 900 | 普通建议的最小间隔 |
| `panda.perception.routine_per_half_hour` | 2 | 每半小时最多几条普通建议；0 关闭 |

## 视觉

| 键 | 默认 | 说明 |
|---|---|---|
| `panda.vision.enabled` | `true` | 视觉采样总开关 |
| `panda.vision.width` / `height` | 160 / 90 | 采样分辨率（16–320 / 16–180） |
| `panda.vision.vertical_fov` | 70 | 垂直视场角（30–110） |
| `panda.vision.max_distance` | 96 | 射线最远距离（8–128） |
| `panda.vision.rays_per_tick` | 256 | 全局每 tick 射线上限 |
| `panda.vision.cells_per_tick` | 8192 | 全局每 tick 格子上限 |
| `panda.vision.time_budget_ms` | 2 | 每 tick 软时间预算（0.25–5） |
| `panda.vision.max_concurrent_captures` | 2 | 同时进行的采样数（1–4） |
| `panda.vision.timeout_ticks` | 200 | 超时取消 |
| `panda.vision.cooldown_seconds` | 10 | 每位玩家的冷却 |
| `panda.vision.movement_tolerance` | 0.35 | 采样期间允许的移动（格） |
| `panda.vision.angle_tolerance` | 3 | 采样期间允许的转头（度） |
| `panda.vision.max_debug_particles` | 64 | 调试粒子上限 |
| `panda.vision.save_images` | `true` | 保存最新一组图片到 `captures/<UUID>/` |
| `panda.vision.textures.enabled` | `true` | 使用真实纹理；关闭后使用地图色 |
| `panda.vision.textures.auto_download` | `true` | 自动从 Mojang 官方下载 `vanilla-<版本>.zip` |
| `panda.vision.textures.packs` | `[assets/vanilla-26.3.zip]` | 1–8 个资源包，后面的覆盖前面的 |
| `panda.vision.textures.cache_mib` | 64 | 解码纹理的缓存（8–256 MiB） |

## 功能

| 键 | 默认 | 说明 |
|---|---|---|
| `panda.features.preview_max_blocks` | 2048 | 幽灵预览的方块上限 |
| `panda.features.preview_seconds` | 180 | 预览自动消失的秒数 |
| `panda.features.guidance_seconds` | 600 | 粒子导航最长持续时间 |
| `panda.features.max_reminders` | 10 | 每人提醒上限；0 关闭 |
| `panda.features.map_url` | `''` | 网页地图模板，支持 `{world}` `{x}` `{y}` `{z}`；必须以 http(s):// 开头 |

## 集成

| 键 | 默认 | 说明 |
|---|---|---|
| `panda.integrations.disabled` | `[]` | 关闭的集成编号；支持列表见 Plugin-Integrations（新增 citizens/shopkeepers/quests/bentobox/itemsadder/oraxen/jobs/multiverse） |
| `panda.integrations.placeholder_denylist` | `[ip, address, password, token, email, uuid]` | 变量名包含这些片段时不解析 |
| `panda.integrations.block_history_days` | 7 | CoreProtect 最多查询的天数 |

## 命令

见 [命令提案](Command-Proposals.md)。

| 键 | 默认 |
|---|---|
| `panda.command_awareness.enabled` | `true` |
| `panda.command_awareness.allow_execution` | `false` |
| `panda.command_awareness.executor` | `player`（唯一可选值） |
| `panda.command_awareness.max_executions` | 3 |
| `panda.command_awareness.confirmation_ttl_seconds` | 60 |
| `panda.command_awareness.allowed_commands` | `[help]` |
| `panda.command_awareness.blocked_commands` | `[op, deop, stop, restart, reload, execute, sudo, panda, pandaadmin]` |

## 日志

| 键 | 默认 | 说明 |
|---|---|---|
| `panda.logging.enabled` | `true` | 文件日志；默认只记录 UUID 与事件 |
| `panda.logging.conversations` | `false` | 显式开启后才记录原始问题与回答 |
| `panda.logging.max_bytes` | 1048576 | 单个日志文件大小 |
| `panda.logging.files` | 3 | 轮转保留的文件数 |

## 代理

| 键 | 默认 | 说明 |
|---|---|---|
| `panda.proxy.enabled` | `false` | HTTP CONNECT 代理（不是 SOCKS） |
| `panda.proxy.host` / `port` | `localhost` / 10808 | |
| `panda.proxy.username` / `password` | `''` | 使用时需在 JVM 参数加入 `-Djdk.http.auth.tunneling.disabledSchemes=` |

代理同时用于模型请求与官方纹理下载。

<!-- nav:bottom -->
---

<div align="center">

⬅️ [⚡ 命令提案](Command-Proposals.md) &nbsp;·&nbsp; [📚 目录](Home.md) &nbsp;·&nbsp; [🌍 多语言](Languages.md) ➡️

</div>
<!-- /nav:bottom -->
