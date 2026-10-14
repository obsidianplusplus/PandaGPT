<!-- nav:top -->
<div align="right">

🌐 **简体中文** · [English](../en/Permissions-and-Admin.md) · 📖 [README](../../../README.md) · 🏠 [Wiki 首页](Home.md)

</div>
<!-- /nav:top -->

# 🔑 权限与管理命令

## 唯一的命令：`/pandaadmin`

玩家没有任何命令，`/pandaadmin` 只给服主使用（需要 `pandagpt.admin`）。

| 命令 | 作用 |
|---|---|
| `/pandaadmin reload` | 校验后重载配置、语言、技能、提示词、服务器资料、配方索引与插件集成；失败时保留原服务。缺少官方纹理时同时开始下载 |
| `/pandaadmin status` | 服务商与模型、是否已配置密钥、技能与工具数量、队列、视觉与感知状态、每 tick 耗时 p95 与最大值、丢弃的日志数 |
| `/pandaadmin clearchat` | 清除全服的会话、进行中的回合与待确认命令 |

## 权限

| 权限 | 默认 | 用途 |
|---|---|---|
| `pandagpt.use` | 所有玩家 | 和 Panda 说话 |
| `pandagpt.actions` | OP | 接收并确认[命令提案](Command-Proposals.md) |
| `pandagpt.vision` | OP | `look`、`focus_object` 与后台观察 |
| `pandagpt.vision.debug` | OP | 用粒子显示射线命中点与对象轮廓 |
| `pandagpt.blockhistory` | OP | 让 Agent 查询 CoreProtect 方块记录 |
| `pandagpt.admin` | OP | `/pandaadmin` |

### 推荐的权限组设置（LuckPerms）

```text
# 普通玩家：可以对话、看图、确认白名单命令
/lp group default permission set pandagpt.vision true
/lp group default permission set pandagpt.actions true

# 管理员：额外可以查方块记录、看调试粒子
/lp group admin permission set pandagpt.blockhistory true
/lp group admin permission set pandagpt.vision.debug true
```

> 💡 视觉功能默认仅限 OP，是为了避免升级插件后普通玩家被自动纳入后台观察。确认服务器性能足够后再给普通玩家开放。

<!-- nav:bottom -->
---

<div align="center">

⬅️ [🌍 多语言](Languages.md) &nbsp;·&nbsp; [📚 目录](Home.md) &nbsp;·&nbsp; [🚀 性能](Performance.md) ➡️

</div>
<!-- /nav:bottom -->
