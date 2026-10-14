"""生成 SpigotMC 宣传长图：同一套版式，分别输出中文与英文 HTML，再由无头 Chrome 渲染为 PNG。

用法：python3 docs/promo/build.py
"""
import base64
import html
import math
import pathlib
import re
import subprocess

HERE = pathlib.Path(__file__).resolve().parent
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
WIDTH = 1000


def esc(s):
    return html.escape(s, quote=False)


# ---------------------------------------------------------------- 插画


def iso_cabin():
    """等距视角的幽灵方块小木屋（与插件的建筑预览同样由方块部件组成）。"""
    blocks = {}
    for x in range(7):
        for z in range(7):
            blocks[(x, 0, z)] = "floor"
    for y in (1, 2, 3):
        for x in range(7):
            for z in range(7):
                if x in (0, 6) or z in (0, 6):
                    corner = x in (0, 6) and z in (0, 6)
                    blocks[(x, y, z)] = "log" if corner else "plank"
    for y in (1, 2):
        blocks.pop((3, y, 6), None)  # 门
    for p in ((6, 2, 2), (6, 2, 4), (1, 2, 6), (5, 2, 6)):
        blocks[p] = "glass"
    for level, (a, b) in enumerate(((-1, 7), (0, 6), (1, 5), (2, 4), (3, 3))):
        y = 4 + level
        for x in range(a, b + 1):
            for z in range(a, b + 1):
                if x in (a, b) or z in (a, b):
                    blocks[(x, y, z)] = "roof"
    colors = {
        "floor": ("#9aa3b5", "#7d8698", "#6a7385"),
        "plank": ("#e2b56f", "#c9984f", "#a97c3c"),
        "log": ("#8a6239", "#6f4d2b", "#5a3d21"),
        "glass": ("#bff0ff", "#93dcf5", "#79c9e6"),
        "roof": ("#6b4a33", "#563a27", "#45301f"),
    }
    W, H, V = 44, 22, 26
    ox, oy = 360, 210

    def P(x, y, z):
        return (ox + (x - z) * W / 2, oy + (x + z) * H / 2 - y * V)

    def poly(pts, fill, op):
        d = " ".join(f"{a:.1f},{b:.1f}" for a, b in pts)
        return (f'<polygon points="{d}" fill="{fill}" fill-opacity="{op}" '
                f'stroke="#d9f6ff" stroke-opacity=".55" stroke-width="1"/>')

    out = []
    for (x, y, z) in sorted(blocks, key=lambda p: (p[0] + p[1] + p[2], p[1])):
        top, right, left = colors[blocks[(x, y, z)]]
        op = ".42" if blocks[(x, y, z)] == "glass" else ".78"
        if (x, y + 1, z) not in blocks:
            out.append(poly([P(x, y + 1, z), P(x + 1, y + 1, z), P(x + 1, y + 1, z + 1), P(x, y + 1, z + 1)], top, op))
        if (x + 1, y, z) not in blocks:
            out.append(poly([P(x + 1, y, z), P(x + 1, y, z + 1), P(x + 1, y + 1, z + 1), P(x + 1, y + 1, z)], right, op))
        if (x, y, z + 1) not in blocks:
            out.append(poly([P(x, y, z + 1), P(x + 1, y, z + 1), P(x + 1, y + 1, z + 1), P(x, y + 1, z + 1)], left, op))
    grid = []
    for i in range(-3, 11):
        a, b = P(i, 0, -3), P(i, 0, 10)
        grid.append(f'<line x1="{a[0]:.0f}" y1="{a[1]:.0f}" x2="{b[0]:.0f}" y2="{b[1]:.0f}"/>')
        a, b = P(-3, 0, i), P(10, 0, i)
        grid.append(f'<line x1="{a[0]:.0f}" y1="{a[1]:.0f}" x2="{b[0]:.0f}" y2="{b[1]:.0f}"/>')
    return (f'<svg viewBox="0 0 720 470" width="100%"><defs><radialGradient id="glow" cx="50%" cy="60%" r="60%">'
            f'<stop offset="0" stop-color="#59c3ff" stop-opacity=".28"/><stop offset="1" stop-color="#59c3ff" stop-opacity="0"/>'
            f'</radialGradient></defs><rect width="720" height="470" fill="url(#glow)"/>'
            f'<g stroke="#59c3ff" stroke-opacity=".12">{"".join(grid)}</g>{"".join(out)}</svg>')


def trail(home_label, bar):
    """粒子导航：从玩家延伸到家的私有粒子路线。"""
    dots = []
    for i in range(46):
        t = i / 45
        x = (1 - t) ** 3 * 70 + 3 * (1 - t) ** 2 * t * 260 + 3 * (1 - t) * t * t * 520 + t ** 3 * 820
        y = (1 - t) ** 3 * 300 + 3 * (1 - t) ** 2 * t * 120 + 3 * (1 - t) * t * t * 330 + t ** 3 * 110
        r = 3 + 3 * math.sin(t * math.pi * 6) ** 2
        dots.append(f'<circle cx="{x:.0f}" cy="{y:.0f}" r="{r:.1f}" fill="#7dffb0" opacity="{0.25 + 0.75 * t:.2f}"/>')
    return f"""<svg viewBox="0 0 888 360" width="100%">
<defs><linearGradient id="sky" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#1b2556"/><stop offset="1" stop-color="#0d1430"/></linearGradient></defs>
<rect width="888" height="360" rx="22" fill="url(#sky)"/>
<path d="M0 300 L120 270 L220 290 L330 250 L470 280 L600 240 L720 265 L888 230 L888 360 L0 360Z" fill="#244a2c"/>
<path d="M0 320 L160 300 L300 318 L460 300 L640 312 L888 290 L888 360 L0 360Z" fill="#1c3a23"/>
{''.join(dots)}
<g transform="translate(46,262)"><rect x="8" y="0" width="16" height="16" fill="#e8c39e"/><rect x="6" y="16" width="20" height="22" fill="#3aa0c8"/><rect x="6" y="38" width="9" height="16" fill="#3b3fa3"/><rect x="17" y="38" width="9" height="16" fill="#3b3fa3"/></g>
<g transform="translate(790,58)"><polygon points="30,0 64,26 -4,26" fill="#8a5a3a"/><rect x="4" y="26" width="52" height="38" fill="#c9984f"/><rect x="24" y="40" width="12" height="24" fill="#5a3d21"/></g>
<text x="820" y="150" fill="#ffffff" font-size="17" font-weight="800" text-anchor="middle">{esc(home_label)}</text>
<rect x="244" y="22" width="400" height="42" rx="10" fill="#000" fill-opacity=".55" stroke="#ffffff" stroke-opacity=".2"/>
<text x="444" y="49" fill="#fff" font-size="18" font-weight="800" text-anchor="middle">{esc(bar)}</text>
</svg>"""


def hub(center, nodes):
    """插件星图：PandaGPT 位于中心，外部插件围绕。"""
    cx, cy, rx, ry = 444, 300, 335, 222
    lines, cards = [], []
    for i, (icon, name, desc) in enumerate(nodes):
        a = -math.pi / 2 + 2 * math.pi * i / len(nodes)
        x, y = cx + rx * math.cos(a), cy + ry * math.sin(a)
        lines.append(f'<line x1="{cx}" y1="{cy}" x2="{x:.0f}" y2="{y:.0f}" stroke="url(#ln)" stroke-width="2" stroke-dasharray="4 6"/>')
        cards.append(
            f'<g transform="translate({x - 84:.0f},{y - 32:.0f})"><rect width="168" height="64" rx="14" fill="#141d3d" stroke="#2f427a"/>'
            f'<text x="84" y="27" text-anchor="middle" font-size="15.5" font-weight="800" fill="#fff">{icon} {esc(name)}</text>'
            f'<text x="84" y="48" text-anchor="middle" font-size="12.5" fill="#9aa6d1">{esc(desc)}</text></g>')
    return f"""<svg viewBox="0 0 888 600" width="100%">
<defs><linearGradient id="ln" x1="0" x2="1"><stop offset="0" stop-color="#5ee36b"/><stop offset="1" stop-color="#59c3ff"/></linearGradient>
<radialGradient id="core"><stop offset="0" stop-color="#2b4bd6"/><stop offset="1" stop-color="#121a40"/></radialGradient></defs>
<ellipse cx="{cx}" cy="{cy}" rx="{rx}" ry="{ry}" fill="none" stroke="#26345f" stroke-dasharray="2 8"/>
{''.join(lines)}
<circle cx="{cx}" cy="{cy}" r="92" fill="url(#core)" stroke="#5ee36b" stroke-width="3"/>
<text x="{cx}" y="{cy - 6}" text-anchor="middle" font-size="54">🐼</text>
<text x="{cx}" y="{cy + 34}" text-anchor="middle" font-size="17" font-weight="900" fill="#fff">{esc(center)}</text>
{''.join(cards)}</svg>"""


# ---------------------------------------------------------------- 文案

TEXT = {
    "zh": {
        "lang": "zh-CN",
        "eyebrow": "MINECRAFT · AI AGENT · PAPER 26.3",
        "slogan": "住在你服务器里的 AI 伙伴",
        "sub": "它看得见你眼前的世界，记得你走过的地方，读得懂整个服务器，<br>在恰当的时候提醒你，在不该说话的时候保持安静。",
        "zero": "零玩家命令 · 只需在聊天里说 panda",
        "hero_chat": [("p", "Steve", "panda 那个熔炉怎么用？"), ("a", "Panda", "#3 是熔炉，已用粒子圈出。你的 12 个粗铁够烧 12 个铁锭。")],
        "hero_tag": "#3 熔炉",
        "hero_bar": "👀 正在看…",
        "nums": [("60", "Agent 工具"), ("34", "专业技能"), ("15", "模型服务商"), ("11", "插件集成"), ("12", "语言"), ("0", "玩家命令")],
        "s1_eye": "01 · 不只是聊天机器人",
        "s1_title": "它会思考，然后行动",
        "s1_sub": "普通的 AI 插件只会把你的问题转给模型。PandaGPT 是一个真正的 Agent：自己决定先看、先查还是先问，一次回答背后可能是十几步推理与工具调用。",
        "trace_q": "panda 我准备好打末影龙了吗？",
        "trace": [
            ("📜", "load_skill", "combat", "加载战斗与 Boss 技能"),
            ("🎒", "get_player_status", "", "钻石剑 锋利 IV · 全套钻石甲 · 32 支箭"),
            ("🗡️", "inspect_item", "chestplate", "胸甲耐久仅剩 18%，有经验修补"),
            ("🔍", "search_server_knowledge", "末地", "本服末地每周日刷新，龙蛋归击杀者"),
            ("🧪", "find_recipes", "slow_falling", "缓降药水：幻翼膜 + 粗制药水"),
        ],
        "trace_a": "差一点。先修胸甲，再备几瓶缓降药水防止被末影龙击飞摔死；水桶和 64 个方块也带上。本服龙蛋归击杀者，加油！",
        "vs_title": "普通 AI 聊天插件  vs  PandaGPT",
        "vs": ["看得见你眼前的东西", "记得你的家、箱子和死亡点", "按本服真实配方核对背包",
               "读取经济、领地、权限等插件数据", "一次提问里多步推理、并行调用工具",
               "在恰当时机主动提醒，其余时间保持安静", "在玩家确认后安全地帮你执行操作"],
        "vs_a": "普通 AI 插件", "vs_b": "PandaGPT",
        "s2_eye": "02 · 视觉",
        "s2_title": "不装任何模组，它也能“看见”",
        "s2_sub": "在服务器端以 160×90 条射线重建你的第一人称画面，并用 Minecraft 原版的模型与纹理逐像素着色。你可以指着说“左边那个熔炉”，它真的知道你在说哪一个。",
        "vis_caps": ["真实纹理重建", "对象编号 + 实体框"],
        "vis": [("🎨", "原版纹理与模型", "方块状态、模型 UV、旋转、生物群系染色，楼梯与半砖各有形状"),
                ("🔢", "对象编号", "画面中的区域被编号，熔炉、工作台等交互方块独立识别"),
                ("✨", "粒子高亮", "服务器再次核对实时方块后，用只有你能看到的粒子圈出目标"),
                ("🐺", "实体观察", "生物位置、碰撞箱、是否被遮挡、正在追谁，一目了然"),
                ("🧱", "7×7×7 空间感知", "前方能否站立、落差多高、有没有岩浆，逐格分析"),
                ("⏱️", "连续观察", "比较前后两次画面，知道哪些方块变了、哪只怪物在靠近")],
        "grid_title": "前方空间分析",
        "grid_legend": [("ok", "可站立"), ("drop", "落差"), ("lava", "危险"), ("unk", "未知")],
        "space": "Panda 复制你周围方块的真实碰撞形状，逐列判断<b>能不能站</b>、<b>落差有多高</b>、<b>有没有岩浆</b>。紧急风险会立刻显示在动作栏，无需等待模型。",
        "s3_eye": "03 · 记忆与导航",
        "s3_title": "它记得你去过的每一个地方",
        "s3_sub": "说一句“记住这里”，Panda 就会记下精确坐标；睡一觉，它自动把床边记成家。想回去时，一条只有你看得见的粒子路线会把你带到门口。",
        "home": "🏠 家",
        "bar": "🧭 家 ↗ 230 格 · 高差 +8",
        "mem_title": "Steve 的记忆",
        "mem": [("🏠", "家", "东北 230 格"), ("📦", "钻石箱子", "家中 · 已核实"), ("⛏️", "矿洞入口", "西 412 格"), ("💀", "死亡点", "4 分钟前 · 物品还剩 1 分钟"), ("🌾", "麦田", "南 36 格")],
        "s4_eye": "04 · 创造",
        "s4_title": "说出你的想法，它在原地为你搭出来",
        "s4_sub": "“在这里给我设计一个 7×7 的小木屋”——Agent 用方块、墙、圆柱、球体、金字塔等部件组合出建筑，以只有你能看到的幽灵方块呈现在原地，世界不会被改动分毫。",
        "bom_title": "材料清单",
        "bom": [("橡木木板", "96", "65"), ("橡木原木", "12", "12"), ("石砖", "49", "49"), ("玻璃", "4", "0"), ("深色橡木", "120", "88")],
        "bom_have": "已有", "bom_need": "需要",
        "bom_note": "还差 31 个木板、4 块玻璃、32 个深色橡木 → 一句话设为目标",
        "s5_eye": "05 · 主动式 AI",
        "s5_title": "懂分寸：该说才说，不该说就安静",
        "s5_sub": "Panda 一直在感知你的处境，但每一次开口前都会先核实。没有价值的提醒，它会主动选择沉默。",
        "timeline": [
            ("07:10", "q", "路过村庄"),
            ("08:30", "t", "⛏️ 出发前检查", "“火把只剩 3 个，要不要先补点？”"),
            ("09:15", "q", "挖矿中，专注时不打扰"),
            ("10:40", "t", "✅ 目标完成", "“铁镐的材料齐了，工作台就在你左边。”"),
            ("12:05", "r", "⚠️ 前方 4 格落差", "动作栏即时提示，不等待模型"),
            ("18:20", "t", "🌙 入夜远离家", "“天黑了，家在东北方 230 格，要我带路吗？”"),
            ("18:50", "t", "💀 死亡之后", "“物品约 5 分钟后消失，跟着粒子走就能找回。”"),
        ],
        "quiet": "🤫 判断不值得打扰，保持安静",
        "pro_rules": ["每 15 分钟最多一条", "同一件事绝不重复", "只能使用只读工具", "一句“别打扰我”立即关闭"],
        "s6_eye": "06 · 生活助手",
        "s6_title": "你的冒险，它都放在心上",
        "life": [("⏰", "提醒", "“十分钟后提醒我收麦子”“天黑前叫我回家”，支持日落、入夜、日出", "⏰ 收麦子 · 还有 4 分钟"),
                 ("💀", "死亡回溯", "死在哪、为什么、掉了什么、还剩多久消失，跟着粒子回去", "💀 被苦力怕炸死 · 掉落 23 组"),
                 ("📊", "统计与成就", "今天挖了多少钻石？下一个最容易完成的成就是什么？", "💎 今日 +17 · 生涯 412"),
                 ("📝", "个人笔记", "待办、偏好与备忘长期保存，之后的建议都会参考", "📝 喜欢中式建筑"),
                 ("🗡️", "物品检查", "剩余耐久、附魔、铁砧修复费用，值不值得修一看便知", "🗡️ 耐久 61% · 修复 7 级"),
                 ("🧑‍🌾", "村民交易", "附近谁卖经验修补、谁收小麦，包括流浪商人", "📗 经验修补 · 24 绿宝石"),
                 ("⚒️", "真实配方", "只用本服真正注册的配方，对照背包告诉你还差什么", "⚒️ 还差 2 根木棍"),
                 ("📚", "服务器知识", "只依据服主写的资料回答规则与玩法，不知道就直说", "📚 本服每周日刷新末地")],
        "s7_eye": "07 · 插件生态",
        "s7_title": "它读得懂你的整个服务器",
        "s7_sub": "安装即用、无需配置。经济、领地、权限、城镇、传送、技能……Panda 能直接读取这些插件的数据来回答，没装的插件自动隐藏。Jobs、Quests、商店等更多插件通过 PlaceholderAPI 覆盖。",
        "hub": "PandaGPT",
        "nodes": [("💰", "Vault", "余额 · 能否买得起"), ("🧩", "PlaceholderAPI", "数千个扩展"), ("🎖️", "LuckPerms", "权限组 · 晋升"),
                  ("🛡️", "WorldGuard", "区域 · PvP"), ("🏡", "GriefPrevention", "领地 · 信任"), ("🏰", "Towny", "城镇 · 地块"),
                  ("🗺️", "Lands", "领地 · 角色"), ("🏠", "EssentialsX", "家 · 传送点"), ("🕵️", "CoreProtect", "谁动了方块"),
                  ("⚔️", "mcMMO", "技能等级"), ("✨", "AuraSkills", "技能 · 属性")],
        "qa": [("我买得起钻石剑吗？", "💰 你有 1,280 金币，商店售价 900，买得起。"),
               ("这块地是谁的？能建东西吗？", "🏡 这是 Alex 的领地，你没有建造权限。"),
               ("谁拆了我的箱子？", "🕵️ 10 分钟前 Bob 破坏了这个箱子。")],
        "s8_eye": "08 · 大脑",
        "s8_title": "接入世界上最聪明的模型",
        "s8_sub": "15 家服务商开箱即用，另外支持任意 OpenAI 兼容接口。从 Claude、GPT、Gemini 到 DeepSeek、通义千问、Kimi，随时切换；不支持图片的模型同样可用。",
        "models": ["OpenAI", "Gemini", "Claude", "Azure OpenAI", "xAI Grok", "Mistral", "Groq", "OpenRouter",
                   "DeepSeek", "通义千问", "Kimi", "智谱 GLM", "豆包", "硅基流动", "任意兼容接口"],
        "skills_title": "34 个专业技能，按需加载",
        "skills_sub": "技能是普通的 Markdown 文件。服主可以改写 Panda 的行为、加入自己服务器的知识，或者关掉不需要的技能。",
        "s9_eye": "09 · 可靠",
        "s9_title": "强大，但始终在你的掌控之中",
        "trust": [("🛡️", "命令绝不自动执行", "只能提出建议，玩家点击确认后，以玩家本人的身份和权限执行"),
                  ("🧵", "不卡服", "每 tick 共享 2 ms 预算，服务器繁忙时自动退让，从不加载新区块"),
                  ("🔒", "尊重隐私", "从不读取别人的箱子，默认不记录对话，敏感变量一律不解析"),
                  ("🌍", "12 种语言", "每位玩家看到自己客户端的语言，Panda 用你提问的语言回答")],
        "hello": ["你好", "你好", "Hello", "こんにちは", "안녕하세요", "Hola", "Bonjour", "Hallo", "Привет", "Olá", "مرحبا", "नमस्ते"],
        "end_title": "让你的服务器，第一次真正“活”过来",
        "end_sub": "不需要学习任何命令。玩家只要开口，Panda 就在。",
        "end_cta": "github.com/obsidianplusplus/PandaGPT",
        "end_small": "Paper 26.3 · Java 25 · 中英双语 Wiki",
    },
    "en": {
        "lang": "en",
        "eyebrow": "MINECRAFT · AI AGENT · PAPER 26.3",
        "slogan": "An AI companion that lives in your server",
        "sub": "It sees the world in front of you, remembers where you've been, understands your whole server,<br>speaks up at the right moment, and stays quiet when it should.",
        "zero": "Zero player commands · just say panda in chat",
        "hero_chat": [("p", "Steve", "panda how do I use that furnace?"), ("a", "Panda", "#3 is a furnace, outlined with particles. Your 12 raw iron make 12 ingots.")],
        "hero_tag": "#3 furnace",
        "hero_bar": "👀 Looking…",
        "nums": [("60", "agent tools"), ("34", "skills"), ("15", "AI providers"), ("11", "plugin hooks"), ("12", "languages"), ("0", "player commands")],
        "s1_eye": "01 · MORE THAN A CHATBOT",
        "s1_title": "It thinks, then it acts",
        "s1_sub": "Ordinary AI plugins just forward your question to a model. PandaGPT is a real agent: it decides whether to look, search or ask first, and a single answer can involve a dozen steps of reasoning and tool use.",
        "trace_q": "panda am I ready to fight the Ender Dragon?",
        "trace": [
            ("📜", "load_skill", "combat", "Loads the combat & boss skill"),
            ("🎒", "get_player_status", "", "Diamond sword Sharpness IV · full diamond armour · 32 arrows"),
            ("🗡️", "inspect_item", "chestplate", "Chestplate at 18% durability, has Mending"),
            ("🔍", "search_server_knowledge", "the End", "The End resets every Sunday; the egg goes to the slayer"),
            ("🧪", "find_recipes", "slow_falling", "Slow Falling: phantom membrane + awkward potion"),
        ],
        "trace_a": "Almost. Repair your chestplate first and brew a few Slow Falling potions so a dragon knock-up can't kill you; bring a water bucket and 64 blocks. The egg goes to the slayer here. Good luck!",
        "vs_title": "Typical AI chat plugin  vs  PandaGPT",
        "vs": ["Sees what's in front of you", "Remembers your home, chests and death point", "Checks your inventory against real server recipes",
               "Reads economy, claims, ranks and other plugin data", "Multi-step reasoning with parallel tool calls",
               "Speaks up at the right moment, quiet otherwise", "Acts for you safely, after you confirm"],
        "vs_a": "Others", "vs_b": "PandaGPT",
        "s2_eye": "02 · VISION",
        "s2_title": "It can see, with no client mod at all",
        "s2_sub": "The server reconstructs your first-person view with 160×90 rays and shades every pixel with vanilla models and textures. Point at \"that furnace on the left\" and it knows exactly which one you mean.",
        "vis_caps": ["Real-texture reconstruction", "Object IDs + entity boxes"],
        "vis": [("🎨", "Vanilla textures & models", "Block states, model UVs, rotations, biome tints; stairs and slabs keep their shapes"),
                ("🔢", "Object IDs", "Regions in view are numbered; furnaces and crafting tables are identified individually"),
                ("✨", "Particle highlights", "After re-checking the live block, it outlines the target with particles only you see"),
                ("🐺", "Entity awareness", "Mob positions, hitboxes, occlusion and who they're chasing"),
                ("🧱", "7×7×7 spatial sense", "Can you stand ahead? How far is the drop? Any lava? Cell by cell"),
                ("⏱️", "Continuous observation", "Compares views over time: which blocks changed, which mob is closing in")],
        "grid_title": "Space ahead",
        "grid_legend": [("ok", "standable"), ("drop", "drop"), ("lava", "danger"), ("unk", "unknown")],
        "space": "Panda copies the real collision shapes around you and checks, column by column, <b>whether you can stand</b>, <b>how far the drop is</b> and <b>whether there is lava</b>. Urgent risks flash in the action bar instantly, without waiting for the model.",
        "s3_eye": "03 · MEMORY & GUIDANCE",
        "s3_title": "Every place you've been, remembered",
        "s3_sub": "Say \"remember this\" and Panda saves the exact block; sleep in a bed and it marks home for you. When you want to go back, a particle trail only you can see leads you to the door.",
        "home": "🏠 home",
        "bar": "🧭 home ↗ 230 blocks · height +8",
        "mem_title": "Steve's memory",
        "mem": [("🏠", "home", "230 blocks NE"), ("📦", "diamond chest", "at home · verified"), ("⛏️", "mine entrance", "412 blocks W"), ("💀", "death point", "4 min ago · items vanish in 1 min"), ("🌾", "wheat farm", "36 blocks S")],
        "s4_eye": "04 · CREATE",
        "s4_title": "Describe it. It builds it on the spot",
        "s4_sub": "\"Design a 7×7 cabin here.\" The agent composes blocks, walls, cylinders, spheres and pyramids into a building and shows it in place as ghost blocks only you can see. The world is never touched.",
        "bom_title": "Bill of materials",
        "bom": [("Oak planks", "96", "65"), ("Oak logs", "12", "12"), ("Stone bricks", "49", "49"), ("Glass", "4", "0"), ("Dark oak", "120", "88")],
        "bom_have": "have", "bom_need": "need",
        "bom_note": "31 planks, 4 glass and 32 dark oak short → one sentence turns it into a goal",
        "s5_eye": "05 · PROACTIVE AI",
        "s5_title": "Proactive, but never pushy",
        "s5_sub": "Panda keeps track of your situation, but checks the facts before every word. When a tip isn't worth it, it chooses silence.",
        "timeline": [
            ("07:10", "q", "Passing a village"),
            ("08:30", "t", "⛏️ Departure check", "\"Only 3 torches left. Top up first?\""),
            ("09:15", "q", "Mining. No interruptions while you focus"),
            ("10:40", "t", "✅ Goal ready", "\"You have everything for the iron pickaxe; the table is on your left.\""),
            ("12:05", "r", "⚠️ 4-block drop ahead", "Instant action-bar warning, no model needed"),
            ("18:20", "t", "🌙 Night, far from home", "\"It's dark. Home is 230 blocks NE. Want me to guide you?\""),
            ("18:50", "t", "💀 After a death", "\"Your items vanish in ~5 minutes. Follow the particles.\""),
        ],
        "quiet": "🤫 Not worth interrupting, so it stays quiet",
        "pro_rules": ["At most one tip per 15 min", "Never repeats itself", "Read-only tools only", "\"Don't disturb me\" turns it off"],
        "s6_eye": "06 · EVERYDAY COMPANION",
        "s6_title": "It keeps your adventure in mind",
        "life": [("⏰", "Reminders", "\"Remind me to harvest in ten minutes\", \"tell me to go home before dark\"; sunset, nightfall, sunrise", "⏰ harvest wheat · 4 min"),
                 ("💀", "Death recall", "Where, why, what dropped, how long is left, and a trail back", "💀 creeper · 23 stacks dropped"),
                 ("📊", "Stats & advancements", "Diamonds mined today? Which advancement is closest?", "💎 today +17 · total 412"),
                 ("📝", "Personal notes", "To-dos, preferences and memos that shape later advice", "📝 loves Japanese builds"),
                 ("🗡️", "Item inspection", "Durability, enchantments and anvil cost: is it worth repairing?", "🗡️ 61% · repair 7 levels"),
                 ("🧑‍🌾", "Villager trades", "Who nearby sells Mending, who buys wheat, wandering traders too", "📗 Mending · 24 emeralds"),
                 ("⚒️", "Real recipes", "Uses only recipes registered on your server and tells you what's missing", "⚒️ 2 sticks short"),
                 ("📚", "Server knowledge", "Answers rules and gameplay from your docs, and says so when it doesn't know", "📚 The End resets Sundays")],
        "s7_eye": "07 · PLUGIN ECOSYSTEM",
        "s7_title": "It understands your entire server",
        "s7_sub": "Install and go, no configuration. Economy, claims, ranks, towns, homes, skills… Panda reads these plugins directly to answer, and anything missing hides itself. Jobs, Quests, shops and more come through PlaceholderAPI.",
        "hub": "PandaGPT",
        "nodes": [("💰", "Vault", "balance · afford?"), ("🧩", "PlaceholderAPI", "1000s of expansions"), ("🎖️", "LuckPerms", "ranks · promotions"),
                  ("🛡️", "WorldGuard", "regions · PvP"), ("🏡", "GriefPrevention", "claims · trust"), ("🏰", "Towny", "towns · plots"),
                  ("🗺️", "Lands", "lands · roles"), ("🏠", "EssentialsX", "homes · warps"), ("🕵️", "CoreProtect", "who broke it"),
                  ("⚔️", "mcMMO", "skill levels"), ("✨", "AuraSkills", "skills · stats")],
        "qa": [("Can I afford a diamond sword?", "💰 You have 1,280 coins; the shop sells it for 900. Yes."),
               ("Whose land is this? Can I build?", "🏡 This is Alex's claim; you don't have build trust."),
               ("Who broke my chest?", "🕵️ Bob broke this chest 10 minutes ago.")],
        "s8_eye": "08 · THE BRAIN",
        "s8_title": "Powered by the best AI models",
        "s8_sub": "15 providers out of the box, plus any OpenAI-compatible endpoint. Switch between Claude, GPT, Gemini, DeepSeek, Qwen, Kimi and more at any time; models without image input work too.",
        "models": ["OpenAI", "Gemini", "Claude", "Azure OpenAI", "xAI Grok", "Mistral", "Groq", "OpenRouter",
                   "DeepSeek", "Qwen", "Kimi", "Zhipu GLM", "Doubao", "SiliconFlow", "any compatible API"],
        "skills_title": "34 expert skills, loaded on demand",
        "skills_sub": "Skills are plain Markdown files. Rewrite how Panda behaves, teach it your server's lore, or switch off what you don't need.",
        "s9_eye": "09 · TRUSTWORTHY",
        "s9_title": "Powerful, and always under your control",
        "trust": [("🛡️", "Never runs commands on its own", "It can only suggest; the player confirms and it runs with their own identity and permissions"),
                  ("🧵", "Lag-aware", "A shared 2 ms per-tick budget that backs off when the server is busy; never loads new chunks"),
                  ("🔒", "Respects privacy", "Never reads other players' chests, doesn't log chats by default, filters sensitive placeholders"),
                  ("🌍", "12 languages", "Every player sees their own client language; Panda answers in the language you ask in")],
        "hello": ["你好", "你好", "Hello", "こんにちは", "안녕하세요", "Hola", "Bonjour", "Hallo", "Привет", "Olá", "مرحبا", "नमस्ते"],
        "end_title": "Bring your server to life",
        "end_sub": "No commands to learn. Players just talk, and Panda is there.",
        "end_cta": "github.com/obsidianplusplus/PandaGPT",
        "end_small": "Paper 26.3 · Java 25 · bilingual wiki",
    },
}

SKILLS = ["panda", "places", "navigation", "goals", "crafting", "survival", "vision", "building", "reminders", "journal",
          "notes", "items", "trading", "commands", "server-guide", "teleport", "combat", "farming", "redstone",
          "enchanting", "exploration", "new-player", "quests-and-jobs", "economy", "placeholders", "ranks", "regions",
          "claims", "towny", "lands", "essentials", "block-history", "mcmmo", "auraskills"]

CSS = """
:root{--bg:#070b1a;--bg2:#0b1126;--panel:#111a38;--panel2:#16224a;--line:#24345f;--text:#eef1ff;--mute:#9aa6d1;
--green:#5ee36b;--sky:#59c3ff;--gold:#ffcf4a;--violet:#a98bff;--red:#ff6b6b}
*{box-sizing:border-box;margin:0;padding:0}
html{background:#ff00ff}
body{width:1000px;background:var(--bg);color:var(--text);
font-family:-apple-system,"SF Pro Display","PingFang SC","Hiragino Sans GB","Helvetica Neue",Arial,sans-serif;-webkit-font-smoothing:antialiased}
.wrap{padding:0 56px}
.sec{padding:96px 56px 24px;position:relative}
.alt{background:linear-gradient(180deg,var(--bg) 0%,var(--bg2) 12%,var(--bg2) 88%,var(--bg) 100%)}
.eye{display:inline-block;font-size:14px;font-weight:800;letter-spacing:2.5px;color:var(--green);background:#5ee36b14;border:1px solid #5ee36b40;padding:7px 14px;border-radius:999px;margin-bottom:18px}
.h{font-size:50px;line-height:1.15;font-weight:900;letter-spacing:-1px;margin-bottom:16px}
.h .g{background:linear-gradient(90deg,#fff 0%,#bfe9ff 60%,#9cf7b0 100%);-webkit-background-clip:text;color:transparent}
.lead{font-size:20px;line-height:1.7;color:var(--mute);margin-bottom:36px}
.hero{position:relative;padding:76px 56px 64px;text-align:center;overflow:hidden;
background:radial-gradient(900px 520px at 50% 0%,#2a47d8 0%,#2a47d800 65%),radial-gradient(600px 400px at 85% 40%,#5ee36b22,transparent 70%),radial-gradient(600px 400px at 10% 50%,#a98bff22,transparent 70%),var(--bg)}
.hero:before{content:"";position:absolute;inset:0;background-image:linear-gradient(#ffffff10 1px,transparent 1px),linear-gradient(90deg,#ffffff10 1px,transparent 1px);background-size:44px 44px;mask-image:linear-gradient(180deg,#000,transparent 75%)}
.hero>*{position:relative}
.heye{font-size:14px;letter-spacing:4px;font-weight:800;color:#bcd0ff;margin-bottom:22px}
.logo{font-size:96px;line-height:1;filter:drop-shadow(0 14px 30px #000a)}
.name{font-size:96px;font-weight:900;letter-spacing:-3px;line-height:1.05;margin:10px 0 14px;background:linear-gradient(180deg,#fff,#b9c7ff);-webkit-background-clip:text;color:transparent}
.slogan{font-size:36px;font-weight:900;margin-bottom:16px;background:linear-gradient(90deg,var(--green),var(--sky));-webkit-background-clip:text;color:transparent}
.hsub{font-size:19px;line-height:1.7;color:#c3cbec}
.zero{display:inline-block;margin:26px 0 40px;font-size:16px;font-weight:800;padding:10px 20px;border-radius:999px;background:#ffffff10;border:1px solid #ffffff2a}
.game{position:relative;border-radius:22px;overflow:hidden;border:2px solid #ffffff22;box-shadow:0 40px 90px #000c,0 0 0 8px #ffffff08}
.game img{display:block;width:100%;image-rendering:pixelated}
.game .chat{position:absolute;left:18px;bottom:62px;text-align:left;width:620px}
.game .chat div{background:#000000a8;padding:7px 12px;font-size:17px;margin-top:4px;border-radius:4px}
.game .chat .w{font-weight:800}
.game .chat .a .w{color:var(--green)}
.game .abar{position:absolute;left:50%;transform:translateX(-50%);bottom:18px;background:#000000a0;padding:7px 16px;border-radius:8px;font-weight:800;font-size:16px}
.game .tag{position:absolute;left:57.5%;top:47%;width:62px;height:62px;border:3px dashed #7dffb0;border-radius:8px;box-shadow:0 0 18px #7dffb0aa}
.game .tag span{position:absolute;top:-34px;left:50%;transform:translateX(-50%);white-space:nowrap;background:#7dffb0;color:#08210f;font-weight:900;font-size:15px;padding:4px 10px;border-radius:8px}
.nums{display:grid;grid-template-columns:repeat(6,1fr);gap:12px;margin-top:44px}
.num{background:#ffffff0b;border:1px solid #ffffff1c;border-radius:18px;padding:18px 6px}
.num b{display:block;font-size:42px;font-weight:900;line-height:1.1;background:linear-gradient(180deg,#ffe17a,#ffb648);-webkit-background-clip:text;color:transparent}
.num span{font-size:14px;color:var(--mute)}
.grass{height:20px;background:repeating-linear-gradient(90deg,#3fae4a 0 20px,#4cc257 20px 40px);border-bottom:12px solid #5a3d22}
.trace{background:#060a18;border:1px solid var(--line);border-radius:22px;padding:26px 28px;box-shadow:0 30px 70px #0008}
.tq{font-size:19px;margin-bottom:18px}.tq b{color:#fff}
.step{display:flex;gap:14px;align-items:center;padding:11px 0;border-bottom:1px dashed #1f2c55}
.step .i{width:40px;height:40px;flex:none;border-radius:12px;background:#16224a;display:flex;align-items:center;justify-content:center;font-size:20px}
.step code{font:700 15px/1 "SF Mono",Menlo,monospace;color:var(--sky);background:#59c3ff14;border:1px solid #59c3ff33;padding:6px 9px;border-radius:8px;flex:none}
.step code em{color:var(--gold);font-style:normal}
.step span{font-size:15.5px;color:#c9d1f3}
.ta{margin-top:18px;padding:16px 18px;border-radius:14px;background:linear-gradient(90deg,#5ee36b1c,#59c3ff12);border:1px solid #5ee36b44;font-size:17px;line-height:1.6}
.ta b{color:var(--green)}
.vs{margin-top:36px;border-radius:22px;overflow:hidden;border:1px solid var(--line)}
.vs .row{display:grid;grid-template-columns:1fr 170px 170px;align-items:center;border-top:1px solid #1d2a52}
.vs .row div{padding:14px 20px;font-size:16.5px}
.vs .hd{background:#16224a;border-top:none}.vs .hd div{font-weight:900;font-size:15px;color:var(--mute)}
.vs .hd div:last-child{color:var(--green)}
.vs .c{text-align:center;font-size:20px!important}
.vs .row div:last-child{background:#5ee36b0f}
.shots{display:grid;grid-template-columns:1fr 1fr;gap:18px;margin-bottom:22px}
.shot{background:var(--panel);border:1px solid var(--line);border-radius:18px;padding:10px}
.shot img{display:block;width:100%;border-radius:10px;image-rendering:pixelated}
.shot div{text-align:center;font-size:14px;color:var(--mute);padding:10px 0 4px}
.g3{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}
.g2{display:grid;grid-template-columns:repeat(2,1fr);gap:16px}
.card{background:linear-gradient(180deg,var(--panel2),var(--panel));border:1px solid var(--line);border-radius:18px;padding:20px}
.card .ic{font-size:30px;margin-bottom:10px}
.card h3{font-size:19px;font-weight:800;margin-bottom:7px}
.card p{font-size:15px;line-height:1.55;color:var(--mute)}
.space{display:grid;grid-template-columns:330px 1fr;gap:26px;margin-top:22px;align-items:center}
.cells{background:var(--panel);border:1px solid var(--line);border-radius:18px;padding:18px}
.cells h4{font-size:15px;color:var(--mute);margin-bottom:12px;text-align:center}
.cg{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}
.cell{height:62px;border-radius:10px;display:flex;align-items:center;justify-content:center;font-weight:900;font-size:16px}
.ok{background:#5ee36b33;border:1px solid #5ee36b88;color:#aef5b6}.drop{background:#ffcf4a2e;border:1px solid #ffcf4a88;color:#ffe08a}
.lava{background:#ff6b6b33;border:1px solid #ff6b6b99;color:#ffb3b3}.unk{background:#ffffff10;border:1px dashed #ffffff40;color:#aab}
.me{grid-column:2;height:44px;margin-top:4px;border-radius:10px;background:#59c3ff2a;border:1px solid #59c3ff99;display:flex;align-items:center;justify-content:center;font-size:22px}
.legend{display:flex;gap:8px;justify-content:center;margin-top:12px;flex-wrap:wrap}
.legend span{font-size:12.5px;padding:4px 8px;border-radius:8px}
.spacetext{font-size:17px;line-height:1.75;color:#c9d1f3}
.spacetext b{color:#fff}
.memwrap{display:grid;grid-template-columns:1fr 300px;gap:18px;margin-top:20px;align-items:center}
.mem{background:var(--panel);border:1px solid var(--line);border-radius:20px;padding:20px}
.mem h4{font-size:15px;color:var(--mute);margin-bottom:10px}
.mi{display:flex;align-items:center;gap:12px;padding:10px 0;border-bottom:1px solid #1d2a52}
.mi:last-child{border-bottom:none}
.mi .e{font-size:22px}.mi b{font-size:16px;display:block}.mi span{font-size:13px;color:var(--mute)}
.build{display:grid;grid-template-columns:1fr 300px;gap:18px;align-items:center}
.iso{background:radial-gradient(circle at 50% 60%,#13204a,#0a1027);border:1px solid var(--line);border-radius:22px;padding:6px;position:relative}
.iso .badge{position:absolute;top:16px;left:16px;font-size:13px;font-weight:800;color:#bff0ff;background:#59c3ff22;border:1px solid #59c3ff66;padding:5px 10px;border-radius:8px}
.bom{background:var(--panel);border:1px solid var(--line);border-radius:20px;padding:20px}
.bom h4{font-size:16px;margin-bottom:12px}
.br{display:grid;grid-template-columns:1fr 50px 50px;font-size:14.5px;padding:8px 0;border-bottom:1px solid #1d2a52}
.br.hd{color:var(--mute);font-size:12.5px;font-weight:800}
.br span:not(:first-child){text-align:right}
.br .lack{color:var(--gold);font-weight:800}
.bomn{margin-top:14px;font-size:14px;line-height:1.5;color:#bff0ff;background:#59c3ff14;border:1px solid #59c3ff44;border-radius:12px;padding:10px 12px}
.shapes{display:flex;gap:8px;flex-wrap:wrap;margin-top:18px}
.shapes span{font:700 13px "SF Mono",Menlo,monospace;color:#bff0ff;background:#59c3ff12;border:1px solid #59c3ff40;padding:6px 10px;border-radius:8px}
.tl{position:relative;padding-left:120px}
.tl:before{content:"";position:absolute;left:96px;top:10px;bottom:10px;width:3px;background:linear-gradient(180deg,#a98bff,#59c3ff,#5ee36b,#ffcf4a,#5a6aa0);border-radius:3px}
.ti{position:relative;margin-bottom:16px}
.ti .tm{position:absolute;left:-120px;width:80px;text-align:right;top:14px;font:800 16px "SF Mono",Menlo,monospace;color:var(--mute)}
.ti:before{content:"";position:absolute;left:-31px;top:17px;width:14px;height:14px;border-radius:50%;background:var(--bg);border:3px solid #59c3ff}
.ti.t .bx{background:linear-gradient(90deg,#5ee36b18,#111a38);border:1px solid #5ee36b55;border-left:5px solid var(--green);border-radius:16px;padding:14px 18px}
.ti.r .bx{background:linear-gradient(90deg,#ffcf4a18,#111a38);border:1px solid #ffcf4a55;border-left:5px solid var(--gold);border-radius:16px;padding:14px 18px}
.ti .bx h4{font-size:17px;margin-bottom:5px}.ti .bx p{font-size:16px;color:#d6defc}
.ti.q .bx{padding:12px 18px;border:1px dashed #2f3f70;border-radius:14px;color:#7f8bb8;font-size:15px}
.ti.q:before{border-color:#3b4a7a}
.rules{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-top:22px}
.rules div{display:flex;align-items:center;justify-content:center;text-align:center;font-size:14.5px;font-weight:700;padding:14px 8px;border-radius:14px;background:#ffffff08;border:1px solid var(--line)}
.life{display:grid;grid-template-columns:repeat(2,1fr);gap:16px}
.lc{background:linear-gradient(180deg,var(--panel2),var(--panel));border:1px solid var(--line);border-radius:20px;padding:20px;display:flex;flex-direction:column}
.lc .top{display:flex;gap:12px;align-items:center;margin-bottom:8px}.lc .top .ic{font-size:28px}.lc h3{font-size:19px}
.lc p{font-size:15px;line-height:1.55;color:var(--mute);flex:1}
.lc .ui{margin-top:14px;align-self:flex-start;background:#000000a0;border:1px solid #ffffff22;border-radius:8px;padding:7px 12px;font-weight:800;font-size:14.5px}
.qa{display:grid;gap:12px;margin-top:10px}
.qr{display:grid;grid-template-columns:1fr 1.25fr;gap:12px;align-items:center}
.qr .q{justify-self:end;background:#16224a;border:1px solid #2f427a;border-radius:16px 16px 4px 16px;padding:12px 16px;font-size:16px}
.qr .a{background:linear-gradient(90deg,#5ee36b1a,#59c3ff10);border:1px solid #5ee36b55;border-radius:16px 16px 16px 4px;padding:12px 16px;font-size:16px}
.models{display:flex;flex-wrap:wrap;gap:12px;justify-content:center}
.models span{font-size:18px;font-weight:800;padding:12px 18px;border-radius:14px;background:linear-gradient(180deg,#1a2756,#121b3d);border:1px solid #2f427a}
.models span:nth-child(3n+1){border-color:#5ee36b66}.models span:nth-child(3n+2){border-color:#59c3ff66}.models span:nth-child(3n){border-color:#a98bff66}
.skills{margin-top:46px;background:var(--panel);border:1px solid var(--line);border-radius:22px;padding:26px}
.skills h3{font-size:24px;margin-bottom:6px}.skills p{font-size:15.5px;color:var(--mute);margin-bottom:16px;line-height:1.6}
.cloud{display:flex;flex-wrap:wrap;gap:8px}
.cloud span{font:700 14px "SF Mono",Menlo,monospace;padding:7px 11px;border-radius:9px;background:#ffffff0a;border:1px solid #ffffff1c;color:#d6defc}
.cloud span.k{color:var(--green);border-color:#5ee36b55}
.hello{display:flex;flex-wrap:wrap;gap:10px;justify-content:center;margin-top:26px}
.hello span{font-size:17px;font-weight:800;padding:9px 16px;border-radius:999px;background:#ffffff0c;border:1px solid #ffffff22}
.end{margin-top:90px;padding:96px 56px 84px;text-align:center;position:relative;overflow:hidden;
background:radial-gradient(800px 460px at 50% 100%,#2a47d8aa,transparent 70%),linear-gradient(180deg,var(--bg),#050816)}
.end .logo{font-size:84px}
.end h2{font-size:52px;font-weight:900;letter-spacing:-1px;margin:16px 0 14px;background:linear-gradient(90deg,#fff,#bfe9ff,#9cf7b0);-webkit-background-clip:text;color:transparent}
.end p{font-size:20px;color:#c3cbec}
.cta{display:inline-block;margin-top:32px;font-size:22px;font-weight:900;color:#06140a;background:linear-gradient(90deg,var(--green),var(--sky));padding:16px 28px;border-radius:16px;box-shadow:0 18px 40px #59c3ff44}
.end small{display:block;margin-top:18px;color:var(--mute);font-size:15px}
"""


def section(eye, title, lead, body, alt=False):
    lead_html = f'<p class="lead">{esc(lead)}</p>' if lead else ""
    return (f'<div class="{"alt" if alt else ""}"><div class="wrap sec"><div class="eye">{esc(eye)}</div>'
            f'<h2 class="h"><span class="g">{esc(title)}</span></h2>{lead_html}{body}</div></div>')


def page(t):
    hero_chat = "".join(
        f'<div class="{k}"><span class="w">{"&lt;" + w + "&gt;" if k == "p" else "[" + w + "]"}</span> {esc(s)}</div>'
        for k, w, s in t["hero_chat"])
    nums = "".join(f'<div class="num"><b>{n}</b><span>{esc(l)}</span></div>' for n, l in t["nums"])
    hero = f"""<div class="hero">
<div class="heye">{esc(t['eyebrow'])}</div><div class="logo">🐼</div><div class="name">PandaGPT</div>
<div class="slogan">{esc(t['slogan'])}</div><div class="hsub">{t['sub']}</div>
<div class="zero">💬 {esc(t['zero'])}</div>
<div class="game"><img src="../texture-preview.png"><div class="tag"><span>{esc(t['hero_tag'])}</span></div>
<div class="chat">{hero_chat}</div><div class="abar">{esc(t['hero_bar'])}</div></div>
<div class="nums">{nums}</div></div><div class="grass"></div>"""

    steps = "".join(
        f'<div class="step"><div class="i">{i}</div><code>{esc(f)}(<em>{esc(a)}</em>)</code><span>{esc(d)}</span></div>'
        for i, f, a, d in t["trace"])
    vs_rows = "".join(f'<div class="row"><div>{esc(r)}</div><div class="c">✖️</div><div class="c">✅</div></div>' for r in t["vs"])
    s1 = section(t["s1_eye"], t["s1_title"], t["s1_sub"],
                 f'<div class="trace"><div class="tq"><b>&lt;Steve&gt;</b> {esc(t["trace_q"])}</div>{steps}'
                 f'<div class="ta"><b>[Panda]</b> {esc(t["trace_a"])}</div></div>'
                 f'<div class="vs"><div class="row hd"><div>{esc(t["vs_title"])}</div><div class="c">{esc(t["vs_a"])}</div>'
                 f'<div class="c">{esc(t["vs_b"])}</div></div>{vs_rows}</div>')

    vis = "".join(f'<div class="card"><div class="ic">{i}</div><h3>{esc(h)}</h3><p>{esc(p)}</p></div>' for i, h, p in t["vis"])
    cls = ["drop", "ok", "lava", "ok", "ok", "unk", "ok", "ok", "drop"]
    marks = {"ok": "✓", "drop": "↓3", "lava": "🔥", "unk": "?"}
    cells = "".join(f'<div class="cell {c}">{marks[c]}</div>' for c in cls)
    legend = "".join(f'<span class="{c}">{esc(l)}</span>' for c, l in t["grid_legend"])
    s2 = section(t["s2_eye"], t["s2_title"], t["s2_sub"],
                 f'<div class="shots"><div class="shot"><img src="../texture-preview.png"><div>{esc(t["vis_caps"][0])}</div></div>'
                 f'<div class="shot"><img src="../perception-preview.png"><div>{esc(t["vis_caps"][1])}</div></div></div>'
                 f'<div class="g3">{vis}</div>'
                 f'<div class="space"><div class="cells"><h4>{esc(t["grid_title"])}</h4><div class="cg">{cells}<div class="me">🧍</div></div>'
                 f'<div class="legend">{legend}</div></div><div class="spacetext">{t["space"]}</div></div>', alt=True)

    mem = "".join(f'<div class="mi"><div class="e">{e}</div><div><b>{esc(n)}</b><span>{esc(d)}</span></div></div>' for e, n, d in t["mem"])
    s3 = section(t["s3_eye"], t["s3_title"], t["s3_sub"],
                 f'<div class="memwrap"><div>{trail(t["home"], t["bar"])}</div>'
                 f'<div class="mem"><h4>{esc(t["mem_title"])}</h4>{mem}</div></div>')

    rows = "".join(
        f'<div class="br"><span>{esc(n)}</span><span>{need}</span><span class="{"lack" if int(have) < int(need) else ""}">{have}</span></div>'
        for n, need, have in t["bom"])
    shapes = "".join(f"<span>{s}</span>" for s in ("block", "fill", "hollow", "walls", "line", "cylinder", "sphere", "pyramid"))
    s4 = section(t["s4_eye"], t["s4_title"], t["s4_sub"],
                 f'<div class="build"><div class="iso"><div class="badge">👻 GHOST PREVIEW</div>{iso_cabin()}</div>'
                 f'<div class="bom"><h4>🧾 {esc(t["bom_title"])}</h4>'
                 f'<div class="br hd"><span></span><span>{esc(t["bom_need"])}</span><span>{esc(t["bom_have"])}</span></div>{rows}'
                 f'<div class="bomn">{esc(t["bom_note"])}</div></div></div><div class="shapes">{shapes}</div>', alt=True)

    items = []
    for item in t["timeline"]:
        if item[1] == "q":
            items.append(f'<div class="ti q"><div class="tm">{item[0]}</div><div class="bx">{esc(t["quiet"])} · {esc(item[2])}</div></div>')
        else:
            items.append(f'<div class="ti {item[1]}"><div class="tm">{item[0]}</div><div class="bx"><h4>{esc(item[2])}</h4><p>{esc(item[3])}</p></div></div>')
    rules = "".join(f"<div>{esc(r)}</div>" for r in t["pro_rules"])
    s5 = section(t["s5_eye"], t["s5_title"], t["s5_sub"], f'<div class="tl">{"".join(items)}</div><div class="rules">{rules}</div>')

    life = "".join(
        f'<div class="lc"><div class="top"><div class="ic">{i}</div><h3>{esc(h)}</h3></div><p>{esc(p)}</p><div class="ui">{esc(u)}</div></div>'
        for i, h, p, u in t["life"])
    s6 = section(t["s6_eye"], t["s6_title"], "", f'<div class="life">{life}</div>', alt=True)

    qa = "".join(f'<div class="qr"><div class="q">{esc(q)}</div><div class="a">{esc(a)}</div></div>' for q, a in t["qa"])
    s7 = section(t["s7_eye"], t["s7_title"], t["s7_sub"], f'{hub(t["hub"], t["nodes"])}<div class="qa">{qa}</div>')

    models = "".join(f"<span>{esc(m)}</span>" for m in t["models"])
    cloud = "".join(f'<span class="{"k" if i % 4 == 0 else ""}">{s}</span>' for i, s in enumerate(SKILLS))
    s8 = section(t["s8_eye"], t["s8_title"], t["s8_sub"],
                 f'<div class="models">{models}</div><div class="skills"><h3>📜 {esc(t["skills_title"])}</h3>'
                 f'<p>{esc(t["skills_sub"])}</p><div class="cloud">{cloud}</div></div>', alt=True)

    trust = "".join(f'<div class="card"><div class="ic">{i}</div><h3>{esc(h)}</h3><p>{esc(p)}</p></div>' for i, h, p in t["trust"])
    hello = "".join(f"<span>{esc(h)}</span>" for h in dict.fromkeys(t["hello"]))
    s9 = section(t["s9_eye"], t["s9_title"], "", f'<div class="g2">{trust}</div><div class="hello">{hello}</div>')

    end = (f'<div class="end"><div class="logo">🐼</div><h2>{esc(t["end_title"])}</h2><p>{esc(t["end_sub"])}</p>'
           f'<div class="cta">{esc(t["end_cta"])}</div><small>{esc(t["end_small"])}</small></div>')
    return (f'<!doctype html><html lang="{t["lang"]}"><head><meta charset="utf-8"><title>PandaGPT</title><style>{CSS}</style></head>'
            f'<body>{hero}{s1}{s2}{s3}{s4}{s5}{s6}{s7}{s8}{s9}{end}'
            f'<script>document.title = "H" + Math.ceil(document.documentElement.scrollHeight);</script></body></html>')


TRIM = """<!doctype html><html><body><script>
const img = new Image();
img.onload = () => {
  const c = document.createElement("canvas");
  c.width = img.naturalWidth; c.height = img.naturalHeight;
  const g = c.getContext("2d"); g.drawImage(img, 0, 0);
  const data = g.getImageData(0, 0, c.width, c.height).data;
  // 从底部向上找到第一行不是品红色占位的像素，作为长图的真实底边。
  let bottom = c.height;
  scan: for (let y = c.height - 1; y >= 0; y--) {
    for (let x = 0; x < c.width; x += 7) {
      const i = (y * c.width + x) * 4;
      if (!(data[i] > 240 && data[i + 1] < 16 && data[i + 2] > 240)) { bottom = y + 1; break scan; }
    }
  }
  const out = document.createElement("canvas");
  out.width = c.width; out.height = bottom;
  out.getContext("2d").drawImage(c, 0, 0);
  document.body.textContent = "PNG:" + out.toDataURL("image/png");
};
img.src = "SRC";
</script></body></html>"""


def render(name, text):
    source = HERE / f"promo-{name}.html"
    source.write_text(page(text), encoding="utf-8")
    common = [CHROME, "--headless=new", "--hide-scrollbars", "--disable-gpu", "--virtual-time-budget=5000"]
    dom = subprocess.run(common + [f"--window-size={WIDTH},1000", "--dump-dom", source.as_uri()],
                         capture_output=True, text=True, check=True).stdout
    height = int(re.search(r"<title>H(\d+)", dom).group(1))
    # 新版无头模式的截图高度与可视区域不完全一致：先多留空白（品红色占位），再裁掉。
    raw = HERE / f".raw-{name}.png"
    subprocess.run(common + [f"--window-size={WIDTH},{height + 800}", "--force-device-scale-factor=2",
                             f"--screenshot={raw}", source.as_uri()], capture_output=True, check=True)
    trim = HERE / f".trim-{name}.html"
    trim.write_text(TRIM.replace("SRC", raw.as_uri()), encoding="utf-8")
    dom = subprocess.run(common + ["--allow-file-access-from-files", "--disable-web-security",
                                   "--window-size=400,300", "--dump-dom", trim.as_uri()],
                         capture_output=True, text=True, check=True).stdout
    encoded = re.search(r"PNG:data:image/png;base64,([A-Za-z0-9+/=]+)", dom).group(1)
    output = HERE / f"PandaGPT-{name}.png"
    output.write_bytes(base64.b64decode(encoded))
    raw.unlink()
    trim.unlink()
    print(output.name)


if __name__ == "__main__":
    for key, value in TEXT.items():
        render(key, value)
