package cn.panda.pandagpt.ai;

import java.util.List;

/**
 * Agent 对话中的一条消息。
 *
 * <p>role 取值：user、assistant、tool。assistant 消息可携带工具调用；tool 消息是某次调用的结果；user 消息可附带一张图片。
 */
public record Message(
        String role,
        String content,
        List<ToolCall> toolCalls,
        ToolCall answers,
        AiRequest.Image image,
        String signature) {

    public Message {
        content = content == null ? "" : content;
        toolCalls = List.copyOf(toolCalls);
    }

    public Message(String role, String content) {
        this(role, content, List.of(), null, null, null);
    }

    public static Message user(String text) {
        return new Message("user", text);
    }

    public static Message user(String text, AiRequest.Image image) {
        return new Message("user", text, List.of(), null, image, null);
    }

    public static Message assistant(String text, List<ToolCall> calls, String signature) {
        return new Message("assistant", text, calls, null, null, signature);
    }

    /** 某次工具调用的结果。 */
    public static Message tool(ToolCall call, String result) {
        return new Message("tool", result, List.of(), call, null, null);
    }
}
