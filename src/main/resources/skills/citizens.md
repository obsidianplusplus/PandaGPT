---
name: citizens
description: Citizens NPC：查看附近可见、已生成的 NPC 名称、编号和位置。
tools: citizens_nearby
requires: citizens
---
# NPC（Citizens）

- “附近有什么 NPC / 这个区域有哪些 NPC”→ citizens_nearby，默认半径 16 格，最多 32 格。
- 仅查询默认 NPC 注册表，按玩家当前世界、距离、可见性、视线与 Citizens 的 PlayerFilter 过滤，最多返回 12 个结果。
- NPC 编号只用于识别本次查询中的 NPC，不能保证重载后仍相同。名称不能证明 NPC 提供某种任务或交互功能。
- 工具只读取名称、编号和位置。创建 NPC、修改特征、移动 NPC、执行绑定命令或领取任务须由玩家通过服务器提供的交互完成。
- 找不到 NPC 或扫描达到上限时，说明查询范围和限制，不能推断全服不存在该 NPC。
