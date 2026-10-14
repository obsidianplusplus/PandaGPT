<!-- nav:top -->
<div align="right">

🌐 [简体中文](../zh/Creative-Features.md) · **English** · 📖 [README](../../../README.en.md) · 🏠 [Wiki home](Home.md)

</div>
<!-- /nav:top -->

# ✨ Creative Features

The agent uses all of these automatically during conversation. Players never need to learn a command.

## 🏗️ Ghost-block building preview

> `panda design a 7×7 wooden cabin here with a stone-brick base and a dark oak roof`

The agent composes a building from geometric parts and shows it in place as **fake blocks only you can see**. The world is never changed, and the preview disappears after a few minutes. Afterwards the agent compares the bill of materials with your inventory.

| `shape` | Use |
|---|---|
| `block` | A single block |
| `fill` | Solid box |
| `hollow` | Hollow box (with floor and roof) |
| `walls` | Four walls only |
| `line` | Straight line between two points, for ridges and railings |
| `cylinder` | Cylinder, optionally hollow, for towers |
| `sphere` | Sphere, optionally hollow |
| `pyramid` | Pyramid, optionally hollow, for roofs |

- Coordinates follow the player's facing: x right, y up, z forward. The origin is 2 blocks in front by default (`anchor=front`) or at your feet (`anchor=feet`).
- Parts draw in order and later ones overwrite earlier ones; use `AIR` to cut doors and windows.
- When you're happy, the agent can turn missing materials into a goal or save the design as a note.

| Setting | Default | Meaning |
|---|---|---|
| `features.preview_max_blocks` | 2048 | Blocks per preview; extra blocks are cut off |
| `features.preview_seconds` | 180 | Seconds before it disappears |

## 🧭 Particle guidance

> `panda take me home` · `panda take me to where I just died` · `panda take me to 100 64 -200`

- Action bar shows arrow, distance and height: `🧭 home ↗ 128 blocks · height +12`
- A private particle trail ahead of you
- Ends on arrival, or when you leave the target's world
- Lasts at most `features.guidance_seconds` (600 by default)

With `features.map_url` set, the agent can also hand out clickable web-map links (BlueMap, Dynmap, squaremap…):

```yaml
panda:
  features:
    map_url: 'https://map.example.com/#{world}:{x}:{y}:{z}:50:0:0:0:0:perspective'
```

## ⏰ Reminders

> `panda remind me to harvest wheat in ten minutes` · `panda tell me to head home before dark` · `panda wake me at sunrise`

| Trigger | Meaning |
|---|---|
| `minutes` | After N real minutes |
| `sunset` | Next sunset in the current world |
| `night` | Next nightfall (when mobs start spawning) |
| `sunrise` | Next sunrise |

When due, a chime plays and `[Panda Reminder] ⏰ harvest wheat` appears. Up to `features.max_reminders` per player (10 by default; 0 disables).

## 💀 Death recall

> `panda where did I just die? Is my stuff still there?`

Records your last death's location, time, cause and drops, and estimates how long the drops have left (they despawn after about 5 minutes). Panda reminds you once after a death; just say "take me back". The death point is stored as a special place.

## 📊 Statistics and advancements

> `panda how many diamonds did I mine today?` · `panda what advancement should I do next?`

- Lifetime statistics and changes this session: play time, deaths, kills, blocks mined, distance travelled and more
- Unfinished advancements sorted by how close you are, with what's left

## 📝 Personal notes

> `panda note that I like Japanese-style builds` · `panda to-do: wall in the village` · `panda what's left on my list?`

Notes are to-dos, facts about you (preferences, play style) or plain memos, saved long-term and used in later advice. Say "the wall is done" and the agent removes that to-do.

## 🗡️ Item inspection

> `panda how long will this sword last?` · `panda is this chestplate worth repairing?`

Durability left, enchantments, anvil repair cost, name and lore of held or worn items.

## 🧑‍🌾 Villager trades

> `panda who nearby sells Mending?` · `panda which villager buys wheat?`

Lists trades from villagers and wandering traders you can see, including enchanted books.

## 💡 More ideas

These emerge naturally from combining tools and skills:

| Scenario | Combination |
|---|---|
| 🗺️ "Plan tonight's expedition" | exploration skill + inventory check + nightfall reminder + saving the start point |
| ⚔️ "Am I ready for the Ender Dragon?" | combat skill + item inspection + player status |
| 🏠 "Design a storage hall in my style" | preferences from notes + ghost preview + material goal |
| 💰 "Can I afford that plot yet?" | Vault balance + prices from server docs |
| 🧭 "Fastest way back to town?" | teleport skill + Essentials warps + distance comparison + command proposal |

<!-- nav:bottom -->
---

<div align="center">

⬅️ [👁️ Vision & Perception](Vision-and-Perception.md) &nbsp;·&nbsp; [📚 Contents](Home.md) &nbsp;·&nbsp; [🔌 Plugin Integrations](Plugin-Integrations.md) ➡️

</div>
<!-- /nav:bottom -->
