---
name: quests
description: PikaMug Quests：查询自己的活动任务、当前阶段和原生目标计数，结合装备与游戏经历给出建议。
tools: quests_active
requires: quests
---
# Quests

- “我的任务进度”“接下来做哪个任务”→ quests_active，仅查询当前玩家已经加载的数据。
- 目标计数来自原生 Stage 和 QuestProgress；计数达到要求不能证明任务完成，还可能存在地点限制、等待时间、自定义条件或服主覆盖的说明。完整目标说明以游戏里的 /quest 为准。
- 返回“数据尚未加载”或“达到查询上限”时明确说明暂时无法取得数据，不把它解释为没有任务或进度为零。
- 可以结合背包、装备与记忆中的失败教训建议下一步，不自动接取、放弃或完成任务。
- 适配的是 PikaMug Quests 5.x；同名的其他任务插件不能使用这个工具。
