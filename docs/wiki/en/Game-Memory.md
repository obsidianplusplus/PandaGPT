<!-- nav:top -->
<div align="right">

🌐 [简体中文](../zh/Game-Memory.md) · **English** · 📖 [README](../../../README.en.md) · 🏠 [Wiki home](Home.md)

</div>
<!-- /nav:top -->

# 🧠 Game memory and memory distillation

Both features follow one standard: **recover the right thing when the player actually needs it.**

Players talk to `panda`. Server-observed deaths and advancements, explicitly saved experiences, preferences, plans and lessons, existing places, notes and the current goal are searchable. Evidence is isolated by player UUID and persisted in `memory/<UUID>.json` across restarts.

Examples: “remember that I prefer Chinese architecture”, “remember my Nether mining plan”, “why did I die last time?”, “that mining plan is finished”, “organize my memories”, and “forget my architecture preference”. Answers must follow retrieved evidence. A missing match means there is no reliable record; ambiguous references require clarification.

Temporary steps and background checks belong to the [agent runtime](Agent-Runtime.md) and do not automatically become persistent evidence here. A plan recorded in game memory does not automatically schedule a runtime check.

## Independent indexes and semantic distillation

| Pipeline | Evidence | Rules |
|---|---|---|
| `episodes` | Deaths, advancements and explicitly saved experiences | Preserve distinct occurrences and timestamps |
| `facts` | Stated preferences, places and facts | Keep the latest version per topic and world; retain older evidence for historical queries |
| `plans` | To-dos, the current crafting goal and stated plans | Completion/cancellation prevents older plans from resurfacing as active |
| `lessons` | Lessons explicitly stated by the player | Keep the latest version; never infer a lesson from an isolated event |

The four indexes handle deduplication and version selection with separate budgets. A separate background model pass reads original evidence and produces up to 32 semantic summaries and 64 explained relationships: continuation, shared project, contrast, plan outcome, experience informing a plan, lesson source and possible cause. A lava death and a later fire-resistance preparation plan can therefore be retrieved together. Summaries and relationships retain evidence IDs and remain interpretations; causal hypotheses never establish verified causes or player emotions.

Evidence changes enqueue a model pass, coalescing requests for the same player. Stale snapshots trigger a fresh pass. One global semantic worker has a queue of 32 jobs. Each pass reads up to the latest 12 records per category within 24,000 characters of whole evidence; it never feeds previous summaries back as evidence. This adds requests and costs at the configured provider. Failures preserve raw evidence and committed interpretations. Commit checks reject changed evidence, disabled modules and replaced providers.

Each category keeps at most 96 evidence records, and each pipeline produces at most 24 indexed cards. Frequent episodes cannot consume other categories' quotas. Retrieval also searches raw evidence, so older events outside the card budget remain searchable while retained. Evidence beyond the retention limit is evicted.

## Retrieval and correctness

- Match English words and Chinese bigrams against original evidence, semantic summaries and generated retrieval cues. Expand one relationship hop while respecting scope, category, status and result limits. This does not use an embedding/vector index.
- Search the current world and global personal memories by default; search other worlds when explicitly requested.
- Rank by relevance, using time only to break ties. An unrelated recent event cannot substitute for the requested event.
- Return original text, source, time, world, position, pipeline and status. Cards keep evidence IDs.
- Exclude superseded, expired and resolved records by default; historical queries can include them.
- Mark changed places as `needs_verification`. Past drops or inventory-related statements cannot prove current world state.

At most three relevant memories are attached to each question within a character budget. Long results return references; query an exact evidence UUID to retrieve one complete record without expanding relationships. The agent calls `recall_game_memory` for full evidence. Missing matches should prompt more specific keywords or an honest acknowledgement.

## Evidence, updates and deletion

`remember_game_memory` must preserve the complete current player message verbatim (up to 4000 characters), retaining negation, conditions, hypotheticals and references. Substrings are rejected. Evidence and deletion previews exceeding the tool budget are rejected rather than silently truncated; narrow the query or increase the budget. Assistant answers, guesses and unexecuted command proposals cannot become player evidence. Updates reuse the same topic and scope; personal preferences usually use global scope.

`forget_game_memory` first previews every affected record and issues an exact confirmation phrase; nothing is deleted at this stage. A subsequent complete player message matching that phrase authorizes deletion with a one-use token bound to the player, evidence ID/set and data revision, valid for five minutes. Changed evidence, reloads and restarts require a new preview. `forget_place` and `remove_note` use the same authorization and atomic deletion path. Deletion removes selected evidence, dependent summaries/relationships and older versions of the same fact, plan or lesson. Linked places and notes are also deleted so later distillation cannot recreate them. Use `clear_goal` for the current crafting goal. `forget_conversation` clears short-term chat only.

New mutation tools report success only after atomic persistence. Corrupt files, unsupported versions, failed loads and unavailable queues return failures. Legacy v1/v2 files are readable and become v3 on their next successful write. Original full conversations cannot be reconstructed from old excerpts. Do not downgrade upgraded data to a plugin supporting only v1/v2.

## Configuration

```yaml
panda:
  memory:
    enabled: true
    distillation: true
    distill_output_tokens: 4096
    recall_limit: 6
    context_chars: 1800
```

`enabled` controls game memory independently of vision/perception and preserves existing data when disabled. `distillation` enables indexes and additional background semantic model requests; `false` searches raw evidence only. `distill_output_tokens` is separate from the chat output budget (default 4096, range 512–8192); each pass limits generation to fit. `recall_limit` accepts 1–12 results; `context_chars` accepts 256–4000 characters. Loaded-player capacity uses `panda.perception.max_players`. Disable the tools with `panda.agent.disabled_skills: [game-memory]`.

Existing custom `prompts/system.md` files are preserved on deployment; merge the updated memory rules into customized prompts. Relevant memories are sent to the configured model provider as context or tool results; see [Privacy & Security](Privacy-and-Security.md).

Independent read-only tools overlap up to `panda.agent.max_parallel_reads` (default 3, range 1–8). Writes, deletions and unmarked tools are barriers; results preserve call order. Bukkit access stays on the server main thread.

<!-- nav:bottom -->
---

<div align="center">

⬅️ [🧭 Agent Runtime](Agent-Runtime.md) &nbsp;·&nbsp; [📚 Contents](Home.md) &nbsp;·&nbsp; [📜 Skills & Prompts](Skills-and-Prompts.md) ➡️

</div>
<!-- /nav:bottom -->
