---
name: quests-and-jobs
description: 任务、职业、每日奖励、商店、排行榜等插件的进度与数据，通过 PlaceholderAPI 与服务器资料读取。
requires: placeholders
---
# 任务、职业与其他玩法插件

这类插件种类繁多（Quests、BetonQuest、Jobs Reborn、BattlePass、ShopGUI+、AuctionHouse……），插件没有专门技能时按下面的方法处理：

1. server_plugins 确认本服装了什么；
2. list_placeholder_expansions 看它是否注册了变量，用 resolve_placeholders 读取玩家自己的进度；
3. search_server_knowledge 查服主写的玩法说明；
4. list_server_commands 找到打开菜单或查看进度的命令，需要时用 commands 技能提案。

回答“我的任务做到哪了/我的职业多少级/今天的奖励领了没”时，先读数据再给出下一步建议。读不到时如实说，并告诉玩家可以用哪个命令自己查看。
