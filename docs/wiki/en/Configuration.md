<!-- nav:top -->
<div align="right">

🌐 [简体中文](../zh/Configuration.md) · **English** · 📖 [README](../../../README.en.md) · 🏠 [Wiki home](Home.md)

</div>
<!-- /nav:top -->

# ⚙️ Configuration

The configuration lives in `plugins/PandaGPT/config.yml` (currently `config_version: 3`). After editing, run `/pandaadmin reload`: the plugin **fully validates and builds the new modules first** and swaps them in only when everything succeeds; on any error the running service is kept and the reason is shown in chat and the console.

> 💡 New options added by an upgrade are filled into your file automatically; existing values are kept.

## Contents

[Language](#language) · [Agent](#agent) · [Conversation](#conversation) · [Model](#model) · [Requests](#requests) · [Perception](#perception) · [Vision](#vision) · [Features](#features) · [Integrations](#integrations) · [Commands](#commands) · [Logging](#logging) · [Proxy](#proxy)

## Language

| Key | Default | Meaning |
|---|---|---|
| `panda.language.default` | `zh_CN` | Language for the console and unmatched clients |
| `panda.language.per_player` | `true` | Show plugin messages in each player's client language |

See [Languages](Languages.md).

## Agent

| Key | Default | Range | Meaning |
|---|---|---|---|
| `panda.agent.max_steps` | 8 | 1–16 | Model requests per turn |
| `panda.agent.max_tool_calls` | 16 | 1–48 | Tool calls per turn |
| `panda.agent.turn_timeout_seconds` | 120 | 10–600 | Total time per turn, including all model requests and tools |
| `panda.agent.max_tool_result_chars` | 6000 | 500–32000 | Truncation length of one tool result |
| `panda.agent.proactive` | `true` | | Agent decides and words proactive tips; off uses built-in text |
| `panda.agent.disabled_skills` | `[]` | | Disabled skills, e.g. `[commands, vision]` |

## Conversation

| Key | Default | Meaning |
|---|---|---|
| `panda.trigger_word` | `panda` | Trigger word at the start of chat (`panda` or `@panda`); no spaces, must not start with `@` |
| `panda.personality` | (Chinese persona) | Persona written into the system prompt |
| `panda.cooldown` | 5000 | Per-player cooldown (ms) |
| `panda.cancel_chat_message` | `true` | Hide the chat message that triggered Panda |
| `panda.history_rounds` | 5 | Full Q&A rounds kept (0–50) |
| `panda.max_sessions` | 1000 | Sessions kept at once |
| `panda.session_ttl_seconds` | 1800 | Idle time before a session is cleared |
| `panda.max_prompt_chars` | 2000 | Max characters per question |
| `panda.context_char_budget` | 16000 | Context budget in characters (not exact tokens) |
| `panda.max_output_tokens` | 1000 | Max output tokens per step |

## Model

```yaml
panda:
  model_selection:
    provider: 'gemini'
    <provider>:
      api_key: ''
      api_url: '...'
      model: '...'
      vision: true
```

All 15 providers are listed in [Model Providers](Model-Providers.md). Keys can also come from `PANDAGPT_<PROVIDER>_API_KEY`.

## Requests

| Key | Default | Range | Meaning |
|---|---|---|---|
| `panda.requests.workers` | 4 | 1–32 | AI worker threads |
| `panda.requests.queue_size` | 32 | 1–1000 | Queue size; requests are rejected when full |
| `panda.requests.connect_timeout_seconds` | 10 | 1–60 | Connect timeout |
| `panda.requests.timeout_seconds` | 45 | 1–180 | Overall request time including retries |
| `panda.requests.max_response_bytes` | 1048576 | 1 KiB–4 MiB | Response body cap |
| `panda.requests.retries` | 1 | 0–3 | Retries only for 429/502/503/504 and connection failures |

## Perception

| Key | Default | Meaning |
|---|---|---|
| `panda.perception.enabled` | `true` | Master switch for perception, goals and place memory |
| `panda.perception.observe_seconds` | 30 | Interval of 32×18 background geometry observation |
| `panda.perception.scene_ttl_seconds` | 60 | Observation lifetime |
| `panda.perception.entity_radius` | 20 | Entity query radius |
| `panda.perception.max_entities` | 16 | Entities kept |
| `panda.perception.max_players` | 128 | Players with memory loaded at once |
| `panda.perception.proactive` | `true` | Produce proactive tips |
| `panda.perception.suggestion_seconds` | 900 | Minimum gap between routine tips |
| `panda.perception.routine_per_half_hour` | 2 | Routine tips per half hour; 0 disables |

## Vision

| Key | Default | Meaning |
|---|---|---|
| `panda.vision.enabled` | `true` | Master switch for vision captures |
| `panda.vision.width` / `height` | 160 / 90 | Capture resolution (16–320 / 16–180) |
| `panda.vision.vertical_fov` | 70 | Vertical field of view (30–110) |
| `panda.vision.max_distance` | 96 | Max ray distance (8–128) |
| `panda.vision.rays_per_tick` | 256 | Global rays per tick |
| `panda.vision.cells_per_tick` | 8192 | Global cells per tick |
| `panda.vision.time_budget_ms` | 2 | Soft time budget per tick (0.25–5) |
| `panda.vision.max_concurrent_captures` | 2 | Concurrent captures (1–4) |
| `panda.vision.timeout_ticks` | 200 | Cancel after this many ticks |
| `panda.vision.cooldown_seconds` | 10 | Per-player cooldown |
| `panda.vision.movement_tolerance` | 0.35 | Allowed movement during capture (blocks) |
| `panda.vision.angle_tolerance` | 3 | Allowed head turn during capture (degrees) |
| `panda.vision.max_debug_particles` | 64 | Debug particle cap |
| `panda.vision.save_images` | `true` | Save the latest images to `captures/<UUID>/` |
| `panda.vision.textures.enabled` | `true` | Use real textures; off uses map colours |
| `panda.vision.textures.auto_download` | `true` | Download `vanilla-<version>.zip` from Mojang automatically |
| `panda.vision.textures.packs` | `[assets/vanilla-26.3.zip]` | 1–8 packs; later ones override earlier ones |
| `panda.vision.textures.cache_mib` | 64 | Decoded texture cache (8–256 MiB) |

## Features

| Key | Default | Meaning |
|---|---|---|
| `panda.features.preview_max_blocks` | 2048 | Ghost preview block cap |
| `panda.features.preview_seconds` | 180 | Seconds before a preview disappears |
| `panda.features.guidance_seconds` | 600 | Max particle guidance time |
| `panda.features.max_reminders` | 10 | Reminders per player; 0 disables |
| `panda.features.map_url` | `''` | Web-map template with `{world}` `{x}` `{y}` `{z}`; must start with http(s):// |

## Integrations

| Key | Default | Meaning |
|---|---|---|
| `panda.integrations.disabled` | `[]` | Disabled integration ids |
| `panda.integrations.placeholder_denylist` | `[ip, address, password, token, email, uuid]` | Placeholders containing these fragments are not resolved |
| `panda.integrations.block_history_days` | 7 | Days of CoreProtect history |

## Commands

See [Command Proposals](Command-Proposals.md).

| Key | Default |
|---|---|
| `panda.command_awareness.enabled` | `true` |
| `panda.command_awareness.allow_execution` | `false` |
| `panda.command_awareness.executor` | `player` (only value) |
| `panda.command_awareness.max_executions` | 3 |
| `panda.command_awareness.confirmation_ttl_seconds` | 60 |
| `panda.command_awareness.allowed_commands` | `[help]` |
| `panda.command_awareness.blocked_commands` | `[op, deop, stop, restart, reload, execute, sudo, panda, pandaadmin]` |

## Logging

| Key | Default | Meaning |
|---|---|---|
| `panda.logging.enabled` | `true` | File logging; by default only UUIDs and events |
| `panda.logging.conversations` | `false` | Log raw questions and answers only when enabled |
| `panda.logging.max_bytes` | 1048576 | Size of one log file |
| `panda.logging.files` | 3 | Rotated files kept |

## Proxy

| Key | Default | Meaning |
|---|---|---|
| `panda.proxy.enabled` | `false` | HTTP CONNECT proxy (not SOCKS) |
| `panda.proxy.host` / `port` | `localhost` / 10808 | |
| `panda.proxy.username` / `password` | `''` | Requires the JVM flag `-Djdk.http.auth.tunneling.disabledSchemes=` |

The proxy is used for both model requests and official texture downloads.

<!-- nav:bottom -->
---

<div align="center">

⬅️ [⚡ Command Proposals](Command-Proposals.md) &nbsp;·&nbsp; [📚 Contents](Home.md) &nbsp;·&nbsp; [🌍 Languages](Languages.md) ➡️

</div>
<!-- /nav:bottom -->
