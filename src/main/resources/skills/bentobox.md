---
name: bentobox
description: BentoBox：查询自己的岛屿与当前位置的岛屿环境，覆盖 BSkyBlock、AOneBlock 等游戏模式的核心岛屿数据。
tools: bentobox_my_islands, bentobox_island_here
requires: bentobox
---
# BentoBox

- “我的空岛”“我的岛在哪”“我的岛有多大”→ bentobox_my_islands，逐世界核对原生 island.info 权限。
- “这是哪个岛”“我在别人的岛上吗”→ bentobox_island_here。访客视图只提供当前岛的基础环境信息；不能推测或补出隐藏的队伍、岛中心或岛主身份。
- 岛屿保护范围和玩家角色不能直接证明某个动作被允许；具体建造或交互权限还取决于 BentoBox 标志和游戏模式配置。
- 核心岛屿数据并不包含 Level、Challenges 或 AOneBlock 阶段进度；缺少附加组件的数据时不要编造岛屿等级或任务状态。
- 只读查询，不创建、重置、删除岛屿，不改变成员或保护设置，不传送玩家。
