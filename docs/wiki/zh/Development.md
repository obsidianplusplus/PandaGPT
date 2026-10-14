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
├── agent/          Agent 循环、工具注册、技能与提示词
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

1. 在 `agent/tools/` 中用 `AgentTool.of(名称, 描述, 参数 Schema, 可用条件, 动作栏提示, 处理函数)` 定义；需要等待多个 tick 时用 `AgentTool.async`，处理函数返回 `CompletableFuture`；
2. 在插件入口的 `ToolRegistry` 中注册；
3. 在某个技能文件的 `tools:` 中列出它，否则模型看不到；
4. 如果是只读工具且适合主动回合，加入 `AssistantService.PROACTIVE_TOOLS`；
5. `SkillLibraryTest` 会检查技能与工具是否一一对应。

## 新增一个插件集成

1. 在 `pom.xml` 中以 `provided` 作用域加入 API，并排除传递依赖；
2. 在 `integration/` 中实现 `Integration`；
3. 在 `Integrations.SPECS` 中登记集成编号、插件名、适配器类名与简介；
4. 新建带 `requires: <集成编号>` 的技能；
5. 在 `plugin.yml` 的 `softdepend` 中加入插件名。

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
