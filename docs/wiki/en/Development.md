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
├── agent/          agent loop, tool registry, skills and prompts
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

1. Define it in `agent/tools/` with `AgentTool.of(name, description, parameter schema, availability, action-bar activity, handler)`; for multi-tick work use `AgentTool.async` with a handler returning a `CompletableFuture`;
2. Register it in the plugin's `ToolRegistry`;
3. List it under `tools:` in a skill, otherwise the model never sees it;
4. If it is read-only and suits proactive turns, add it to `AssistantService.PROACTIVE_TOOLS`;
5. `SkillLibraryTest` checks that skills and tools match one to one.

## Adding a plugin integration

1. Add the API to `pom.xml` as `provided`, excluding transitive dependencies;
2. Implement `Integration` in `integration/`;
3. Register the id, plugin name, adapter class name and summary in `Integrations.SPECS`;
4. Create a skill with `requires: <integration id>`;
5. Add the plugin to `softdepend` in `plugin.yml`.

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
