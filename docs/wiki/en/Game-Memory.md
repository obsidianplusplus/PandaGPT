<div align="right">

🌐 [简体中文](../zh/Game-Memory.md) · **English** · 📖 [README](../../../README.en.md) · 🏠 [Wiki home](Home.md)

</div>

# 🧠 Game memory and memory distillation

Both features follow one standard: **recover the right thing when the player actually needs it.**

Players talk to `panda`. Server-observed deaths and advancements, explicitly saved experiences, preferences, plans and lessons, existing places, notes and the current goal are searchable. Evidence is isolated by player UUID and persisted in `memory/<UUID>.json` across restarts.

Examples: “remember that I prefer Chinese architecture”, “remember my Nether mining plan”, “why did I die last time?”, “that mining plan is finished”, “organize my memories”, and “forget my architecture preference”. Answers must follow retrieved evidence. A missing match means there is no reliable record; ambiguous references require clarification.

## Four independent pipelines

| Pipeline | Evidence | Rules |
|---|---|---|
| `episodes` | Deaths, advancements and explicitly saved experiences | Preserve distinct occurrences and timestamps |
| `facts` | Stated preferences, places and facts | Keep the latest version per topic and world; retain older evidence for historical queries |
| `plans` | To-dos, the current crafting goal and stated plans | Completion/cancellation prevents older plans from resurfacing as active |
| `lessons` | Lessons explicitly stated by the player | Keep the latest version; never infer a lesson from an isolated event |

Each pipeline reads original evidence with its own deduplication rules and output budget, without consuming another pipeline's summary. Changes automatically run through the ordered background I/O queue; players can also request organization. This implementation uses **extractive distillation**, without extra model requests or invented facts.

Each category keeps at most 96 evidence records, and each pipeline produces at most 24 indexed cards. Frequent episodes cannot consume other categories' quotas. Retrieval also searches raw evidence, so older events outside the card budget remain searchable while retained. Evidence beyond the retention limit is evicted.

## Retrieval and correctness

- Match specific keywords using English words and Chinese bigrams. Semantic/vector retrieval is not part of this implementation.
- Search the current world and global personal memories by default; search other worlds when explicitly requested.
- Rank by relevance, using time only to break ties. An unrelated recent event cannot substitute for the requested event.
- Return original text, source, time, world, position, pipeline and status. Cards keep evidence IDs.
- Exclude superseded, expired and resolved records by default; historical queries can include them.
- Mark changed places as `needs_verification`. Past drops or inventory-related statements cannot prove current world state.

At most three relevant memories are attached to each question within a character budget. The agent calls `recall_game_memory` for full evidence. Missing matches should prompt more specific keywords or an honest acknowledgement.

## Evidence, updates and deletion

`remember_game_memory` must quote an exact passage from the current player message. Assistant answers, guesses and unexecuted command proposals cannot become player evidence. Updates reuse the same topic and scope; personal preferences usually use global scope.

`forget_game_memory` deletes the evidence ID and older versions of the same fact, plan or lesson. Linked places and notes are also deleted so later distillation cannot recreate them. Use `clear_goal` for the current crafting goal. `forget_conversation` clears short-term chat only.

New mutation tools report success only after atomic persistence. Corrupt files, unsupported versions, failed loads and unavailable queues return failures. Legacy v1 files are readable and become v2 on their next successful write. Do not downgrade upgraded data to a plugin supporting only v1.

## Configuration

```yaml
panda:
  memory:
    enabled: true
    distillation: true
    recall_limit: 6
    context_chars: 1800
```

`enabled` controls game memory independently of vision/perception and preserves existing data when disabled. `distillation: false` searches raw evidence without generating cards. `recall_limit` accepts 1–12 results; `context_chars` accepts 256–4000 characters. Loaded-player capacity uses `panda.perception.max_players`. Disable the tools with `panda.agent.disabled_skills: [game-memory]`.

Existing custom `prompts/system.md` files are preserved on deployment; merge the updated memory rules into customized prompts. Relevant memories are sent to the configured model provider as context or tool results; see [Privacy & Security](Privacy-and-Security.md).
