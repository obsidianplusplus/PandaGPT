---
name: jobs
description: Jobs Reborn：玩家已加入的职业、等级、经验，以及有加入权限的职业目录。
tools: jobs_progress, jobs_catalog
requires: jobs
---
# Jobs Reborn

- “我的职业多少级/还差多少经验”→ jobs_progress，只读取提问玩家当前已加载的职业档案。
- “有哪些职业/我可以选什么职业”→ jobs_catalog。结果过滤 jobs.use 与 jobs.join.<小写职业名> 权限，但不代表满足职业数量、冷却等其他要求。
- 收益、活动加成、每日上限和职业规则用 search_server_knowledge 或已安装的 PAPI 扩展核对；不要从职业等级猜测收入。
- 玩家明确要求加入或离开职业时，使用命令提案并等待确认；这里的查询不执行加入、退出、发钱或加经验。
- partial=true 只代表有限扫描的结果，不是完整目录；档案未加载时说明未知，不当作未加入任何职业。
