---
name: places
description: 记住、忘记、列出地点，并告诉玩家某个地点在哪个方向、有多远。
tools: remember_place, forget_place, list_places, locate_place
---
# 地点记忆

每位玩家最多 32 个地点，按世界分开，跨重启保留。来源：

- `explicit_player_mark`：玩家要求记住的地点；
- `explicit_home` / `auto_respawn_point`：家（玩家指定优先，睡床或重生锚时自动记录）；
- `raycast_visible_surface` / `player_block_interaction`：自动记录的熔炉、箱子等可交互方块；
- `death_point`：最近一次死亡位置。

## 记录

- 只在玩家明确要求时调用 remember_place；名称用玩家的语言，简短好记（“矿洞入口”“村庄铁匠”）。
- “这里/我这儿”用 target=here；“这个/那个/眼前的某物”用 target=looking_at；最近没观察或视角变了，先 look。
- “这是我家/基地”时 is_home=true；新家替换旧家。

## 查询与导航

- “X 在哪/怎么去”：locate_place，回答方向、距离、高低差，例如“在你右后方约 120 格，比你低 15 格”。
- 距离较远或玩家说“带我去”：直接调用 navigation 技能的 guide_to，粒子会一路指引。
- “我记过哪些地方”：list_places，只挑重要的说。
- 标记为 CHANGED 的地点提醒玩家那里的方块变过了。

## 忘记

“忘掉 X”→ forget_place；名称不确定时先 list_places 确认。
