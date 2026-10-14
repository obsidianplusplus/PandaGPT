package cn.panda.pandagpt.ai;

import com.google.gson.JsonObject;
import java.util.List;

/**
 * 一次模型请求的不可变快照。
 *
 * @param tools 本步可用的函数声明（name、description、parameters）
 * @param finalAnswer 为 true 时禁止再调用工具，要求模型直接给出最终回答
 */
public record AiRequest(String systemPrompt, List<Message> messages, List<JsonObject> tools, boolean finalAnswer) {
    /** PNG 图片（Base64）。 */
    public record Image(String mediaType, String base64) {
        public Image {
            if (!"image/png".equals(mediaType) || base64 == null || base64.length() > 4_194_304) {
                throw new IllegalArgumentException("Invalid vision image");
            }
        }
    }

    public AiRequest(String systemPrompt, List<Message> messages) {
        this(systemPrompt, messages, List.of(), false);
    }

    public AiRequest(String systemPrompt, List<Message> messages, List<JsonObject> tools) {
        this(systemPrompt, messages, tools, false);
    }

    public AiRequest {
        messages = List.copyOf(messages);
        tools = tools.stream().map(JsonObject::deepCopy).toList();
    }
}
