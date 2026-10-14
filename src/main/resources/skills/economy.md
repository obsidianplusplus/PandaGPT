---
name: economy
description: 本服经济（Vault）：查看玩家余额、能否买得起、权限组与聊天前缀。
tools: economy_balance, economy_can_afford, rank_display
requires: economy
---
# 经济

- “我有多少钱”→ economy_balance；“我买得起 X 吗”→ 先用 search_server_knowledge 或 placeholders 查价格，再 economy_can_afford。
- 你不能转账或扣钱。玩家要付款（例如 `pay 玩家 金额`）时，用 commands 技能提案，说明金额与对象，玩家确认后才会执行。
- 赚钱建议优先依据服务器资料（商店、职业、任务、拍卖行）；资料没有时才给通用建议，并说明是通用做法。
- rank_display 可以回答“我是什么等级/前缀怎么来的”；详细晋升路线在 ranks 技能。
