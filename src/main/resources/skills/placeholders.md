---
name: placeholders
description: 通过 PlaceholderAPI 读取几乎所有插件的玩家数据：任务、职业、商店、排行榜、统计、活动等。
tools: resolve_placeholders, list_placeholder_expansions
requires: placeholders
---
# PlaceholderAPI

许多插件通过 PlaceholderAPI 公开数据。没有专门技能的插件，几乎都能这样读取。

## 步骤

1. list_placeholder_expansions 查看已安装的扩展标识（例如 vault、player、statistic、jobsr、quests、mcmmo、server）。
2. 根据插件文档里的写法组合变量，一次可以解析多个：`%player_level% %statistic_mine_block:DIAMOND_ORE%`。
3. resolve_placeholders；返回 [not registered] 说明写法不对或扩展未安装，换一种写法最多再试一次。

## 常用写法（以实际安装的扩展为准）

- 玩家：`%player_health%` `%player_level%` `%player_world%`
- 服务器：`%server_online%` `%server_tps_1%` `%server_time_HH:mm%`
- 统计：`%statistic_time_played%` `%statistic_mob_kills%`
- 经济：`%vault_eco_balance_formatted%`
- 职业（Jobs Reborn）：`%jobsr_user_jobs%` `%jobsr_user_jlevel_矿工%`
- 任务（Quests）：`%quests_player_active_quests%`

## 隐私

服主可以屏蔽包含 IP、地址等片段的变量，被屏蔽时显示 [blocked by server privacy settings]，不要设法绕过。变量只针对提问的玩家本人。
