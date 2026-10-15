<!-- nav:top -->
<div align="right">

🌐 [简体中文](../zh/Installation.md) · **English** · 📖 [README](../../../README.en.md) · 🏠 [Wiki home](Home.md)

</div>
<!-- /nav:top -->

# 📦 Installation & Upgrading

## Requirements

| Item | Requirement |
|---|---|
| Server | **Paper 26.3** (API version `26.3`); Spigot 1.12.2 is not supported and Folia compatibility is not declared |
| Java | **JDK 25** |
| Network | The server must reach your chosen model provider; on first start it also reaches Mojang to download textures |
| Model | A model with **function (tool) calling**; image input is needed for pixel-level vision |

## Three steps

### 1️⃣ Install the plugin

Download `PandaGPT-3.2.0.jar` from the GitHub Actions artifacts ([Actions](https://github.com/obsidianplusplus/PandaGPT/actions) → latest successful run → `PandaGPT-Paper-26.3`), or [build it yourself](#build). Put it in `plugins/` and restart. Remove the old JAR when upgrading.

The first start creates:

```text
plugins/PandaGPT/
├── config.yml          # main configuration
├── knowledge.md        # server documentation (rules, gameplay, plugins)
├── lang/*.yml          # 12 language files, editable
├── skills/*.md         # 43 skills: edit, add or disable
├── prompts/*.md        # system.md, proactive.md, runtime.md
├── assets/             # auto-downloaded official textures (vanilla-26.3.zip)
├── memory/<UUID>.json  # places, goals, notes and game memory (v3)
└── captures/<UUID>/    # latest vision capture (optional)
```

### 2️⃣ Add your model key

Edit `plugins/PandaGPT/config.yml`, pick a provider and paste its key:

```yaml
panda:
  model_selection:
    provider: 'openai'          # any of the 15 providers
    openai:
      api_key: 'sk-...'
      model: 'gpt-4.1-mini'
```

> 💡 Prefer not to store the key on disk? Set `PANDAGPT_<PROVIDER>_API_KEY`, for example `PANDAGPT_OPENAI_API_KEY`. A non-empty environment variable wins over the file.

See [Model Providers](Model-Providers.md) for every provider and how they differ.

### 3️⃣ Describe your server and reload

Write your rules, economy, claims, teleports and key plugins into `knowledge.md`, split by Markdown headings. The agent searches the relevant sections on demand instead of reading the whole file every time.

```text
/pandaadmin reload
/pandaadmin status
```

`status` shows whether the provider is configured, skill and tool counts, queues and vision state. Any player can now say `panda hello` in chat.

<a id="textures"></a>

## 🖼️ Official textures: downloaded automatically

Vision needs Minecraft's vanilla block models and textures. **The plugin handles this itself; no scripts required:**

1. On start or reload, if an entry in `panda.vision.textures.packs` named `vanilla-<version>.zip` is missing, a background download begins;
2. The version manifest and client come only from Mojang's official HTTPS hosts, and every step is checked against the **SHA-1 and size** Mojang publishes;
3. The client JAR is read as a ZIP only and **its code is never loaded or run**. Only blockstates, block models, block textures and colour maps are extracted (about 5,500 files);
4. Output goes to a temporary file and is atomically moved into place once verified. It takes effect immediately, with no reload needed.

Console output:

```text
[PandaGPT] Downloading and verifying 26.3 render assets from Mojang…
[PandaGPT] Official textures verified: extracted 5579 render assets to vanilla-26.3.zip.
```

| Situation | What to do |
|---|---|
| The server cannot reach Mojang | Let the plugin download once on another machine and copy `assets/vanilla-26.3.zip` over, or configure `panda.proxy` |
| You want a server resource pack on top | Add `assets/server-pack.zip` to `packs`; later packs override earlier ones |
| You don't want automatic downloads | `panda.vision.textures.auto_download: false` |
| You don't need vision at all | `panda.vision.enabled: false` |

Only entries named `vanilla-<version>.zip` are downloaded; your own packs are never touched.

## ⬆️ Upgrading

| From | What happens |
|---|---|
| **3.1 / 3.0** (config v2) | Backed up to `config-v2-backup.yml`; top-level `openai:` and `gemini:` keys and URLs move into `panda.model_selection.<provider>` |
| **2.x and older** (config v1) | Backed up to `config-v1-backup.yml`; `history_size` becomes conversation rounds; then the step above runs |
| Old `/panda ...` player commands | Removed since 3.0; players simply talk in chat |
| Memory files | Read legacy v1/v2, then upgrade to v3 on the next successful write; do not downgrade to a legacy-format plugin |
| `tools/download_vanilla_assets.py` | Removed in 3.2; the plugin downloads textures itself |

A legacy Gemini URL's `?key=%s` is stripped automatically; the key is now sent in a request header.

### Runtime upgrade behavior

On startup, upgrading fills missing `panda.agent.runtime` defaults and copies `prompts/runtime.md` if absent. Existing custom system/proactive prompts and skill files are preserved. The runtime template is appended to conversation and proactive turns. An older custom core `panda` skill can use the three runtime tools when enabled even if its header omits their names.

Defaults let AI schedule up to 3 automatic follow-up turns for a multi-step request; each turn can make multiple model calls. To keep plans without automatic checks, set `panda.agent.runtime.max_follow_ups: 0`; to disable the runtime, set `panda.agent.runtime.enabled: false`. Disabled or unavailable routine AI proactive turns now stay silent instead of sending canned fallback text. Immediate local-risk alerts still use the action bar.

Reloads and restarts clear working plans and pending checks rather than restoring tasks automatically. Persistent memory remains separate: legacy v1/v2 files become v3 on the next successful write; do not downgrade upgraded data to a plugin supporting only older formats. See [Agent Runtime](Agent-Runtime.md) and [Game Memory](Game-Memory.md).

<a id="build"></a>

## 🔨 Building from source

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

The JAR is written to `target/PandaGPT-3.2.0.jar`. See [Development](Development.md) for details.

<!-- nav:bottom -->
---

<div align="center">

⬅️ [🏠 Home](Home.md) &nbsp;·&nbsp; [📚 Contents](Home.md) &nbsp;·&nbsp; [🤖 Model Providers](Model-Providers.md) ➡️

</div>
<!-- /nav:bottom -->
