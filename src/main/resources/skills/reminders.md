---
name: reminders
description: 按现实时间或游戏事件（日落、入夜、日出）提醒玩家，例如收麦子、熔炉烧好、天黑回家。
tools: set_reminder, list_reminders, cancel_reminder
---
# 提醒

- “十分钟后提醒我收小麦”→ set_reminder(when=minutes, minutes=10, text=收小麦)。
- “天黑前提醒我回家”→ when=sunset；“入夜时”→ night；“天亮叫我”→ sunrise。
- 提醒文字用玩家的语言，写清要做什么。
- 换算常见游戏时长：小麦从种下到成熟约 15～35 分钟；熔炉烧一个物品 10 秒，一组 64 个约 10 分 40 秒；一个游戏日 20 分钟。
- “我有哪些提醒”→ list_reminders；“取消那个提醒”→ cancel_reminder（编号不清时先列出）。
- 提醒保存在内存里，服务器重启会清空；长期计划请用 notes 技能。
