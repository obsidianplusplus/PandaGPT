<!-- nav:top -->
<div align="right">

🌐 **简体中文** · [English](../en/Languages.md) · 📖 [README](../../../README.md) · 🏠 [Wiki 首页](Home.md)

</div>
<!-- /nav:top -->

# 🌍 多语言

所有面向玩家与控制台的文字都来自语言文件，内置 **12 种语言**，并按每位玩家的客户端语言自动切换。

| 代码 | 语言 | 代码 | 语言 |
|---|---|---|---|
| `zh_CN` | 🇨🇳 简体中文 | `es_ES` | 🇪🇸 Español |
| `zh_TW` | 繁體中文 | `fr_FR` | 🇫🇷 Français |
| `en_US` | 🇺🇸 English | `de_DE` | 🇩🇪 Deutsch |
| `ja_JP` | 🇯🇵 日本語 | `ru_RU` | 🇷🇺 Русский |
| `ko_KR` | 🇰🇷 한국어 | `pt_BR` | 🇧🇷 Português (Brasil) |
| `ar_SA` | 🇸🇦 العربية | `hi_IN` | 🇮🇳 हिन्दी |

## 配置

```yaml
panda:
  language:
    default: 'zh_CN'   # 控制台与无法匹配的客户端使用的语言
    per_player: true   # 按玩家客户端语言显示；关闭后所有人使用 default
```

## 匹配规则

开启 `per_player` 时按客户端语言就近匹配：

| 客户端语言 | 使用 |
|---|---|
| `zh_HK`、`zh_MO`、繁体脚本 | `zh_TW` |
| `pt_PT` | `pt_BR` |
| `es_MX` 等西班牙语变体 | `es_ES` |
| 其他未内置的语言 | `default` |

## 修改与补充翻译

1. 首次启动时内置语言文件会复制到 `plugins/PandaGPT/lang/`；
2. 修改其中任意键，执行 `/pandaadmin reload` 即可生效；
3. 文件中缺少的键会自动使用内置文本，**升级插件不会覆盖你修改过的文件**；
4. `{0}`、`{1}` 是参数占位符，翻译时请保留。

## 模型回答的语言

插件消息由语言文件决定；**模型的回答**由模型决定。系统提示要求模型“用提问的语言回答，无法判断时使用玩家客户端语言”，所以用日语提问会得到日语回答，即使服务器默认语言是中文。

技能与提示词文件本身是中文写成的，这不影响模型用其他语言回答。

> 🙏 阿拉伯语与印地语的翻译欢迎母语者通过 Pull Request 帮忙校对。

<!-- nav:bottom -->
---

<div align="center">

⬅️ [⚙️ 配置参考](Configuration.md) &nbsp;·&nbsp; [📚 目录](Home.md) &nbsp;·&nbsp; [🔑 权限与管理命令](Permissions-and-Admin.md) ➡️

</div>
<!-- /nav:bottom -->
