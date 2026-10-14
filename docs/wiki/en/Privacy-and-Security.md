<!-- nav:top -->
<div align="right">

🌐 [简体中文](../zh/Privacy-and-Security.md) · **English** · 📖 [README](../../../README.en.md) · 🏠 [Wiki home](Home.md)

</div>
<!-- /nav:top -->

# 🛡️ Privacy & Security

## What is sent to the model

| Data | When |
|---|---|
| The player's question and recent rounds | Every turn |
| Compact snapshot: name, health, hunger, game mode, dimension, position, facing, held item | Every turn |
| Places, goals and notes summary | Every turn (up to 12 places in the current world by default) |
| Relevant game evidence, source, world and time | Up to 3 attached to each question; additional records retrieved on demand |
| Complete memory evidence (up to 12 records per category, 24,000 characters total) | Background semantic model requests when distillation is enabled; one running worker |
| Inventory, nearby mobs, recipes, doc excerpts, plugin data | When the agent calls the matching tool |
| Vision reconstruction image | When the agent calls `look` and `vision: true` |

Data goes **only to the model provider the server owner configured**. Tell your players about it in your rules or privacy notice.

## What it never does

- ❌ Read other players' inventories or chests
- ❌ Store a full world map
- ❌ Keep images in history
- ❌ Execute any command text from a model reply
- ❌ Run commands as OP or the console
- ❌ Resolve PAPI placeholders whose names contain `ip`, `address`, `password`, `token`, `email` or `uuid`
- ❌ Trigger chunk loading

## Prompt-injection defences

- Tool results, server docs and player snapshots are **explicitly labelled as data, not instructions**;
- State changes only happen through [command proposals](Command-Proposals.md) the player confirms;
- Proactive turns can only use read-only tools;
- `focus_object` accepts only IDs from the latest `look` and re-verifies them on the server; model-invented coordinates are rejected.

## Keys and networking

- Keys can live only in environment variables (`PANDAGPT_<PROVIDER>_API_KEY`) and never touch disk;
- `Settings.toString()` and all error messages exclude keys, headers and raw responses;
- Endpoints must be HTTPS without URL credentials, query or fragment;
- The HTTP client never follows redirects; proxy credentials are only sent to the configured proxy host;
- Official textures come only from Mojang's HTTPS hosts with SHA-1 and size checks, and **classes in the client JAR are never loaded**.

## Logging

- By default only UUIDs and events are logged, **never conversation content**;
- `panda.logging.conversations: true` enables logging of raw questions and answers;
- Logs are UTF-8 and rotate by size.

Deaths and advancements are saved as game memories. Explicitly saved player messages retain the complete original message, including negation and conditions; assistant replies do not automatically become long-term evidence. Disable `panda.memory.enabled` to stop game recording and recall while keeping existing data. Persistent memory, place and note deletion requires a preview and a subsequent complete player confirmation phrase. Semantic summaries and experience relationships remain evidence-linked model interpretations.

## Local files

| Path | Content |
|---|---|
| `memory/<UUID>.json` | Places, goals, notes, game events, facts, plans, lessons and distilled indexes (v3; atomic writes; corrupt files preserved) |
| `captures/<UUID>/` | Latest vision capture (disable with `save_images: false`) |
| `logs/` | Event logs |

None of these are served over HTTP.

<!-- nav:bottom -->
---

<div align="center">

⬅️ [🚀 Performance](Performance.md) &nbsp;·&nbsp; [📚 Contents](Home.md) &nbsp;·&nbsp; [❓ FAQ](FAQ.md) ➡️

</div>
<!-- /nav:bottom -->
