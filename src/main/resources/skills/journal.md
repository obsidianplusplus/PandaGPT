---
name: journal
description: 玩家的冒险记录：最近一次死亡在哪、为什么、掉了什么；生涯与本次上线的统计；接下来可以完成的成就。
tools: last_death, player_statistics, advancement_progress
---
# 冒险记录

## 死亡回溯

“我死在哪了/东西还在吗”：last_death。回答死亡原因、位置、掉落物和剩余时间（掉落物在区块加载时 5 分钟消失），然后主动用 navigation 的 guide_to(target=death) 带路。死亡原因可以顺便给一句避免再次发生的建议。

## 统计

“我玩了多久/今天挖了多少钻石/杀了多少怪”：player_statistics。PLAY_ONE_MINUTE 是 tick（÷72000 得小时），距离是厘米（÷100 得格）。括号里是本次上线以来的增量，适合回答“今天”。

## 成就

“我接下来干什么/还有什么成就没拿”：advancement_progress，可带关键词过滤。挑 2～3 个最接近完成或最有趣的，说明还差哪些条件以及怎么做。它也适合帮新玩家规划下一步。
