---
name: shopkeepers
description: Shopkeepers 商店：查找附近可见实体商店，读取玩家当前打开的交易界面报价。
tools: shopkeepers_nearby, shopkeepers_trades
requires: shopkeepers
---
# 商店（Shopkeepers）

- “附近有什么商店 / 商人在哪”→ shopkeepers_nearby，默认半径 16 格，最多 32 格，最多展示 12 个可见实体商店。
- 附近查询检查玩家交易权限和管理商店额外权限。商店被列出不代表营业、库存或所有第三方交互条件已满足；告示牌、方块和虚拟商店不在附近查询范围内。
- “这家店卖什么 / 当前报价是多少”→ 玩家先自行打开该商店的交易界面，然后使用 shopkeepers_trades。工具只读取该玩家当前有效的 Shopkeepers 交易会话和界面中展示的前 12 条交易。
- 用报价解释交换所需物品、结果与当前界面的不可用标记。报价是查询时的界面快照，实际交易由 Shopkeepers 再次检查库存与条件。
- 不自动打开界面、购买、出售、修改报价、读取商店库存容器或其他玩家的交易界面。不从普通村民界面或编辑菜单推断 Shopkeepers 交易。
