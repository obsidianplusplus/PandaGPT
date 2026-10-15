<div align="right">

🌐 [简体中文](CHANGELOG.md) · **English** · 📖 [README](README.en.md)

</div>

# Changelog

Player-facing and server-owner changes, configuration updates and upgrade notes, with the latest changes first.

## Unreleased · 2026-10-14

### Added

- Added 8 ecosystem adapters: Citizens, Shopkeepers, Quests, BentoBox, ItemsAdder, Oraxen, Jobs Reborn and Multiverse-Core 5, with 12 independent read tools and 8 on-demand skills. Totals: 19 integrations, 43 skills and 76 tools.
- New adapters validate native public API signatures before activation; incompatible plugins are skipped with a warning.

- **Game memory**: retrieve experiences, facts and preferences, plans and lessons when the player actually needs the right record.
- **Memory distillation**: four independent pipelines for episodes, facts, plans and lessons, each with its own deduplication rules and capacity budget, reading original evidence directly.
- Persistent server-observed deaths and advancements, with existing places, personal notes and the current crafting goal included in retrieval.
- The `game-memory` skill and four tools: `remember_game_memory`, `recall_game_memory`, `distill_memory` and `forget_game_memory`.
- Bilingual [Game Memory & Distillation documentation](docs/wiki/en/Game-Memory.md). Built-in skills increase to 35 and tools to 64.

### Behavior and reliability

- Isolate evidence by player UUID. Default retrieval covers the current world and global personal memories; other worlds require an explicit wider search.
- New memory writes must quote the current player message exactly. Assistant answers, guesses and unexecuted command proposals cannot become player evidence.
- Return original text, source, time, world, position, pipeline and status. Missing matches return unknown rather than an unrelated recent record.
- Superseded facts, plans and lessons appear only in historical queries. Expired records and completed or cancelled plans are excluded from active retrieval.
- Changed places require verification. Historical records cannot prove current chest contents or that death drops still exist.
- Forgetting removes linked places, notes and older versions of the same topic, preventing later distillation from recreating them. Use `clear_goal` for the current crafting goal.
- New memory mutation tools report success after persistence. Failed loads, corrupt files, unsupported formats and unavailable queues return failures.
- Release memory caches when players leave, including players without an initialized perception state.

### Configuration and capacity

| Configuration | Default | Description |
|---|---|---|
| `panda.memory.enabled` | `true` | Game memory and recall, independent of vision/perception |
| `panda.memory.distillation` | `true` | Distillation switch; disabling it searches raw evidence |
| `panda.memory.recall_limit` | `6` | Maximum results per retrieval, accepting `1–12` |
| `panda.memory.context_chars` | `1800` | Relevant-memory context budget, accepting `256–4000` characters |

- Attach at most three relevant game memories to each question.
- Retain at most 96 evidence records per category and 24 indexed cards per pipeline. Older raw evidence remains searchable while retained.
- Use extractive distillation and keyword retrieval with English words and Chinese bigrams, without extra model requests.

### Upgrade notes

- Replace the plugin JAR and restart the server. The project version remains `3.2.0`; this section records unreleased changes.
- Read legacy v1 memory files and upgrade to v2 on the next successful write. Do not downgrade upgraded data to plugins supporting only v1.
- Existing customized prompts are preserved. Merge the updated memory retrieval rules into customized `prompts/system.md` files.
- Relevant memories are sent to the configured model provider as context or tool results; [Privacy & Security](docs/wiki/en/Privacy-and-Security.md) has been updated.

### Validation

- The feature build and all 145 automated tests passed, including 14 new memory tests.
- Coverage includes restart recovery, player/world isolation, fact updates, plan completion, deletion followed by distillation, retrieval boundaries and corrupt-file protection.
- Plugin JAR generated; conversational behavior has not yet been tested on a live Minecraft server.
