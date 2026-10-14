<!-- nav:top -->
<div align="right">

🌐 **简体中文** · [English](../en/Model-Providers.md) · 📖 [README](../../../README.md) · 🏠 [Wiki 首页](Home.md)

</div>
<!-- /nav:top -->

# 🤖 模型服务商

PandaGPT 内置 **15 家**模型服务商，覆盖国际与国内主流平台，另外支持任意 OpenAI 兼容接口。切换服务商只需改一行配置并重载。

## 一览

| 编号 `provider` | 服务商 | 接口格式 | 默认模型 | 默认图片输入 |
|---|---|---|---|:---:|
| `openai` | OpenAI | OpenAI | `gpt-4.1-mini` | ✅ |
| `gemini` | Google Gemini | Gemini 原生 | `gemini-3.8-flash` | ✅ |
| `anthropic` | Anthropic Claude | Claude Messages 原生 | `claude-sonnet-5-5` | ✅ |
| `azure` | Azure OpenAI | OpenAI（`api-key` 请求头） | 部署名 | ✅ |
| `xai` | xAI Grok | OpenAI 兼容 | `grok-4` | ✅ |
| `mistral` | Mistral AI | OpenAI 兼容 | `mistral-medium-latest` | ✅ |
| `groq` | Groq | OpenAI 兼容 | `openai/gpt-oss-120b` | ❌ |
| `openrouter` | OpenRouter（数百个模型） | OpenAI 兼容 | `openai/gpt-4.1-mini` | ✅ |
| `deepseek` | DeepSeek 深度求索 | OpenAI 兼容 | `deepseek-chat` | ❌ |
| `qwen` | 阿里云百炼 · 通义千问 | OpenAI 兼容 | `qwen-plus` | ❌ |
| `moonshot` | 月之暗面 · Kimi | OpenAI 兼容 | `kimi-k2-turbo-preview` | ❌ |
| `zhipu` | 智谱 · GLM | OpenAI 兼容 | `glm-4.6` | ❌ |
| `doubao` | 火山方舟 · 豆包 | OpenAI 兼容 | `doubao-seed-1-6-250615` | ✅ |
| `siliconflow` | 硅基流动 | OpenAI 兼容 | `deepseek-ai/DeepSeek-V3` | ❌ |
| `custom` | 任意 OpenAI 兼容接口 | OpenAI 兼容 | 需填写 | ❌ |

> ⚠️ 模型名称会随服务商更新而变化，表中只是默认值。请以服务商控制台中你的账号实际可用的模型为准。

## 配置方式

每家服务商在 `panda.model_selection` 下有独立的一段，互不干扰，可以同时保存多家的密钥，随时切换：

```yaml
panda:
  model_selection:
    provider: 'anthropic'          # 当前使用的服务商
    anthropic:
      api_key: 'sk-ant-...'
      api_url: 'https://api.anthropic.com/v1/messages'
      model: 'claude-sonnet-5-5'
      vision: true                 # 模型是否接受图片
    deepseek:
      api_key: 'sk-...'
      api_url: 'https://api.deepseek.com/chat/completions'
      model: 'deepseek-chat'
      vision: false
```

| 字段 | 说明 |
|---|---|
| `api_key` | 密钥。也可以用环境变量 `PANDAGPT_<编号大写>_API_KEY`，非空时优先 |
| `api_url` | 接口地址。必须是 HTTPS，不能带查询参数、片段或 URL 凭据。留空时使用默认地址 |
| `model` | 模型名，只允许字母、数字和 `._:/-` |
| `vision` | 是否把视觉采样的图片发给模型。关闭后 Agent 只拿到文字形式的观察结果（对象编号、方块、距离、实体），视觉功能仍然可用，只是看不到像素 |

## 各家说明

<details>
<summary><b>OpenAI</b></summary>

使用 Chat Completions 接口与 `max_completion_tokens`。支持并行工具调用与图片输入。

</details>

<details>
<summary><b>Google Gemini</b></summary>

地址中的 `%s` 会被替换为模型名。密钥通过 `x-goog-api-key` 请求头发送。Gemini 3 的多轮函数调用要求原样带回“思考签名”（thoughtSignature），插件会自动保存并回传。

</details>

<details>
<summary><b>Anthropic Claude</b></summary>

使用原生 Messages 接口（`x-api-key` + `anthropic-version` 请求头），不是兼容层。插件会把连续的工具结果合并为一条 user 消息，满足 Claude 对角色严格交替的要求；思考内容块不会展示给玩家。

</details>

<details>
<summary><b>Azure OpenAI</b></summary>

`api_url` 必须填写，例如 `https://<资源名>.openai.azure.com/openai/v1/chat/completions`；`model` 填写**部署名**。密钥通过 `api-key` 请求头发送。

</details>

<details>
<summary><b>国内服务商（DeepSeek、通义千问、Kimi、智谱、豆包、硅基流动）</b></summary>

都使用 OpenAI 兼容接口，插件会自动改用 `max_tokens` 字段。

- **豆包**：`model` 可以填写模型名或火山方舟的推理接入点 ID（`ep-...`）。
- **通义千问**：想使用图片，请换成视觉模型（例如 `qwen-vl-plus`）并设置 `vision: true`。
- **DeepSeek**：`deepseek-chat` 支持工具调用；推理模型的工具调用支持情况请以官方文档为准。

</details>

<details>
<summary><b>OpenRouter、Groq、xAI、Mistral</b></summary>

OpenRouter 用一个密钥即可访问数百个模型，模型名写成 `厂商/模型`，例如 `anthropic/claude-sonnet-5-5`。Groq 以极快的推理速度著称，适合对响应时间敏感的服务器。

</details>

<details>
<summary><b>自定义兼容接口 <code>custom</code></b></summary>

适用于任何实现了 OpenAI Chat Completions 与 `tools` 参数的服务，例如自建的 vLLM、LiteLLM、One API、New API 网关。必须填写 `api_url` 与 `model`，且地址必须是 HTTPS。

</details>

## 模型选择建议

| 需求 | 建议 |
|---|---|
| 完整体验（含看图） | 选择 `vision: true` 且工具调用稳定的模型，例如 Claude、GPT、Gemini、豆包 |
| 成本优先 | DeepSeek、通义千问、GLM 等，关闭 `vision` 后视觉功能仍以文字形式工作 |
| 国内网络 | 国内服务商直连；国际服务商可配置 `panda.proxy`（HTTP CONNECT 代理） |
| 速度优先 | Groq，或各家的 flash / mini / turbo 系列 |

> 💡 Agent 一个回合可能请求模型多次（默认最多 8 步）。可以在 `panda.agent.max_steps` 与 `max_tool_calls` 中控制成本，见 [配置参考](Configuration.md#agent)。

## 网络与重试

- 每个回合有总超时（`panda.requests.timeout_seconds`），包含重试与响应体读取。
- 只对 HTTP 429/502/503/504 与连接失败重试，遵守 `Retry-After`。重试只会重新获取模型回复，**不会重复执行工具**。
- 响应体大小有上限（默认 1 MiB），错误信息不会包含原始响应、地址或密钥。
- 代理：`panda.proxy` 支持 HTTP CONNECT 代理。使用账号密码时，需要在 JVM 参数中加入 `-Djdk.http.auth.tunneling.disabledSchemes=`，插件检测到未设置时会在控制台提醒。

<!-- nav:bottom -->
---

<div align="center">

⬅️ [📦 安装与升级](Installation.md) &nbsp;·&nbsp; [📚 目录](Home.md) &nbsp;·&nbsp; [💬 和 Panda 对话](Talking-to-Panda.md) ➡️

</div>
<!-- /nav:bottom -->
