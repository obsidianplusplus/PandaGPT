<!-- nav:top -->
<div align="right">

🌐 [简体中文](../zh/Languages.md) · **English** · 📖 [README](../../../README.en.md) · 🏠 [Wiki home](Home.md)

</div>
<!-- /nav:top -->

# 🌍 Languages

Every player- and console-facing message comes from a language file. **12 languages** are built in, and each player automatically sees their own client language.

| Code | Language | Code | Language |
|---|---|---|---|
| `zh_CN` | 🇨🇳 简体中文 | `es_ES` | 🇪🇸 Español |
| `zh_TW` | 繁體中文 | `fr_FR` | 🇫🇷 Français |
| `en_US` | 🇺🇸 English | `de_DE` | 🇩🇪 Deutsch |
| `ja_JP` | 🇯🇵 日本語 | `ru_RU` | 🇷🇺 Русский |
| `ko_KR` | 🇰🇷 한국어 | `pt_BR` | 🇧🇷 Português (Brasil) |
| `ar_SA` | 🇸🇦 العربية | `hi_IN` | 🇮🇳 हिन्दी |

## Configuration

```yaml
panda:
  language:
    default: 'en_US'   # console and clients without a match
    per_player: true   # follow each player's client language; off = everyone uses default
```

## Matching

With `per_player` on, the closest language is chosen:

| Client language | Uses |
|---|---|
| `zh_HK`, `zh_MO`, Traditional script | `zh_TW` |
| `pt_PT` | `pt_BR` |
| `es_MX` and other Spanish variants | `es_ES` |
| Anything not built in | `default` |

## Editing and adding translations

1. On first start the built-in files are copied to `plugins/PandaGPT/lang/`;
2. Edit any key and run `/pandaadmin reload`;
3. Missing keys fall back to the built-in text, and **upgrades never overwrite files you changed**;
4. `{0}`, `{1}` are placeholders; keep them when translating.

## Language of the model's answers

Plugin messages come from language files; **the model's answers** come from the model. The system prompt asks it to "answer in the language of the question, or the player's client language when unclear", so a question in Japanese gets a Japanese answer even if the server default is English.

Skill and prompt files are written in Chinese; this does not stop the model from answering in other languages.

> 🙏 Native speakers are welcome to review the Arabic and Hindi translations via pull request.

<!-- nav:bottom -->
---

<div align="center">

⬅️ [⚙️ Configuration](Configuration.md) &nbsp;·&nbsp; [📚 Contents](Home.md) &nbsp;·&nbsp; [🔑 Permissions & Admin](Permissions-and-Admin.md) ➡️

</div>
<!-- /nav:bottom -->
