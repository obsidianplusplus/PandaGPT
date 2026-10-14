<!-- nav:top -->
<div align="right">

🌐 **简体中文** · [English](../en/Installation.md) · 📖 [README](../../../README.md) · 🏠 [Wiki 首页](Home.md)

</div>
<!-- /nav:top -->

# 📦 安装与升级

## 环境要求

| 项目 | 要求 |
|---|---|
| 服务端 | **Paper 26.3**（API 版本 `26.3`）；不支持 Spigot 1.12.2，未声明 Folia 兼容 |
| Java | **JDK 25** |
| 网络 | 服务器需要能访问所选的模型服务商；首次启动还会访问 Mojang 官方下载纹理 |
| 模型 | 支持<strong>函数调用（工具调用）</strong>的模型；想使用视觉功能还需要支持图片输入 |

## 三步上手

### 1️⃣ 放入插件

从 GitHub Actions 的构建产物（[Actions](https://github.com/obsidianplusplus/PandaGPT/actions) → 最新一次成功构建 → `PandaGPT-Paper-26.3`）下载 `PandaGPT-3.2.0.jar`，或[自行构建](#build)，放入 `plugins/`，然后重启服务器。升级时先删除旧版本的 JAR。

首次启动会生成：

```text
plugins/PandaGPT/
├── config.yml          # 主配置
├── knowledge.md        # 服务器资料（规则、玩法、插件说明）
├── lang/*.yml          # 12 种语言文件，可直接修改
├── skills/*.md         # 34 个技能，可修改、新增或关闭
├── prompts/*.md        # 系统提示词模板
├── assets/             # 自动下载的官方纹理（vanilla-26.3.zip）
├── memory/<UUID>.json  # 每位玩家的地点、目标与笔记
└── captures/<UUID>/    # 最近一次视觉采样的图片（可关闭）
```

### 2️⃣ 填写模型密钥

编辑 `plugins/PandaGPT/config.yml`，选择服务商并填写密钥：

```yaml
panda:
  model_selection:
    provider: 'deepseek'        # 15 家任选其一
    deepseek:
      api_key: 'sk-...'
      model: 'deepseek-chat'
```

> 💡 不想把密钥写进文件？设置环境变量 `PANDAGPT_<服务商>_API_KEY`，例如 `PANDAGPT_DEEPSEEK_API_KEY`。非空的环境变量优先于配置文件。

全部服务商及其区别见 [模型服务商](Model-Providers.md)。

### 3️⃣ 写一点服务器资料并重载

在 `knowledge.md` 中用 Markdown 标题分段写下本服的规则、经济、领地、传送方式和常用插件。Agent 会按需检索相关段落，而不是每次都读整份文件。

```text
/pandaadmin reload
/pandaadmin status
```

`status` 显示服务商是否已配置、技能与工具数量、队列和视觉状态。完成后，任何玩家在聊天里说 `panda 你好` 即可开始。

<a id="textures"></a>

## 🖼️ 官方纹理：自动下载

视觉功能需要 Minecraft 原版的方块模型与纹理。**插件会自动处理，不需要任何脚本：**

1. 启动或重载时，如果 `panda.vision.textures.packs` 中有名为 `vanilla-<版本>.zip` 的文件不存在，插件会在后台线程开始下载；
2. 只从 Mojang 官方 HTTPS 主机获取版本清单与客户端，逐层核对官方给出的 **SHA-1 与文件大小**；
3. 客户端 JAR 只当作 ZIP 读取，**绝不加载或执行其中的代码**；只提取方块状态、方块模型、方块纹理与颜色表（约 5,500 个文件）；
4. 先写入临时文件，校验通过后原子替换；完成后立即生效，不需要再重载。

控制台会看到：

```text
[PandaGPT] 正在从 Mojang 官方下载并校验 26.3 渲染资源…
[PandaGPT] 官方纹理已校验，提取了 5579 个渲染资源到 vanilla-26.3.zip。
```

| 场景 | 做法 |
|---|---|
| 服务器无法访问 Mojang | 在其他机器上让插件下载一次，再把 `assets/vanilla-26.3.zip` 复制过来；或填写 `panda.proxy` |
| 想用服务器资源包覆盖原版纹理 | 在 `packs` 中追加 `assets/server-pack.zip`，后面的包覆盖前面的包 |
| 不想自动下载 | `panda.vision.textures.auto_download: false` |
| 完全不需要视觉 | `panda.vision.enabled: false` |

只有文件名符合 `vanilla-<版本>.zip` 的条目会被自动下载，服主自备的资源包不会被改动。

## ⬆️ 从旧版本升级

| 从 | 会发生什么 |
|---|---|
| **3.1 / 3.0**（配置 v2） | 自动备份为 `config-v2-backup.yml`；顶层的 `openai:`、`gemini:` 密钥与地址移入 `panda.model_selection.<服务商>` |
| **2.x 及更早**（配置 v1） | 先备份为 `config-v1-backup.yml`，把 `history_size` 换算为问答轮数，再继续上面的迁移 |
| 旧的玩家命令 `/panda ...` | 3.0 起已全部移除，玩家直接在聊天中说话即可 |
| 记忆文件 | 直接沿用，新增字段向后兼容 |
| `tools/download_vanilla_assets.py` | 3.2 起删除，由插件自动下载取代 |

旧的 Gemini 地址中的 `?key=%s` 会被自动移除，密钥改为放在请求头中发送。

<a id="build"></a>

## 🔨 从源码构建

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

产物位于 `target/PandaGPT-3.2.0.jar`。更多细节见 [开发指南](Development.md)。

<!-- nav:bottom -->
---

<div align="center">

⬅️ [🏠 首页](Home.md) &nbsp;·&nbsp; [📚 目录](Home.md) &nbsp;·&nbsp; [🤖 模型服务商](Model-Providers.md) ➡️

</div>
<!-- /nav:bottom -->
