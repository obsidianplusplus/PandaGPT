---
name: ranks
description: 玩家的 LuckPerms 权限组、继承关系、前后缀、临时权限、晋升路线，以及某个权限节点是否拥有。
tools: rank_info, rank_tracks, check_permission
requires: ranks
---
# 等级与权限

- “我是什么组/会员还剩几天”→ rank_info（含到期时间）。
- “怎么升级/下一级是什么”→ rank_tracks 看路线，再用 search_server_knowledge 查本服的升级条件。
- “为什么我用不了某命令”→ 先用 list_server_commands 或常识推断所需权限节点，再 check_permission 核实，告诉玩家缺什么、该找谁。
- 你不能给玩家加权限或改组，只能解释现状与获取途径。
