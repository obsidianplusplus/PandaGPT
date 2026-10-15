<!-- nav:top -->
<div align="right">

🌐 [简体中文](../zh/Development.md) · **English** · 📖 [README](../../../README.en.md) · 🏠 [Wiki home](Home.md)

</div>
<!-- /nav:top -->

# 🛠️ Development

## Building

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

- Requires JDK 25 and Maven 3.9+;
- Paper API is pinned to `26.3.build.175-stable`; change `paper.version` and re-verify when updating;
- Plugin APIs are `provided` (compile-only) and never shaded into the JAR; Gson is relocated to `cn.panda.pandagpt.shaded.gson`.

### Testing with real textures

```bash
# Prepare official textures with the plugin's own downloader
mvn -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt -Dmdep.includeScope=compile
java -cp "target/classes:$(cat cp.txt)" cn.panda.pandagpt.vision.texture.VanillaAssets 26.3 /tmp/vanilla-26.3.zip

# Run the tests that need official assets
mvn -Dpandagpt.test.assets=/tmp/vanilla-26.3.zip clean verify
```

Without the property, official-asset tests are skipped and everything else runs. GitHub Actions downloads the assets and runs the full suite.

## Code layout

```text
cn.panda.pandagpt
├── agent/          agent loop, session task runtime, tools, skills and prompts
│   └── tools/      built-in tools grouped by domain
├── ai/             wire formats: OpenAI-compatible, Gemini, Anthropic; HTTP client
├── integration/    plugin adapters (lazy-loaded by class name)
├── features/       ghost preview, guidance, reminders, death log, statistics
├── perception/     perception, goals, place memory, proactive policy
├── vision/         raycast reconstruction, rendering, textures, official asset download
├── actions/        command proposals and confirmation
├── service/        conversation service and sessions
├── config/         immutable settings snapshot and providers
├── i18n/           language files and localisation
└── listeners/      event listeners
```

## Adding a tool

1. Define ordinary synchronous tools with `AgentTool.of`; use `AgentTool.async` for multi-tick or asynchronous results. Unmarked tools form serial execution barriers.
2. Only independent reads should use `readOnlyOf`, `readOnlyAsync` or the `readOnly` wrapper to declare `independentRead()`. Never label gameplay/player mutations as queries.
3. Register the tool in `ToolRegistry` and list it under a skill's `tools:`. The three built-in runtime tools have separate compatibility handling for old core headers.
4. Proactive turns automatically consider independent reads from enabled skills and check permission and `available`. `AssistantService.PROACTIVE_TOOLS` retains compatibility for older reads; new independent queries need no extra manual entry there.
5. Tool entry still runs on the server thread. Copy Bukkit data before handing immutable values to background work. Returned futures must support cancellation; tools must not continue mutating the world after returning.
6. `SkillLibraryTest` checks ownership. Add meaningful permission-change, failure, size and cancellation coverage as appropriate; independent queries also need concurrency-bound and result-order checks.

## Adding a plugin integration

1. Implement `Integration` under `integration/`. For compile-time APIs use `provided` and exclude transitive dependencies; for reflective public APIs add no vendor dependency and use the installed plugin's ClassLoader.
2. New reflective adapters use `validate(Plugin)` to check fixed public classes, method signatures and return types. Validation must not load players, create profiles or access worlds. Skip incompatible adapters with a warning.
3. Register the id, plugin name, class and description in `Integrations.SPECS`, and add `softdepend` in `plugin.yml`.
4. Create a skill with `requires: <integration id>`. Known integration IDs are available only after successful adapter activation; raw plugin presence cannot bypass failed validation.
5. Filter by native permissions and the caller's identity, with fixed scan/output limits. Missing evidence returns unknown or an explicit error. Only independent reads qualify for concurrency.
6. Cover skill/registration, permissions and incompatibility, then update bilingual support tables and configuration IDs. Never bundle vendor implementations in the JAR.

## Runtime responsibilities

- `AgentRuntime` keeps server-thread working plans, steps, phases and one-shot checks; it neither reads Bukkit nor persists long-term evidence.
- `RuntimeTools` validates the original player request and plan arguments; proactive turns can only revise an existing objective and cannot refill the allowance.
- `AssistantService` owns player rotation, context, background-turn identity, preemption, session cleanup and delivery checks.
- `AgentRunner` owns the multi-step model loop, independent-read concurrency, shared deadline and per-step text budget; cancellation reaches the client wait.
- `ChatGPTIntegration` advances runtime rotation only when shared background work is allowed. Model-chosen waits must not block the server thread with sleep.

Focused regression command:

```bash
mvn -Dtest=AgentRuntimeTest,RuntimeLimitsTest,AssistantServiceTest,ReadConcurrencyTest,SettingsTest test
```

These use scripted models and a mocked server to verify boundaries and scheduling. They do not replace long-running tests with real Paper, vendor plugins and paid models. Run the full `verify` command above for complete validation.

## Adding a model provider

- **OpenAI-compatible**: add one line to `config.Provider` (id, default URL, default model, image support) and a block in `config.yml`;
- **A new wire format**: implement `ai.WireFormat`, add a `Provider.Wire` value, and cover multi-turn tool calls and images in `HttpAiProviderTest`.

## Conventions

- Formatting: `mvn spotless:apply` (palantir-java-format); `mvn verify` checks it.
- Comments are written in Chinese: types use `/** One-line responsibility. 线程：…… */` describing duties and threading; inline comments sit above the code and explain *why*.
- No hard-coded player- or console-facing text: use `Lang` / `Msg` with language-file keys. New keys must be added to all 12 files; `MessagesTest` checks keys and placeholders match.
- Game state is read only on the main thread; workers handle immutable data only.

## Pull requests

Issues and pull requests are welcome. Make sure `mvn clean verify` passes and describe how you tested on a real server.

<!-- nav:bottom -->
---

<div align="center">

⬅️ [❓ FAQ](FAQ.md) &nbsp;·&nbsp; [📚 Contents](Home.md)

</div>
<!-- /nav:bottom -->
