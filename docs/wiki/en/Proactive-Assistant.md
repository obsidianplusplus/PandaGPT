<!-- nav:top -->
<div align="right">

🌐 [简体中文](../zh/Proactive-Assistant.md) · **English** · 📖 [README](../../../README.en.md) · 🏠 [Wiki home](Home.md)

</div>
<!-- /nav:top -->

# 🔔 Proactive Assistant

> A good assistant is quiet most of the time. Panda only speaks up when it is **genuinely useful**, and every time the agent checks the facts before deciding to say anything.

## How it works

```mermaid
flowchart TD
    G["🎮 Game signal<br/>goal progress · local risk · health/hunger · nightfall · death"] --> Q{"QuietPolicy<br/>rate limits · dedupe · timing"}
    Q -->|urgent local risk| AB["⚠️ Action bar immediately<br/>no model involved"]
    Q -->|routine candidate| A["🧠 Proactive turn<br/>read-only tools + stay_silent"]
    A -->|worth saying after checking| S["💬 One short, specific sentence"]
    A -->|not worth it| N["🤫 stay_silent, nothing is sent"]
    A -->|model unavailable| F["📝 Built-in fallback text"]
```

## Signals that start a proactive turn

| Signal | When | Panda might say |
|---|---|---|
| ✅ Goal materials ready | Everything for your goal item is in your inventory | "You have everything for the iron pickaxe, and there's a crafting table right here." |
| 🛠️ Craft on the way | Materials ready and a crafting table observed nearby | Same, pointing at the table |
| ⛏️ Departure check | Leaving home (8 blocks) with a mining goal | "Only 3 torches left; top up first?" |
| ❤️ Low health / hunger | Persistently low values | "You're nearly starving; there's bread in your bag." |
| 🌙 Nightfall far from home | It's getting dark and you're far away | "It's dark; home is 230 blocks north-east." |
| 💀 Just died | After a death | "You died near the mine entrance; your items vanish in about 5 minutes. Want me to guide you?" |
| ⚠️ Local risk | Drops or lava ahead | Shown in the action bar directly, no model |

## Constraints on proactive turns

- **Read-only tools only**, e.g. `goal_status`, `locate_place`, `nearby_entities`, `last_death`, `economy_balance`, `regions_here`, plus `stay_silent`.
- **Cannot** propose commands, change goals, set reminders or change any player state.
- One sentence of about 40 characters (Chinese) or a short line, saying what was found and what to do. No small talk, no questions.
- When the model is unavailable or proactive agent turns are off, built-in text is used instead.

## Frequency and interruptions

| Rule | Default |
|---|---|
| Minimum gap between routine tips | 15 minutes (`perception.suggestion_seconds: 900`) |
| At most per rolling half hour | 2 (`perception.routine_per_half_hour: 2`; 0 disables) |
| Same fact | Deduplicated, never repeated |
| Pending queue | Keeps only the single most valuable tip; dropped if no good moment within 20 seconds |
| After combat, placing/breaking or interacting | Routine tips wait 5 seconds |
| Conditions no longer true | Missing materials again, goal cancelled, moved away, logged out, etc. withdraw the tip |

Urgent local risks use separate short-term deduplication and don't count toward the routine limit.

## Switches

| Goal | How |
|---|---|
| Player opts out | Tell Panda "don't disturb me"; "you can remind me again" to resume |
| Use built-in text instead of model-written tips | `panda.agent.proactive: false` |
| Disable proactive tips entirely | `panda.perception.proactive: false` |
| Disable perception, goals and memory | `panda.perception.enabled: false` |

Quiet mode also hides the goal bar, but players can still ask anything at any time.

## Background observation

In assist mode the plugin schedules at most one low-resolution **32×18** geometry observation within 24 blocks every 30 seconds, for later `recall_surroundings`. Background observation:

- reads no textures and writes no PNGs;
- **never calls the model and costs nothing**;
- requires `pandagpt.vision` (OP only by default), so regular players are not observed just because the plugin was upgraded;
- pauses automatically under load; see [Performance](Performance.md).

<!-- nav:bottom -->
---

<div align="center">

⬅️ [📜 Skills & Prompts](Skills-and-Prompts.md) &nbsp;·&nbsp; [📚 Contents](Home.md) &nbsp;·&nbsp; [👁️ Vision & Perception](Vision-and-Perception.md) ➡️

</div>
<!-- /nav:bottom -->
