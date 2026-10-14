package cn.panda.pandagpt.config;

import cn.panda.pandagpt.i18n.LocalizedException;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 支持的模型服务商；{@link #id()} 与配置文件中的写法一致。
 *
 * <p>大多数服务商提供 OpenAI Chat Completions 兼容接口，共用同一套线格式，只在默认地址、默认模型、 鉴权请求头与输出长度字段上有差别。默认模型名称会随服务商更新，服主应以服务商控制台为准。
 */
public enum Provider {
    OPENAI("openai", Wire.OPENAI, "https://api.openai.com/v1/chat/completions", "gpt-4.1-mini", true),
    GEMINI(
            "gemini",
            Wire.GEMINI,
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent",
            "gemini-3.8-flash",
            true),
    ANTHROPIC("anthropic", Wire.ANTHROPIC, "https://api.anthropic.com/v1/messages", "claude-sonnet-5-5", true),
    AZURE("azure", Wire.OPENAI, "", "gpt-4.1-mini", true),
    XAI("xai", Wire.OPENAI, "https://api.x.ai/v1/chat/completions", "grok-4", true),
    MISTRAL("mistral", Wire.OPENAI, "https://api.mistral.ai/v1/chat/completions", "mistral-medium-latest", true),
    GROQ("groq", Wire.OPENAI, "https://api.groq.com/openai/v1/chat/completions", "openai/gpt-oss-120b", false),
    OPENROUTER("openrouter", Wire.OPENAI, "https://openrouter.ai/api/v1/chat/completions", "openai/gpt-4.1-mini", true),
    DEEPSEEK("deepseek", Wire.OPENAI, "https://api.deepseek.com/chat/completions", "deepseek-chat", false),
    QWEN("qwen", Wire.OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen-plus", false),
    MOONSHOT("moonshot", Wire.OPENAI, "https://api.moonshot.cn/v1/chat/completions", "kimi-k2-turbo-preview", false),
    ZHIPU("zhipu", Wire.OPENAI, "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-4.6", false),
    DOUBAO(
            "doubao",
            Wire.OPENAI,
            "https://ark.cn-beijing.volces.com/api/v3/chat/completions",
            "doubao-seed-1-6-250615",
            true),
    SILICONFLOW(
            "siliconflow",
            Wire.OPENAI,
            "https://api.siliconflow.cn/v1/chat/completions",
            "deepseek-ai/DeepSeek-V3",
            false),
    CUSTOM("custom", Wire.OPENAI, "", "", false);

    /** 请求/响应线格式。 */
    public enum Wire {
        OPENAI,
        GEMINI,
        ANTHROPIC
    }

    private final String id;
    private final Wire wire;
    private final String defaultUrl;
    private final String defaultModel;
    private final boolean defaultVision;

    Provider(String id, Wire wire, String defaultUrl, String defaultModel, boolean defaultVision) {
        this.id = id;
        this.wire = wire;
        this.defaultUrl = defaultUrl;
        this.defaultModel = defaultModel;
        this.defaultVision = defaultVision;
    }

    public String id() {
        return id;
    }

    public Wire wire() {
        return wire;
    }

    /** 默认接口地址；为空表示必须由服主配置（Azure 与自定义兼容接口）。 */
    public String defaultUrl() {
        return defaultUrl;
    }

    public String defaultModel() {
        return defaultModel;
    }

    /** 默认模型是否接受图片输入。 */
    public boolean defaultVision() {
        return defaultVision;
    }

    /** OpenAI 官方使用 max_completion_tokens，其余兼容接口普遍只认 max_tokens。 */
    public String maxTokensField() {
        return this == OPENAI ? "max_completion_tokens" : "max_tokens";
    }

    /** 全部服务商编号，用于错误提示。 */
    public static String ids() {
        return Arrays.stream(values()).map(Provider::id).collect(Collectors.joining(", "));
    }

    public static Provider parse(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        for (Provider provider : values()) {
            if (provider.id.equals(normalized)) {
                return provider;
            }
        }
        throw new LocalizedException("config.provider_invalid", ids());
    }

    @Override
    public String toString() {
        return id;
    }
}
