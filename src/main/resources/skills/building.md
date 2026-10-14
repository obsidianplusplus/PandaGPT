---
name: building
description: 帮玩家设计建筑，用只有他能看到的幽灵方块在原地预览，并核对材料够不够。
tools: preview_structure, clear_preview
---
# 建筑预览

preview_structure 把你设计的结构显示为假方块，只有这名玩家能看到，不会改动世界，几分钟后自动消失。

## 坐标系

以玩家朝向为准：x 向右、y 向上、z 向前。anchor=front（默认）时原点在玩家前方 2 格的地面上，anchor=feet 时在脚下。y=0 是玩家脚下那一层，所以地板通常放在 y=-1 或 y=0。

## 部件

| shape | 用法 |
|---|---|
| block | 单个方块，from |
| fill | 实心长方体，from 到 to |
| hollow | 空心长方体（含地板与屋顶），from 到 to |
| walls | 只有四面墙，from 到 to |
| line | 两点之间的直线，适合屋脊、栏杆 |
| cylinder | from 为底面中心，radius、height，hollow 只要外壳 |
| sphere | from 为中心，radius，hollow |
| pyramid | from 为底面中心，radius 为半边长，hollow |

部件按顺序绘制，后面的覆盖前面的；用 block=AIR 可以挖出门窗。

## 设计建议

1. 先问清用途、尺寸和风格；玩家没说时给一个 5×5 到 9×9 的合理默认。
2. 典型小屋：walls 做墙，fill 做地板，pyramid 或多条 line 做屋顶，AIR 开门（宽 1 高 2）和窗。
3. 材料要有层次：基座用石砖或圆石，墙用木板或原木，窗用玻璃，屋顶用不同颜色的楼梯或木板。
4. 预览后转述返回的材料清单和缺口，问玩家要不要调整（加宽、换材料、加二层）。
5. 玩家满意后，可以把缺的材料设为 goals 目标，或用 notes 技能记下设计要点。
6. 方块数超过上限会被截断，把大型建筑拆成几次预览。
