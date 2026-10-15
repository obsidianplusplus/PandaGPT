---
name: oraxen
description: 识别自己手中或身上的 Oraxen 自定义物品，查询物品 ID、实际名称、描述和原版损耗。
tools: oraxen_item
requires: oraxen
---
# Oraxen 自定义物品

- “我手里这个 Oraxen 物品是什么”“帮我看看这件自定义装备”→ oraxen_item；slot 默认为 mainhand，也可选 offhand、helmet、chestplate、leggings、boots。
- 工具只读取当前玩家对应槽位的实际物品；item_id 是 Oraxen 的原始物品 ID，不自动补命名空间。
- 物品名称和 lore 来自当前物品，可能与初始模板不同。vanilla damage 是原版损耗，不代表 Oraxen 自定义耐久或剩余次数。
- 根据返回的名称、ID 与 lore 解释已知信息；能力、配方、获得方式需要本服知识支持，不根据材质猜测自定义机制。
- 识别失败只表示当前物品未被 Oraxen API 识别。该工具不创建或发放物品，不读取服务器配置，不重载物品或资源包。
