<!-- nav:top -->
<div align="right">

🌐 [简体中文](../zh/Model-Providers.md) · **English** · 📖 [README](../../../README.en.md) · 🏠 [Wiki home](Home.md)

</div>
<!-- /nav:top -->

# 🤖 Model Providers

PandaGPT ships with **15 model providers**, covering the major international and Chinese platforms, plus any OpenAI-compatible endpoint. Switching providers is a one-line change and a reload.

## Overview

| `provider` id | Provider | Wire format | Default model | Image input by default |
|---|---|---|---|:---:|
| `openai` | OpenAI | OpenAI | `gpt-4.1-mini` | ✅ |
| `gemini` | Google Gemini | Native Gemini | `gemini-3.8-flash` | ✅ |
| `anthropic` | Anthropic Claude | Native Claude Messages | `claude-sonnet-5-5` | ✅ |
| `azure` | Azure OpenAI | OpenAI (`api-key` header) | deployment name | ✅ |
| `xai` | xAI Grok | OpenAI-compatible | `grok-4` | ✅ |
| `mistral` | Mistral AI | OpenAI-compatible | `mistral-medium-latest` | ✅ |
| `groq` | Groq | OpenAI-compatible | `openai/gpt-oss-120b` | ❌ |
| `openrouter` | OpenRouter (hundreds of models) | OpenAI-compatible | `openai/gpt-4.1-mini` | ✅ |
| `deepseek` | DeepSeek | OpenAI-compatible | `deepseek-chat` | ❌ |
| `qwen` | Alibaba Cloud Model Studio · Qwen | OpenAI-compatible | `qwen-plus` | ❌ |
| `moonshot` | Moonshot AI · Kimi | OpenAI-compatible | `kimi-k2-turbo-preview` | ❌ |
| `zhipu` | Zhipu AI · GLM | OpenAI-compatible | `glm-4.6` | ❌ |
| `doubao` | Volcengine Ark · Doubao | OpenAI-compatible | `doubao-seed-1-6-250615` | ✅ |
| `siliconflow` | SiliconFlow | OpenAI-compatible | `deepseek-ai/DeepSeek-V3` | ❌ |
| `custom` | Any OpenAI-compatible endpoint | OpenAI-compatible | required | ❌ |

> ⚠️ Model names change as providers update; the table lists defaults only. Use a model your account can actually access, as shown in your provider's console.

## Configuration

Each provider has its own block under `panda.model_selection`, so you can keep keys for several providers and switch at any time:

```yaml
panda:
  model_selection:
    provider: 'anthropic'          # the provider in use
    anthropic:
      api_key: 'sk-ant-...'
      api_url: 'https://api.anthropic.com/v1/messages'
      model: 'claude-sonnet-5-5'
      vision: true                 # does the model accept images?
    deepseek:
      api_key: 'sk-...'
      api_url: 'https://api.deepseek.com/chat/completions'
      model: 'deepseek-chat'
      vision: false
```

| Field | Meaning |
|---|---|
| `api_key` | The key. `PANDAGPT_<ID_IN_UPPER_CASE>_API_KEY` also works and wins when non-empty |
| `api_url` | Endpoint. Must be HTTPS with no query, fragment or URL credentials. Empty means the default |
| `model` | Model name: letters, digits and `._:/-` only |
| `vision` | Whether vision captures are sent as images. When off, the agent receives text-only observations (object IDs, blocks, distances, entities). Vision still works, just without pixels |

## Provider notes

<details>
<summary><b>OpenAI</b></summary>

Uses Chat Completions with `max_completion_tokens`. Parallel tool calls and image input are supported.

</details>

<details>
<summary><b>Google Gemini</b></summary>

`%s` in the URL is replaced with the model name. The key is sent in the `x-goog-api-key` header. Gemini 3 requires thought signatures (`thoughtSignature`) to be echoed back on multi-turn function calls, and the plugin stores and returns them automatically.

</details>

<details>
<summary><b>Anthropic Claude</b></summary>

Uses the native Messages API (`x-api-key` + `anthropic-version` headers), not a compatibility layer. Consecutive tool results are merged into one user message to satisfy Claude's strict role alternation, and thinking blocks are never shown to players.

</details>

<details>
<summary><b>Azure OpenAI</b></summary>

`api_url` is required, for example `https://<resource>.openai.azure.com/openai/v1/chat/completions`. Set `model` to your **deployment name**. The key is sent in the `api-key` header.

</details>

<details>
<summary><b>Chinese providers (DeepSeek, Qwen, Kimi, GLM, Doubao, SiliconFlow)</b></summary>

All use the OpenAI-compatible API; the plugin automatically sends `max_tokens` instead of `max_completion_tokens`.

- **Doubao**: `model` may be a model name or a Volcengine Ark endpoint ID (`ep-...`).
- **Qwen**: for images, switch to a vision model (e.g. `qwen-vl-plus`) and set `vision: true`.
- **DeepSeek**: `deepseek-chat` supports tool calling; check the official docs for reasoning models.

</details>

<details>
<summary><b>OpenRouter, Groq, xAI, Mistral</b></summary>

OpenRouter gives one key access to hundreds of models; write model names as `vendor/model`, e.g. `anthropic/claude-sonnet-5-5`. Groq is known for very fast inference, good for latency-sensitive servers.

</details>

<details>
<summary><b>Custom compatible endpoint <code>custom</code></b></summary>

For any service implementing OpenAI Chat Completions with `tools`, such as a self-hosted vLLM, LiteLLM, One API or New API gateway. Both `api_url` and `model` are required, and the URL must be HTTPS.

</details>

## Choosing a model

| Need | Suggestion |
|---|---|
| Full experience (including images) | A model with `vision: true` and reliable tool calling, e.g. Claude, GPT, Gemini, Doubao |
| Lowest cost | DeepSeek, Qwen, GLM and similar; with `vision` off, vision still works as text |
| Mainland China networks | Chinese providers connect directly; international ones can use `panda.proxy` (HTTP CONNECT) |
| Speed | Groq, or any provider's flash / mini / turbo tier |

> 💡 One turn may call the model several times (up to 8 steps by default). Control cost with `panda.agent.max_steps` and `max_tool_calls`; see [Configuration](Configuration.md#agent).

## Networking and retries

- Each request has an overall timeout (`panda.requests.timeout_seconds`) covering retries and reading the body.
- Only HTTP 429/502/503/504 and connection failures are retried, honouring `Retry-After`. A retry only re-fetches the model reply and **never re-runs tools**.
- Response bodies are capped (1 MiB by default); errors never include raw responses, URLs or keys.
- Proxy: `panda.proxy` supports HTTP CONNECT proxies. With a username and password, add `-Djdk.http.auth.tunneling.disabledSchemes=` to the JVM flags; the plugin warns in the console if it is missing.

<!-- nav:bottom -->
---

<div align="center">

⬅️ [📦 Installation](Installation.md) &nbsp;·&nbsp; [📚 Contents](Home.md) &nbsp;·&nbsp; [💬 Talking to Panda](Talking-to-Panda.md) ➡️

</div>
<!-- /nav:bottom -->
