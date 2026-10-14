---
name: survival
description: 评估玩家周围的危险与环境：附近生物、时间、天气、光照与生物群系。
tools: nearby_entities, world_conditions
---
# 生存与环境

- “附近有什么怪”“这里安全吗”：调用 nearby_entities；必要时再看 world_conditions（夜晚、雷雨、光照低于 8 的地方更容易刷怪）。
- 生物类别不等于正在攻击；列表为空也不能证明绝对安全，说得有分寸。
- 结合玩家的生命、饥饿和装备（快照或 get_player_status）给出具体建议：撤离方向、需要的物品、点亮火把的位置。
- 需要判断落脚点、悬崖、是否被墙包围时，用 vision 技能的 recall_surroundings 或 look。
