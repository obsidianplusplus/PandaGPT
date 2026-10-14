package cn.panda.pandagpt.agent;

import cn.panda.pandagpt.ai.AiRequest;

/**
 * 工具执行结果，原样作为工具消息交给模型。
 *
 * @param image 可选图片；Agent 会在工具结果之后以用户消息的形式附上
 */
public record ToolResult(String content, AiRequest.Image image) {
    public static ToolResult ok(String content) {
        return new ToolResult(content, null);
    }

    /** 失败结果；模型会看到错误原因并自行调整，而不是让整个回合失败。 */
    public static ToolResult error(String reason) {
        return new ToolResult("ERROR: " + reason, null);
    }
}
