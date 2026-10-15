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
| 🧑 **Citizens** | `citizens` | Visible nearby NPC names, IDs, positions and distances, respecting line of sight and PlayerFilter | "Which NPC is nearby?" |
| 🛒 **Shopkeepers** | `shopkeepers` | Visible nearby entity shops; actual offers and enchantments in the caller's active trading UI | "How much is this shop's Mending book?" |
| 📜 **Quests (PikaMug)** | `quests` | Caller-only loaded active quests, stages and native objective counters | "What stage is my quest on?" |
| 🏝️ **BentoBox** | `bentobox` | The caller's islands, protection range and team role; island at the current position | "How big is my island?" |
| 🎨 **ItemsAdder** | `itemsadder` | Own held/equipped custom item ID, description, custom durability and uses | "How many uses does this custom weapon have left?" |
| 🧰 **Oraxen** | `oraxen` | Own held/equipped Oraxen ID, actual name, lore and vanilla damage | "Which custom item am I holding?" |
| ⛏️ **Jobs Reborn** | `jobs` | Own profession levels/XP and a catalogue filtered by native join permissions | "How much XP until my next Miner level?" |
| 🌐 **Multiverse-Core 5** | `multiverse` | Visible worlds, native access scope, game mode, PvP, flight and entry fee | "Which worlds are available?" |

## Scope and permissions of the new adapters

- **Citizens** reads spawned entities from the default NPC registry within 1–32 blocks, inspecting at most 256 nearby entities and returning 12 NPCs. World, visibility, line-of-sight and native PlayerFilter checks apply. Results do not prove interaction eligibility.
- **Shopkeepers** requires `shopkeeper.trade` and any native admin-shop trade permission. Nearby discovery covers entity shops, not sign/block/virtual shops. Offers are read only from the caller's already-open valid active trading session and actual MerchantInventory, including adjusted prices and enchantments. No shop opening, stock-container reads or trades occur.
- **Quests** supports the PikaMug Quests 5.x API and requires `quests.quest`. It reads existing records, scans at most 2048 loaded questers, and returns up to 10 quests with 24 objectives each. Missing data is unknown. Native counters do not include every location/custom/wait condition; `/quest` remains authoritative for full descriptions.
- **BentoBox** reads core island data, checking each game mode's `<prefix>island.info` permission. UUID-scoped personal lookup may hydrate the caller's cold native cache; at most 128 personal records are inspected and 12 shown. Visitor output hides centers and member identities. BSkyBlock/AOneBlock core data is supported; Level, Challenges and OneBlock-specific phases are not included.
- **ItemsAdder / Oraxen** identify only the caller's mainhand, offhand or four armor slots, passing a cloned stack to the API. ItemsAdder returns its namespaced ID; Oraxen keeps its actual item ID. Names/lore are bounded. No item granting, creation or resource-pack reloads occur.
- **Jobs Reborn** progress requires `jobs.command.stats`; the catalogue requires `jobs.command.browse`, plus both `jobs.use` and `jobs.join.<lowercase job>` for each entry. Only the caller's cached profile is read. Catalogue scans are limited to 256 entries and 32 results. Join permission does not prove other limits, cooldowns or payout requirements.
- **Multiverse-Core 5** listing requires `multiverse.core.list.worlds`; current-world settings require `multiverse.core.info`. Native access enforcement/permissions, hidden flags and destination-world source blacklists are honored. Up to 256 worlds are inspected and 32 returned. No worlds/chunks are loaded; unloaded spawn positions are withheld. World settings do not override other plugins or guarantee teleport eligibility.

New adapters verify fixed public API signatures through the installed plugin's ClassLoader. Missing classes/methods or incompatible return types disable the adapter with a warning; runtime failures return explicit errors. All new tools are independent reads under the bounded concurrency policy. Unit tests and upstream API checks cover these contracts; live Paper testing with all vendor plugins has not been performed.

API references: [Citizens](https://wiki.citizensnpcs.co/API), [Shopkeepers](https://github.com/Shopkeepers/Shopkeepers/tree/master/modules/api), [Quests](https://github.com/PikaMug/Quests/tree/master/api), [BentoBox](https://docs.bentobox.world/en/latest/BentoBox/Developer-Documentation/), [ItemsAdder](https://lonedev6.github.io/API-ItemsAdder/dev/lone/itemsadder/api/CustomStack.html), [Oraxen](https://docs.oraxen.com/developers/api), [Jobs](https://github.com/Zrips/Jobs/wiki/API), [Multiverse](https://mvplugins.org/core/developers/developer-api-starter/).

## Plugins without a dedicated adapter

**BetonQuest, other shops, auction houses, daily rewards, leaderboards…** still work through:

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
| 📦 **No bundled plugin code** | Existing compile APIs stay provided; new adapters use validated public native reflection. Vendor code comes from the installed plugin |

## Configuration

```yaml
panda:
  integrations:
    disabled: []                  # e.g. [economy, block-history]
    placeholder_denylist: ['ip', 'address', 'password', 'token', 'email', 'uuid']
    block_history_days: 7         # how far back CoreProtect queries go
```

- **`disabled`** accepts `economy placeholders ranks regions claims towny lands essentials block-history mcmmo auraskills citizens shopkeepers quests bentobox itemsadder oraxen jobs multiverse`.
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
