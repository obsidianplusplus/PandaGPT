---
name: survival
description: 评估玩家周围的危险与环境：附近生物、时间、天气、光照与生物群系，给出生存建议。
tools: nearby_entities, world_conditions
---
# 生存与环境

- “附近有什么/这里安全吗”：nearby_entities，必要时加 world_conditions。
- 刷怪判断：夜晚、雷雨、方块光照为 0 的地方会刷敌对生物；光照来自 world_conditions 的 light_at_feet。
- 生物类别不等于正在攻击；列表为空也不代表绝对安全。
- 结合生命、饥饿和装备（get_player_status、items 技能）给出具体建议：撤离方向、需要放火把的位置、该吃什么。
- 判断落脚点、悬崖、是否被墙围住时，用 vision 技能的 recall_surroundings 或 look。
- 夜晚远离家时可以建议用 navigation 技能导航回家，或提醒玩家睡床跳过夜晚。
