<!-- nav:top -->
<div align="right">

🌐 [简体中文](../zh/Permissions-and-Admin.md) · **English** · 📖 [README](../../../README.en.md) · 🏠 [Wiki home](Home.md)

</div>
<!-- /nav:top -->

# 🔑 Permissions & Admin

## The only command: `/pandaadmin`

Players have no commands; `/pandaadmin` is for server owners (requires `pandagpt.admin`).

| Command | Effect |
|---|---|
| `/pandaadmin reload` | Validates and reloads config, languages, skills, prompts, server docs, the recipe index and plugin integrations; keeps the old service on failure. Also starts downloading official textures if they are missing |
| `/pandaadmin status` | Provider and model, whether a key is set, skill and tool counts, queues, vision and perception state, per-tick p95 and max, dropped log lines |
| `/pandaadmin clearchat` | Clears every session, in-flight turn and pending command |

## Permissions

| Permission | Default | Purpose |
|---|---|---|
| `pandagpt.use` | everyone | Talk to Panda |
| `pandagpt.actions` | OP | Receive and confirm [command proposals](Command-Proposals.md) |
| `pandagpt.vision` | OP | `look`, `focus_object` and background observation |
| `pandagpt.vision.debug` | OP | Particles showing ray hits and object outlines |
| `pandagpt.blockhistory` | OP | Let the agent query CoreProtect block history |
| `pandagpt.admin` | OP | `/pandaadmin` |

### Suggested group setup (LuckPerms)

```text
# Regular players: talk, see, confirm allow-listed commands
/lp group default permission set pandagpt.vision true
/lp group default permission set pandagpt.actions true

# Staff: block history and debug particles too
/lp group admin permission set pandagpt.blockhistory true
/lp group admin permission set pandagpt.vision.debug true
```

> 💡 Vision is OP-only by default so that upgrading the plugin doesn't silently enrol regular players in background observation. Open it up once you're confident in your server's headroom.

<!-- nav:bottom -->
---

<div align="center">

⬅️ [🌍 Languages](Languages.md) &nbsp;·&nbsp; [📚 Contents](Home.md) &nbsp;·&nbsp; [🚀 Performance](Performance.md) ➡️

</div>
<!-- /nav:bottom -->
