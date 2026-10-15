<!-- nav:top -->
<div align="right">

🌐 [简体中文](../zh/Talking-to-Panda.md) · **English** · 📖 [README](../../../README.en.md) · 🏠 [Wiki home](Home.md)

</div>
<!-- /nav:top -->

# 💬 Talking to Panda

> **Players have no commands at all.** Start a chat message with `panda` (or `@panda`) and talk as you would to a friend.

## Trigger rules

- Only the **start** of a message is matched, followed by a space, comma or colon: `panda hi`, `@panda, take a look`, `Panda: where am I`.
- "panda" in the middle of a message never triggers. Change the word with `panda.trigger_word`.
- The triggering message is hidden by default (`cancel_chat_message: true`), so other players don't see the question.
- Each player has at most one turn in flight and a cooldown (5 seconds by default).
- Chat events already cancelled by other plugins (mutes, for example) are respected.

While a turn runs, the action bar shows what Panda is doing, e.g. `👀 Looking…`, `🏗 Building a preview…`, `💰 Checking your balance…`.

## Examples

### 🧭 Places and navigation

| You say | Panda |
|---|---|
| `panda remember this as home` | Saves your position as "home" |
| `panda remember this chest` | Looks first to confirm which chest you mean, then saves its exact block |
| `panda where's my home?` | Distance, height difference and which way to turn |
| `panda take me home` | Starts particle guidance with an action-bar arrow; ends on arrival |
| `panda what places do you remember?` | Lists places in the current world |
| `panda forget the mine entrance` | Deletes that place |

### 👁️ Seeing and understanding

| You say | Panda |
|---|---|
| `panda what's in front of me?` | Reconstructs your view and identifies blocks and objects |
| `panda how do I use that furnace on the left?` | Locks onto the furnace by object ID, outlines it with private particles and explains |
| `panda is it safe around here?` | Checks mobs, time and light against your health and gear |
| `panda what was here a moment ago?` | Recalls the latest background observation |

### ⚒️ Crafting and goals

| You say | Panda |
|---|---|
| `panda I want an iron pickaxe` | Sets a goal with a progress bar, checks this server's real recipes and tells you what's missing |
| `panda what can I craft right now?` | Lists what your inventory can make immediately |
| `panda I'm going mining` | Checks torches, pickaxes and food, and reminds you when you leave home |

### 🏗️ Creative and everyday

| You say | Panda |
|---|---|
| `panda design a 7×7 wooden cabin here` | Previews it with ghost blocks only you can see and lists missing materials |
| `panda remind me to harvest wheat in ten minutes` | Real-time reminder |
| `panda tell me to go home before dark` | Game-event reminder (sunset / nightfall / sunrise) |
| `panda where did I just die? Is my stuff still there?` | Death location, cause, drops and time left, and can guide you back |
| `panda note that I like Japanese-style builds` | Long-term note that later advice takes into account |
| `panda how long will this sword last?` | Durability, enchantments, repair cost |
| `panda who nearby sells Mending?` | Lists visible villager and wandering-trader trades |
| `panda how many diamonds did I mine today?` | Session and lifetime statistics |
| `panda which advancement should I go for next?` | Unfinished advancements, closest first |

### 🏙️ Server and plugins

| You say | Panda |
|---|---|
| `panda can I claim land here?` | Answers only from `knowledge.md` and installed plugins, never invents |
| `panda how much money do I have? Can I afford a diamond sword?` | Reads your Vault balance |
| `panda whose land is this?` | Queries WorldGuard / GriefPrevention / Towny / Lands |
| `panda what do I need for the next rank?` | Reads LuckPerms promotion tracks |
| `panda who broke my chest?` | Queries CoreProtect block history (permission required) |
| `panda take me to spawn` | Proposes `/spawn` with confirm buttons; click or reply "do it" |

### 🤫 Managing the conversation

| You say | Effect |
|---|---|
| `panda don't disturb me` | Quiet mode: no proactive tips, goal bar hidden |
| `panda you can remind me again` | Back to assist mode |
| `panda forget our conversation` | Clears chat history; places, goals and notes stay |

## When you say nothing

- 🛏️ Sleeping in a bed or using a respawn anchor saves your respawn point as "home". A home you named yourself takes priority.
- 🔔 The [Proactive Assistant](Proactive-Assistant.md) may say one short sentence at worthwhile moments (goal materials ready, far from home at nightfall, drops about to despawn after a death), but stays quiet most of the time.
- ⚠️ Urgent local risks such as drops or lava appear in the action bar immediately, without waiting for the model.
- 👀 Low-frequency background observation of nearby geometry for later recall; it **never calls the model** and costs nothing.

## Multi-step tasks and follow-ups

Try `panda help me prepare for the Nether; check equipment and supplies and follow up if useful`. The model can keep a plan and choose bounded checks later or after changed observations. It still decides whether new evidence warrants a message. Simple questions need no ongoing plan. Follow-up turns use the model; working plans do not survive restart.

Ask a new question when priorities change; the older background turn and pending check are canceled. Say "don't disturb me" to stop proactive interruptions. "Remind me in ten minutes" still uses the reminder feature. See [Agent Runtime](Agent-Runtime.md).

## Answer language

Panda answers in the language you asked in; when unclear it uses your client language. The plugin's own messages follow your client language too; see [Languages](Languages.md).

<!-- nav:bottom -->
---

<div align="center">

⬅️ [🤖 Model Providers](Model-Providers.md) &nbsp;·&nbsp; [📚 Contents](Home.md) &nbsp;·&nbsp; [🧠 Agent & Tools](Agent-and-Tools.md) ➡️

</div>
<!-- /nav:bottom -->
