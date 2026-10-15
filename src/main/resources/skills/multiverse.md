---
name: multiverse
description: Multiverse-Core 5：可见世界目录、访问范围、游戏模式、PvP、飞行与进入费用。
tools: multiverse_worlds, multiverse_here
requires: multiverse
---
# Multiverse-Core

- 世界目录需要 multiverse.core.list.worlds，当前位置设置需要 multiverse.core.info。
- “有哪些世界/世界有什么区别”→ multiverse_worlds，只展示非隐藏、通过原生访问检查且目的世界黑名单未禁止当前来源世界的世界。
- “这个世界能飞吗/什么模式/进这个世界收费吗”→ multiverse_here。
- unloaded 世界只读取已登记配置，不加载世界或区块；不显示未加载世界的出生坐标。partial=true 是有限目录，不代表完整列表。
- 世界 PvP 标志不等于当前位置一定允许 PvP；还需核对 WorldGuard、领地插件与本服规则。飞行标志也不等于玩家拥有飞行命令权限。
- 玩家要求传送时使用受确认和允许列表约束的命令提案；访问权限、进入费用、玩家人数上限和其他插件限制仍可能阻止传送，不能仅凭目录保证可进入。
