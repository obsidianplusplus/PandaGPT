package cn.panda.pandagpt.ai;

import java.util.List;

/**
 * 模型的一步回复：文本与工具调用。
 *
 * @param signature Gemini 附在文本上的思考签名，需要在下一步原样带回
 */
public record AiResponse(String text, List<ToolCall> toolCalls, String signature) {
    public AiResponse {
        text = text == null ? "" : text;
        toolCalls = List.copyOf(toolCalls);
    }

    public AiResponse(String text) {
        this(text, List.of(), null);
    }
}
