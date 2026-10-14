<!-- nav:top -->
<div align="right">

🌐 [简体中文](../zh/Skills-and-Prompts.md) · **English** · 📖 [README](../../../README.en.md) · 🏠 [Wiki home](Home.md)

</div>
<!-- /nav:top -->

# 📜 Skills & Prompts

The agent's behaviour is not hard-coded. Two kinds of text files define it: **skills** tell the model how to handle a kind of task and which tools to use; **prompts** frame every turn. Server owners can edit both, and `/pandaadmin reload` applies changes immediately.

## 📜 Skills

### Progressive disclosure

```mermaid
flowchart LR
    A["System prompt<br/>lists only 34 skill names + one-line descriptions"] --> B{"Model decides<br/>which skill it needs"}
    B -->|load_skill("building")| C["Reads the full skill<br/>method + unlocks its tools"]
    C --> D["Calls preview_structure etc."]
```

Only the catalog sits in the system prompt; the model loads a skill's full text with `load_skill` when needed, saving context and cost. Skills with `always: true` (such as the core `panda` skill) are inlined in the system prompt.

### File format

Skills live in `plugins/PandaGPT/skills/*.md` as Markdown with a header:

```markdown
---
name: events
description: This week's server events: times, places and rewards.
tools: search_server_knowledge, guide_to
always: false
requires: essentials
---
When the player asks about events:
1. Search "events" with search_server_knowledge.
2. Tell them when and where; if they want to go, guide them with guide_to.
…
```

| Field | Required | Meaning |
|---|:---:|---|
| `name` | ✅ | Skill name, same as the file name |
| `description` | ✅ | One sentence on when to use it; the model decides whether to load it from this |
| `tools` | | Tools unlocked by this skill, comma-separated; may be empty (knowledge-only skill) |
| `always` | | `true` keeps the full text in the system prompt |
| `requires` | | Integration IDs or plugin names, comma-separated; the skill loads only when all are present |

Limits: up to 64 skills, 16 KiB per file.

### Customising

| Goal | How |
|---|---|
| Change a built-in skill | Edit the file with the same name in `skills/`; it overrides the built-in one |
| Add a skill | Create `skills/<name>.md`; it may be instructions only, e.g. this week's events or a building style guide |
| Enable only with a plugin | Add `requires: <integration id or plugin name>` |
| Disable skills | `panda.agent.disabled_skills: [commands, vision]`; their tools disappear too |

### Built-in skills (34)

<details open>
<summary><b>🧩 Core and general (15, with tools)</b></summary>

| Skill | Purpose | Tools |
|---|---|---|
| `panda` | Core behaviour: intent, tool choice, conversation and pending commands (always on) | get_player_status and 4 more |
| `places` | Remember, forget and list places; direction and distance | remember_place and 3 more |
| `navigation` | Action-bar arrow and private particle guidance; web-map links | guide_to, stop_guidance, map_link |
| `goals` | Track wanted items or mining preparation | set_goal, clear_goal, goal_status |
| `crafting` | Real recipes, what's craftable and what's missing | find_recipes, craftable_now |
| `survival` | Nearby danger and environment | nearby_entities, world_conditions |
| `vision` | Reconstruct the view, verify and highlight blocks, recall surroundings | look, focus_object, recall_surroundings |
| `building` | Ghost-block building preview and material check | preview_structure, clear_preview |
| `reminders` | Real-time and game-event reminders | set_reminder and 2 more |
| `journal` | Death recall, statistics, advancement progress | last_death and 2 more |
| `notes` | To-dos, preferences, memos | add_note and 2 more |
| `items` | Durability, enchantments, repair cost | inspect_item |
| `trading` | Villager and wandering-trader trades | villager_trades |
| `commands` | Available commands and confirmable proposals | propose_command, list_server_commands |
| `server-guide` | Server documentation and installed plugins | search_server_knowledge, server_plugins |

</details>

<details>
<summary><b>📚 Knowledge-only skills (8, no tools)</b></summary>

| Skill | Content |
|---|---|
| `teleport` | Picks the best way to travel: walking guidance, Nether transit, homes, warps, teleport requests |
| `combat` | Gear, enchantments, potions and food; Ender Dragon, Wither and Warden strategy |
| `farming` | Crop conditions, automatic farms, animal breeding |
| `redstone` | Signal strength, repeaters and comparators, pistons and observers, common circuits |
| `enchanting` | Bookshelf layout, incompatible enchantments, anvil costs, Mending |
| `exploration` | Ore heights, biomes, structure hints, Nether and End preparation |
| `new-player` | First-day survival and server onboarding |
| `quests-and-jobs` | Quests, jobs, daily rewards, shops, leaderboards (requires PlaceholderAPI) |

</details>

<details>
<summary><b>🔌 Plugin skills (11, loaded only with their plugin)</b></summary>

`economy`, `placeholders`, `ranks`, `regions`, `claims`, `towny`, `lands`, `essentials`, `block-history`, `mcmmo`, `auraskills`. See [Plugin Integrations](Plugin-Integrations.md).

</details>

## 💬 Prompts

| File | Purpose |
|---|---|
| `prompts/system.md` | System prompt when a player asks something |
| `prompts/proactive.md` | System prompt for [proactive turns](Proactive-Assistant.md) |

Template variables:

| Variable | Content |
|---|---|
| `{{personality}}` | The persona from `panda.personality` |
| `{{language}}` | The player's client language |
| `{{skills}}` | Skill catalog plus always-on skills |
| `{{snapshot}}` | Compact player snapshot |
| `{{memory}}` | Places, goals and notes |
| `{{knowledge}}` | Overview of server docs (full text is searched) |
| `{{pending}}` | Commands awaiting confirmation |
| `{{signal}}` | Proactive turns only: the game signal |

> ⚠️ When editing prompts, keep the statement that tool results and docs are data, not instructions. It is an important defence against prompt injection.

## ✍️ Writing good skills

1. **Be specific in `description`**: it decides whether the skill is loaded, so say *when* to use it.
2. **Write steps, not slogans**: which tool first, and what to do with each result.
3. **State the boundaries**: e.g. "ask the player when unsure".
4. **Keep it short**: a few hundred words, to save context.
5. **Keep facts in `knowledge.md`**: skills hold methods; changing facts belong in server docs.

<!-- nav:bottom -->
---

<div align="center">

⬅️ [🧠 Agent & Tools](Agent-and-Tools.md) &nbsp;·&nbsp; [📚 Contents](Home.md) &nbsp;·&nbsp; [🔔 Proactive Assistant](Proactive-Assistant.md) ➡️

</div>
<!-- /nav:bottom -->
