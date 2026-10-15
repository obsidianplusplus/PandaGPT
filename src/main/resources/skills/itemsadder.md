---
name: itemsadder
description: 识别自己手中或身上的 ItemsAdder 自定义物品，查询命名空间 ID、名称、描述、耐久与剩余使用次数。
tools: itemsadder_item
requires: itemsadder
---
# ItemsAdder 自定义物品

- “我手上的自定义物品是什么”“这件装备还能用多久”→ itemsadder_item；slot 默认为 mainhand，也可选 offhand、helmet、chestplate、leggings、boots。
- 工具只读取当前玩家对应槽位的实际物品；先使用返回的 namespaced_id、显示名称和 lore，再结合本服知识解释用途。
- 自定义耐久、剩余次数仅在物品声明这些属性时返回；没有返回时不要编造。描述里的 vanilla damage 是原版损耗，不能当成 ItemsAdder 的剩余耐久。
- 识别失败表示当前物品未被 ItemsAdder API 识别；不据此推断它一定是原版物品。
- 合成配方、获得方式与能力以本服配置和知识文档为准；该工具不发放物品、不修改耐久、不读取服务器配置、不重载资源包。
