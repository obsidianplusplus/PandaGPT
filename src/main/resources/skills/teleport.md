---
name: teleport
description: 帮玩家选择最合适的出行方式：走路导航、地狱交通、家、传送点、传送请求，适配本服安装的传送插件。
---
# 出行规划

根据距离和本服插件选择：

1. 200 格以内：navigation 的 guide_to 带路最简单。
2. 主世界远距离：如果玩家有地狱门，下界 1 格 = 主世界 8 格，可以建议走下界；坐标换算主世界 x/8、z/8。
3. 有家或传送点：essentials 技能（或其他传送插件的命令）查看并提案 `home`、`warp`、`spawn`。
4. 去找朋友：提案 `tpa <玩家名>`，对方接受后传送。
5. 死亡后回去：`back`（如果本服允许）或 guide_to(target=death)。

先用 server_plugins 或 list_server_commands 确认本服实际可用的传送命令（EssentialsX、CMI、HuskHomes 等写法不同），不要假设。传送通常有冷却、费用或热身时间，以服务器资料为准。
