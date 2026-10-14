<!-- nav:top -->
<div align="right">

🌐 [简体中文](../zh/FAQ.md) · **English** · 📖 [README](../../../README.en.md) · 🏠 [Wiki home](Home.md)

</div>
<!-- /nav:top -->

# ❓ FAQ

<details>
<summary><b>A player said panda but nothing happened</b></summary>

1. The trigger must be at the **start**: `panda hi` ✅, `hi panda` ❌;
2. Check the player has `pandagpt.use`;
3. Run `/pandaadmin status` and confirm the provider has a key;
4. They may be on cooldown (5 seconds by default) or have a turn still running;
5. Another plugin (mute or chat formatting) may have cancelled the chat event.

</details>

<details>
<summary><b>The answer says authentication failed or the model wasn't found</b></summary>

- Authentication failure (401/403): wrong key or no access;
- Not found (404): the `model` name is wrong or your account can't use it; check the provider console;
- `api_url` may be wrong. Fix it and `/pandaadmin reload`.

</details>

<details>
<summary><b>My model doesn't accept images</b></summary>

Set `vision: false`. The agent still calls `look` and receives text results (object IDs, blocks, distances, entities), so most "what is this" or "that furnace" questions still work.

</details>

<details>
<summary><b>Vision says texture assets are not ready</b></summary>

The plugin is downloading official textures in the background (see the console), or the download failed, usually because the server can't reach Mojang. Configure `panda.proxy`, or let the plugin download once on another machine and copy `assets/vanilla-26.3.zip`. See [Installation](Installation.md#textures).

</details>

<details>
<summary><b>Will it be expensive?</b></summary>

- A turn may call the model several times (8 steps max by default); lower `panda.agent.max_steps`;
- Skills are progressively disclosed, so only the catalog is in the system prompt;
- Background observation never calls the model; proactive tips are rate-limited and can be turned off (`panda.agent.proactive: false`);
- Choose cheaper models such as DeepSeek, Qwen, GLM, or any mini / flash tier.

</details>

<details>
<summary><b>Could Panda run commands on its own?</b></summary>

No. Execution is off by default; when on, only allow-listed commands can run, the player must confirm, and they run as the player. See [Command Proposals](Command-Proposals.md).

</details>

<details>
<summary><b>How do I teach Panda about my server?</b></summary>

Write rules, economy, claims, teleports and events into `knowledge.md`, split by Markdown headings; the agent searches relevant sections. You can also create knowledge-only skills; see [Skills & Prompts](Skills-and-Prompts.md).

</details>

<details>
<summary><b>Folia / Spigot / older versions?</b></summary>

3.x supports Paper 26.3 with Java 25 only. Folia compatibility is not declared and Spigot 1.12.2 is not supported.

</details>

<details>
<summary><b>Can players turn proactive tips off?</b></summary>

Yes. Tell Panda "don't disturb me"; "you can remind me again" turns them back on.

</details>

<details>
<summary><b>A reload failed</b></summary>

The old service keeps running, and chat and console explain which setting is wrong (out of range, non-HTTPS URL, and so on). Fix it and `/pandaadmin reload` again.

</details>

<!-- nav:bottom -->
---

<div align="center">

⬅️ [🛡️ Privacy & Security](Privacy-and-Security.md) &nbsp;·&nbsp; [📚 Contents](Home.md) &nbsp;·&nbsp; [🛠️ Development](Development.md) ➡️

</div>
<!-- /nav:bottom -->
