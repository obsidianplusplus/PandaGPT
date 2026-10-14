<!-- nav:top -->
<div align="right">

🌐 [简体中文](../zh/Plugin-Integrations.md) · **English** · 📖 [README](../../../README.en.md) · 🏠 [Wiki home](Home.md)

</div>
<!-- /nav:top -->

# 🔌 Plugin Integrations

PandaGPT hooks into popular plugins as **soft dependencies**: each plugin you install gives the agent another capability, and when it's absent the related tools and skills hide themselves. **No configuration needed.**

## Supported plugins

| Plugin | Integration id | What the agent can do | Example |
|---|---|---|---|
| 💰 **Vault** (with EssentialsX, CMI and other economies) | `economy` | Balance, affordability, groups and chat prefix | "Can I afford a diamond sword?" |
| 🧩 **PlaceholderAPI** | `placeholders` | Resolve placeholders: quests, jobs, shops, leaderboards, stats from thousands of expansions | "Did I finish my daily quest?" |
| 🎖️ **LuckPerms** | `ranks` | Groups, inheritance, prefix/suffix, expiry, promotion tracks, permission checks | "How do I reach VIP?" |
| 🛡️ **WorldGuard** | `regions` | Current regions, owner/member, flags, build and PvP | "Is PvP allowed here?" |
| 🏡 **GriefPrevention** | `claims` | Claim owner, trust level, your claims and remaining claim blocks | "How much more can I claim?" |
| 🏰 **Towny** | `towny` | Town and nation, rank, town bank, plot under you and its price | "How much is this plot?" |
| 🗺️ **Lands** | `lands` | Land owner, trust and role, your lands | "Whose land is this?" |
| 🏠 **EssentialsX** | `essentials` | Homes, warps (with coordinates and distance), AFK, mail, mute, jail, /back | "What homes do I have?" |
| 🕵️ **CoreProtect** | `block-history` | Who recently placed or broke a block (needs `pandagpt.blockhistory`) | "Who broke my chest?" |
| ⚔️ **mcMMO** | `mcmmo` | Skill levels, XP to next level, power level | "How far am I from the next Excavation level?" |
| ✨ **AuraSkills** | `auraskills` | Skill levels, stats, mana | "Which stat should I level?" |

## Plugins without a dedicated adapter

**Jobs, Quests, BetonQuest, shops, auction houses, daily rewards, leaderboards…** still work through:

1. **PlaceholderAPI**: almost every plugin exposes PAPI placeholders, readable via `resolve_placeholders`;
2. **`server_plugins`**: what's installed;
3. **`list_server_commands`**: commands the player may use;
4. **`knowledge.md`**: your own gameplay notes.

The `quests-and-jobs` and `teleport` skills teach the agent to combine these.

## Design principles

| Principle | Detail |
|---|---|
| 🔒 **Read-only** | Integrations read only the asking player's data or public information |
| ✋ **State changes need confirmation** | Payments, teleports and claims go through [command proposals](Command-Proposals.md) the player confirms |
| 🧩 **Lazy loading** | Adapters load by class name, touching a plugin's API only when it is enabled |
| 🩹 **Version mismatches don't crash** | A failing adapter logs a warning and is skipped; startup continues |
| 📦 **No bundled plugin code** | APIs are compile-only; the server's installed plugins are used at runtime |

## Configuration

```yaml
panda:
  integrations:
    disabled: []                  # e.g. [economy, block-history]
    placeholder_denylist: ['ip', 'address', 'password', 'token', 'email', 'uuid']
    block_history_days: 7         # how far back CoreProtect queries go
```

- **`disabled`** accepts `economy placeholders ranks regions claims towny lands essentials block-history mcmmo auraskills`.
- **`placeholder_denylist`**: placeholders whose names contain these fragments are never resolved, protecting IPs, emails and so on.
- **CoreProtect** lookups run off the main thread and require `pandagpt.blockhistory` (OP by default).

## Making a skill depend on a plugin

Custom skills can declare `requires` and stay hidden until the plugin is present:

```markdown
---
name: shop-guide
description: How to use this server's shop plugin and common prices.
tools: resolve_placeholders, search_server_knowledge
requires: placeholders, ShopGUIPlus
---
```

`requires` accepts integration IDs or plugin names (as in their `plugin.yml`).

<!-- nav:bottom -->
---

<div align="center">

⬅️ [✨ Creative Features](Creative-Features.md) &nbsp;·&nbsp; [📚 Contents](Home.md) &nbsp;·&nbsp; [⚡ Command Proposals](Command-Proposals.md) ➡️

</div>
<!-- /nav:bottom -->
