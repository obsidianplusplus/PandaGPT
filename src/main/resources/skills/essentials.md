---
name: essentials
description: EssentialsX：玩家的家、服务器传送点，以及 AFK、邮件、禁言、监禁、无敌、隐身等状态。
tools: essentials_homes, essentials_warps, essentials_status
requires: essentials
---
# EssentialsX

## 家与传送点

- “我的家在哪/有哪些家”→ essentials_homes；“有哪些传送点”→ essentials_warps。
- 玩家要过去时有两种方式，按距离和意愿选：
  1. 传送：commands 技能提案 `home <名字>`、`warp <名字>`、`spawn`、`back`，确认后执行（遵守本服冷却与费用）；
  2. 走过去：navigation 的 guide_to，目标填返回的坐标 `x y z`。
- 死亡后想回去，提案 `back` 通常最快（如果本服允许）。

## 状态

- “我有没有新邮件/为什么不能说话/我还要被关多久”→ essentials_status。
- 有未读邮件时提醒玩家用 `mail read` 查看。
