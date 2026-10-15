<!-- nav:top -->
<div align="right">

🌐 **简体中文** · [English](../en/Development.md) · 📖 [README](../../../README.md) · 🏠 [Wiki 首页](Home.md)

</div>
<!-- /nav:top -->

# 🛠️ 开发指南

## 构建

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

- 需要 JDK 25 与 Maven 3.9+；
- Paper API 固定为 `26.3.build.175-stable`，更新时修改 `paper.version` 并重新验证；
- 外部插件 API 为 `provided` 作用域，只参与编译，不打进 JAR；Gson 会重定位到 `cn.panda.pandagpt.shaded.gson`。

### 使用真实纹理测试

```bash
# 用插件自带的下载器准备官方纹理
mvn -q compile dependency:build-classpath -Dmdep.outputFile=cp.txt -Dmdep.includeScope=compile
java -cp "target/classes:$(cat cp.txt)" cn.panda.pandagpt.vision.texture.VanillaAssets 26.3 /tmp/vanilla-26.3.zip

# 运行包含官方资源的测试
mvn -Dpandagpt.test.assets=/tmp/vanilla-26.3.zip clean verify
```

不提供资源路径时，官方资源测试会被跳过，其余测试照常运行。GitHub Actions 会自动下载并运行全部测试。

## 代码结构

```text
cn.panda.pandagpt
├── agent/          Agent 循环、会话任务运行时、工具、技能与提示词
│   └── tools/      内置工具（按领域分组）
├── ai/             线格式：OpenAI 兼容、Gemini、Anthropic；HTTP 客户端
├── integration/    外部插件适配器（按类名延迟加载）
├── features/       幽灵预览、导航、提醒、死亡记录、统计
├── perception/     感知、目标、地点记忆、主动策略
├── vision/         射线重建、渲染、纹理与官方资源下载
├── actions/        命令提案与确认
├── service/        对话服务与会话
├── config/         不可变配置快照与服务商
├── i18n/           语言文件与本地化
└── listeners/      事件监听
```

## 新增一个工具

1. 在 `agent/tools/` 用 `AgentTool.of` 定义普通同步工具，跨 tick 或异步结果使用 `AgentTool.async`；未标记工具构成串行执行边界。
2. 只有真正独立的只读查询才使用 `readOnlyOf`、`readOnlyAsync` 或 `readOnly` 包装，声明 `independentRead()`；不能把修改玩家或玩法状态的操作伪装成查询。
3. 在插件入口的 `ToolRegistry` 注册，并由技能的 `tools:` 列出。三个内置运行时工具另有对旧核心技能头部的兼容处理。
4. 主动回合会自动考虑已启用技能中的独立只读工具，并检查玩家权限和 `available`。`AssistantService.PROACTIVE_TOOLS` 保留旧的只读工具兼容列表，新独立查询不需要再手工加入该表。
5. 工具入口仍在服务器主线程执行；先复制 Bukkit 数据，再把不可变数据交给后台任务。返回的 future 必须支持取消，不能在工具返回后私自继续改变世界。
6. `SkillLibraryTest` 检查工具归属；按风险补充权限变化、失败、结果大小与取消测试。独立查询还要验证并发上限和结果顺序。

## 新增一个插件集成

1. 在 `integration/` 实现 `Integration`。选择编译期 API 时，在 `pom.xml` 使用 `provided` 并排除传递依赖；选择公共 API 反射时，不增加厂商依赖，使用已安装插件的 ClassLoader。
2. 新反射适配通过 `validate(Plugin)` 校验固定公共类、方法签名和返回类型；校验阶段不要加载玩家、创建档案或访问世界。不兼容时跳过适配并记录警告。
3. 在 `Integrations.SPECS` 登记集成编号、插件名、类名和简介，在 `plugin.yml` 加入 `softdepend`。
4. 新建带 `requires: <集成编号>` 的技能。已知集成编号只有成功启用适配后才满足依赖，不因原插件存在而绕过失败校验。
5. 按原生权限和提问玩家身份筛选数据，为查询设置固定扫描与输出上限；没有证据时返回 unknown 或明确错误。只有独立只读工具才声明并发资格。
6. 补齐技能/注册检查、权限与不兼容测试、中英文支持列表和配置编号。不要把厂商实现类打入 JAR。

## 运行时的职责边界

- `AgentRuntime` 保存主线程内的临时计划、步骤、阶段和一次性复查；不读 Bukkit、不保存长期证据。
- `RuntimeTools` 校验原始玩家请求和计划参数；主动回合只能更新既有目标，不能补充次数预算。
- `AssistantService` 负责玩家轮转、上下文、单玩家后台回合身份、抢占、会话清理和发送前复核。
- `AgentRunner` 管理多步模型循环、独立查询并发、共享总超时和每步文本预算；模型请求的取消传播至客户端等待。
- `ChatGPTIntegration` 仅在共享后台预算允许时推进运行时轮转。AI 决定的等待不应通过阻塞服务器主线程的 sleep 实现。

专项回归可运行：

```bash
mvn -Dtest=AgentRuntimeTest,RuntimeLimitsTest,AssistantServiceTest,ReadConcurrencyTest,SettingsTest test
```

这些测试使用脚本模型和模拟服务器，验证边界与调度；不等于真实 Paper 服务器、厂商插件和付费模型的长时间联调。完整验证仍使用前面的 `verify` 命令。

## 新增一个模型服务商

- **OpenAI 兼容**：在 `config.Provider` 中加一行（编号、默认地址、默认模型、是否支持图片），并在 `config.yml` 中加入对应段落即可；
- **全新格式**：实现 `ai.WireFormat`，在 `Provider.Wire` 中新增类型，并在 `HttpAiProviderTest` 中覆盖多轮工具调用与图片。

## 代码规范

- 格式：`mvn spotless:apply` 使用 palantir-java-format 统一格式；`mvn verify` 会检查格式。
- 注释一律使用中文：类型用 `/** 一句话职责说明。线程：……。 */` 说明职责与线程约束；行内注释写在代码上方，解释“为什么”。
- 面向玩家或控制台的文字不得硬编码，统一通过 `Lang` / `Msg` 引用语言文件中的键；新增键时需同时补全 12 个语言文件，`MessagesTest` 会检查键与占位符是否一致。
- 游戏状态只能在服务器主线程读取；工作线程只处理不可变数据。

## 提交 PR

欢迎提交 Issue 与 Pull Request。请确保 `mvn clean verify` 通过，并说明改动在真实服务器上的验证情况。

<!-- nav:bottom -->
---

<div align="center">

⬅️ [❓ 常见问题](FAQ.md) &nbsp;·&nbsp; [📚 目录](Home.md)

</div>
<!-- /nav:bottom -->
